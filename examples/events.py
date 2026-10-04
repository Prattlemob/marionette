#!/usr/bin/env python3
"""Read-only one-shot event logger (protocol 2, `events` capability, M4.6).

Requires the in-repository client (`python -m pip install -e ./python`); the
published 0.1.0a1 alpha predates events. Usage: python examples/events.py [port]
Take damage, die and respawn, pick up an item, chat, break a block or change
dimension: each event prints once, in order. With `humanPrecedence` it also shows
human precedence changes (`control`): a human pausing the agent, the input lockout
and panic. Ctrl-C exits without changing controls.
"""
import asyncio
import sys

from marionette_mc import Disconnected

from _common import connect


def describe(event):
    kind = event["event"]
    if kind == "damage":
        source = event["source"] or {}
        known = lambda value: "?" if value is None else value  # null means unknown, never zero
        return (f"damage {known(event['amount'])} from {source.get('type', 'unknown source')}"
                f" → health {known(event['health'])}")
    if kind == "death":
        return f"death: {event['message']}"
    if kind == "respawn":
        return f"respawn in {event['dimension']}"
    if kind == "dimension_change":
        return f"dimension {event['from']} → {event['to']}"
    if kind == "item_pickup":
        return f"picked up {event['count']} × {event['item'] or 'unknown item'}"
    if kind == "chat":
        return f"{event['kind']}: {event['text']}"
    if kind == "block_broken":
        pos = event["pos"]
        return f"broke {event['block']} at {pos['x']} {pos['y']} {pos['z']} (client prediction)"
    if kind == "control":
        inputs = f" by {', '.join(event['inputs'])}" if event["inputs"] else ""
        state = "agent paused" if event["paused"] else "agent may drive"
        return f"control {event['mode']}: {state} ({event['cause']}{inputs})"
    return f"{kind} (unknown kind; ignored)"


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
    async with connect(f"ws://127.0.0.1:{port}/", role="observer", sections=[], events=True) as client:
        print("Logging events; world sessions and ticks identify where each occurred.", flush=True)
        try:
            async for event in client.events():
                print(f"#{event['seq']:<4} tick {event['tick']:<6} [{event['basis']}] {describe(event)}",
                      flush=True)
        except Disconnected as exc:  # game closed or left: no reconnect, no replay
            print(f"Session ended ({exc.outcome.code}): {exc.outcome.reason}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
