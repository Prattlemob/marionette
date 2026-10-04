#!/usr/bin/env python3
"""Minimal Marionette bridge probe (protocol v2).

Connects, performs the hello handshake, watches observations for a second,
holds `forward` for 0.2 seconds and releases it, then demonstrates the
`configure` message by slowing the observation stream to every 10th tick.

Works with the published client:  pip install "marionette-mc==0.1.0a1"
Usage:     python examples/probe.py [port]     (default 24680)
"""
import asyncio
import sys

from _common import connect

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def watch(ws, seconds):
    """Print observations for `seconds`, keeping the socket drained."""
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (remaining := end - loop.time()) > 0:
        try:
            print(await ws.next_observation(timeout=remaining))
        except TimeoutError:
            break


async def main():
    async with connect(f"ws://127.0.0.1:{PORT}/", role="controller", sections=["player"]) as ws:
        hello = ws.hello
        assert hello.get("type") == "hello", f"handshake rejected: {hello}"
        print(f"connected: protocol {hello['version']}, mod {hello['mod']}, "
              f"capabilities {hello['capabilities']}")
        print("-- observing for 1 s --")
        await watch(ws, 1.0)
        print("-- forward ON for 0.2 s --")
        await ws.input(forward=True)
        await watch(ws, 0.2)
        print("-- forward OFF --")
        await ws.input(forward=False)
        await watch(ws, 1.0)
        if hello["capabilities"].get("configure"):
            print("-- configure rateDivisor 10: expect ~2 observations/second --")
            await ws.configure(rate_divisor=10)
            await watch(ws, 2.0)
            print("-- configure rateDivisor 1: back to every tick --")
            await ws.configure(rate_divisor=1)
            await watch(ws, 1.0)
        else:
            print("-- server lacks configure capability; skipping demo --")


if __name__ == "__main__":
    asyncio.run(asyncio.wait_for(main(), timeout=120))
