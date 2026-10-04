#!/usr/bin/env python3
"""Marionette status query example (M5.2, ``status``).

Connects as a read-only observer named "status-example" and prints what the
local status HUD shows: connection state, the controller's agent name, the
precedence mode and pause, the agent's held controls, and the observation,
drop and latency counters, once a second. It never changes anything; the
controller's inputs are unaffected.

Requires the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha has no ``status`` support.
Usage:     python status.py [port] [--once] [--json]     (default 24680)
"""
import asyncio
import json
import sys

from _common import connect

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
PORT = int(ARGS[0]) if ARGS else 24680


def ms(value):
    return "-" if value is None else f"{value:.1f} ms"


def describe(status):
    controller = status["controller"]
    line = f"{status['state']:<9} mode {status['mode']}{' (paused)' if status['paused'] else ''}"
    if controller is not None:
        line += (f" | agent {controller['agent'] or 'unnamed'}"
                 f" | held {' '.join(status['held']) or 'none'}{' +pan' if status['panning'] else ''}"
                 f" | obs {controller['observationRate']:.1f}/s sent {controller['observationsSent']}"
                 f" dropped {controller['observationsDropped']} rtt {ms(controller['rttMillis'])}"
                 f" cmd {ms(controller['commandLatencyMillis'])}")
    return line + f" | observers {status['observers']}"


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="observer", sections=[],
                       required_capabilities=["status"], agent="status-example") as client:
        while True:
            status = await client.status()
            print(json.dumps(status) if "--json" in sys.argv else describe(status), flush=True)
            if "--once" in sys.argv:
                return
            await asyncio.sleep(1)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
