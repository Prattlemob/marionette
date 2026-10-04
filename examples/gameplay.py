#!/usr/bin/env python3
"""M3.7 gameplay controls: chat, commands, respawn, swap hands and held-use items.

Each subcommand performs one explicit action the caller chose; the mod makes no
gameplay or social decision. Examples::

    python examples/gameplay.py say "hello there"
    python examples/gameplay.py command "time set day"   # refused unless chat.allowCommands
    python examples/gameplay.py respawn                  # refused unless the player is dead
    python examples/gameplay.py swap                     # main hand <-> offhand
    python examples/gameplay.py hold-use 1.5             # draw a bow, raise a shield, charge a crossbow

``hold-use`` holds ``use`` for the given seconds and lets go; letting go fires a drawn
bow or throws a charged trident, as a human releasing the button would. Every
refusal prints the reason and, for chat, the limits in force. Needs the
in-repository client; the published 0.1.0a1 alpha has none of these requests.
"""
import argparse
import asyncio

from marionette_mc import ServerError
from _common import connect


def activity(frame):
    player = frame.get("player", {})
    return {k: player.get(k) for k in ("health", "blocking", "usingItem", "vehicle", "swimming")}


async def run(port, action, argument):
    capability = {"say": "chat", "command": "chat", "respawn": "respawn", "swap": "swapHands",
                  "hold-use": "playerActivity"}[action]
    async with connect(f"ws://127.0.0.1:{port}/", required_capabilities=[capability]) as client:
        try:
            if action == "say":
                print(await client.chat(argument))
            elif action == "command":
                print(await client.chat(command=argument))
            elif action == "respawn":
                print(await client.respawn())
            elif action == "swap":
                await client.input(tap=["swap_hands"])
                await asyncio.sleep(0.3)
            else:
                await client.input(use=True)
                try:  # asyncio.timeout raises TimeoutError when its block exits
                    async with asyncio.timeout(float(argument)):
                        while True:
                            print(activity(await client.next_observation()), flush=True)
                            await asyncio.sleep(0.25)
                except TimeoutError:
                    pass
                await client.input(use=False)
                await asyncio.sleep(0.2)
                print("released:", activity(await client.next_observation()))
        except ServerError as error:
            extra = {k: error.error[k] for k in ("limits", "retryAfterMs") if k in error.error}
            print(f"refused: {error.code} ({error.reason}) {extra}")
        finally:
            await client.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=24680)
    parser.add_argument("action", choices=["say", "command", "respawn", "swap", "hold-use"])
    parser.add_argument("argument", nargs="?", default="")
    args = parser.parse_args()
    if args.action in ("say", "command") and not args.argument:
        parser.error(f"{args.action} needs text")
    if args.action == "hold-use":
        seconds = float(args.argument or 1.0)
        if not 0 < seconds <= 10:
            parser.error("hold-use takes 0-10 seconds")
        args.argument = str(seconds)
    asyncio.run(run(args.port, args.action, args.argument))


if __name__ == "__main__":
    main()
