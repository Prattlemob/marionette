#!/usr/bin/env python3
"""Scripted end-to-end reference agent (no AI): perceive, decide, act, report.

One controller session that exercises the loop every agent needs, with every
decision made by the plain rules in this file:

1. connect with a display name, the capabilities it relies on, the player,
   world and entities sections and the event stream; print the world context
   and the status the local HUD shows;
2. perceive the terrain with one bounded block scan and choose the cardinal
   direction with the longest walkable run (solid floor, two clear blocks);
3. turn there with a smoothed pan, walk out with a bounded forward hold, look
   at the nearest entity (if any), then turn round and walk back;
4. obey the human: a `control` event that pauses the agent, or panic, ends the
   plan at once (nothing is retried), and a disconnect ends the run;
5. release everything and report where it ended and the events it saw.

It never jumps or sprints, and holds forward at most LEG_SECONDS per leg.
Stand the player in an open, flat area of a disposable world.

Needs the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha has no events, block scan, status or extra sections.
Usage: python examples/reference_agent.py [port] [--max-distance BLOCKS]
"""
import asyncio
import math
import sys
from collections import Counter

from marionette_mc import Disconnected, ServerError, scan_block

from _common import connect

ARGS = [a for i, a in enumerate(sys.argv[1:], 1) if not a.startswith("--") and sys.argv[i - 1] != "--max-distance"]
PORT = int(ARGS[0]) if ARGS else 24680
MAX_DISTANCE = int(sys.argv[sys.argv.index("--max-distance") + 1]) if "--max-distance" in sys.argv else 6
LEG_SECONDS = 4.0     # longest forward hold per leg
TURN_SECONDS = 5.0    # longest wait for a smoothed pan to settle
EYE_HEIGHT = 1.62

# Minecraft yaw: 0 faces +Z (south), 90 faces -X (west).
DIRECTIONS = {"south": (0.0, 0, 1), "west": (90.0, -1, 0), "north": (180.0, 0, -1), "east": (-90.0, 1, 0)}
PLANTS = {"minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
          "minecraft:dandelion", "minecraft:poppy", "minecraft:dead_bush", "minecraft:snow"}
UNSAFE_FLOOR = {"minecraft:water", "minecraft:lava", "minecraft:magma_block", "minecraft:campfire",
                "minecraft:soul_campfire", "minecraft:cactus", "minecraft:sweet_berry_bush"}


class Interrupted(Exception):
    """The human took over (pause or panic); the agent stops instead of fighting it."""


def passable(block):
    return block is not None and (block.endswith("air") or block in PLANTS)


def walkable_run(scan, feet, step, limit):
    """Blocks the player can walk from `feet` along `step` before floor or headroom fails."""
    x, y, z = feet
    for k in range(1, limit + 1):
        bx, bz = x + step[0] * k, z + step[1] * k
        floor = scan_block(scan, bx, y - 1, bz)
        if floor is None or passable(floor) or floor in UNSAFE_FLOOR:
            return k - 1
        if not (passable(scan_block(scan, bx, y, bz)) and passable(scan_block(scan, bx, y + 1, bz))):
            return k - 1
    return limit


class Agent:
    def __init__(self, client):
        self.client = client
        self.seen = Counter()
        self.stop = asyncio.Event()
        self.why = None

    async def watch_events(self):
        """Log every event; a human pause or panic stops the plan."""
        async for event in self.client.events():
            self.seen[event["event"]] += 1
            print(f"  event #{event['seq']} {event['event']}"
                  + (f" mode={event['mode']} paused={event['paused']} cause={event['cause']}"
                     if event["event"] == "control" else ""), flush=True)
            if event["event"] == "control" and (event["paused"] or event["mode"] == "panic"):
                self.why = f"human took over ({event['cause']})"
                self.stop.set()
            elif event["event"] in ("death", "dimension_change"):
                self.why = f"situation changed ({event['event']})"
                self.stop.set()

    async def frame(self):
        if self.stop.is_set():
            raise Interrupted(self.why)
        return await self.client.next_observation(timeout=5)

    async def turn(self, **look):
        """Smoothed pan; done when yaw and pitch hold still for three frames."""
        await self.client.look(mode="smooth", **look)
        last, stable = await self.frame(), 0
        async with asyncio.timeout(TURN_SECONDS):
            while stable < 3:
                now = await self.frame()
                moved = (abs(now["player"]["yaw"] - last["player"]["yaw"])
                         + abs(now["player"]["pitch"] - last["player"]["pitch"]))
                stable = stable + 1 if moved < 0.01 else 0
                last = now
        return last

    async def walk(self, distance):
        """Bounded forward hold: stop at `distance`, after LEG_SECONDS, or when blocked."""
        start = (await self.frame())["player"]
        loop = asyncio.get_running_loop()
        began = loop.time()
        walked, reason = 0.0, "time limit"
        await self.client.input(forward=True)
        try:
            while loop.time() - began < LEG_SECONDS:
                p = (await self.frame())["player"]
                walked = math.dist((p["x"], p["z"]), (start["x"], start["z"]))
                if walked >= distance:
                    reason = "arrived"
                    break
                if loop.time() - began > 1.0 and walked < 0.2:
                    reason = "blocked"
                    break
        finally:
            await self.client.input(forward=False)
        return walked, reason

    async def run(self):
        client = self.client
        hello = client.hello
        print(f"connected: protocol {hello['version']}, mod {hello['mod']}")
        status = await client.status()
        print(f"status: {status['state']}, mode {status['mode']}, observers {status['observers']}")

        first = await self.frame()
        world, me = first["world"], first["player"]
        print(f"world: {world['dimension']}, {world['timeOfDay']}, {world['weather']}; "
              f"player at {me['x']:.1f} {me['y']:.1f} {me['z']:.1f}, health {me['health']:g}")

        # Perceive: one bounded scan around the feet, one block below to one above head height.
        feet = (math.floor(me["x"]), math.floor(me["y"]), math.floor(me["z"]))
        reach = MAX_DISTANCE + 1
        scan = await client.scan((2 * reach + 1, 3, 2 * reach + 1),
                                 min=(feet[0] - reach, feet[1] - 1, feet[2] - reach))
        runs = {name: walkable_run(scan, feet, (dx, dz), MAX_DISTANCE)
                for name, (_, dx, dz) in DIRECTIONS.items()}
        print("walkable blocks: " + ", ".join(f"{name} {n}" for name, n in runs.items()))

        # Decide: the longest run; ties go to the first direction listed.
        heading = max(runs, key=runs.get)
        distance = runs[heading] - 0.5
        if distance < 1.0:
            print("decision: no room to walk safely; staying put")
            return
        yaw = DIRECTIONS[heading][0]
        print(f"decision: walk {distance:.1f} blocks {heading}, look around, walk back")

        # Act.
        await self.turn(yaw=yaw, pitch=0.0)
        walked, reason = await self.walk(distance)
        print(f"out: walked {walked:.1f} blocks ({reason})")

        nearby = (await self.frame())["entities"]["nearby"]
        if nearby:
            target = nearby[0]
            print(f"looking at nearest entity: {target['type']} {target['distance']:.1f} blocks away")
            await self.turn(x=target["x"], y=target["y"] + 1.0, z=target["z"])
        else:
            print("no entities nearby; looking at the horizon")
            await self.turn(yaw=yaw + 90.0, pitch=-10.0)

        await self.turn(yaw=yaw + 180.0, pitch=0.0)
        walked_back, reason = await self.walk(walked)
        print(f"back: walked {walked_back:.1f} blocks ({reason})")

        end = (await self.frame())["player"]
        print(f"ended {math.dist((end['x'], end['z']), (me['x'], me['z'])):.1f} blocks from the start")


async def main():
    capabilities = ["camera", "blockScan", "status", "humanPrecedence"]
    try:
        async with connect(f"ws://127.0.0.1:{PORT}/", sections=["player", "world", "entities"],
                           events=True, agent="reference-agent", required_capabilities=capabilities) as client:
            agent = Agent(client)
            watcher = asyncio.create_task(agent.watch_events())
            try:
                await agent.run()
            except (Interrupted, TimeoutError) as stop:
                print(f"stopped: {stop or 'a pan did not settle in time'}")
            finally:
                await client.release()
                await asyncio.sleep(0.2)  # let the release's events arrive
                watcher.cancel()
            print("events seen: " + (", ".join(f"{k} x{n}" for k, n in sorted(agent.seen.items())) or "none"))
    except ServerError as error:
        if error.code == "panic_latched":
            sys.exit("panic is latched: press the re-arm key (default F9) in game, then run again")
        raise
    except Disconnected as lost:
        sys.exit(f"session ended ({lost.outcome.code}): {lost.outcome.reason}")


if __name__ == "__main__":
    asyncio.run(asyncio.wait_for(main(), timeout=120))
