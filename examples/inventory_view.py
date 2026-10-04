#!/usr/bin/env python3
"""Read-only live inventory and open-menu dashboard (M4.2, `inventoryState`).

Needs the in-repository client (pip install -e ./python); the published
0.1.0a1 alpha cannot select the inventory section.
Usage: python examples/inventory_view.py [port] [--once]
Pick up, eat, move items or open a chest to watch the view change.
"""
import asyncio
import json
import sys

from _common import connect


def describe(stack):
    if stack["count"] == 0:
        return "-"
    text = f"{stack['item'].removeprefix('minecraft:')} x{stack['count']}"
    if "maxDamage" in stack:
        text += f" [{stack['maxDamage'] - stack['damage']}/{stack['maxDamage']}]"
    if "name" in stack:
        text += f' "{stack["name"]}{"..." if stack.get("nameTruncated") else ""}"'
    for key in ("enchantments", "storedEnchantments"):
        if key in stack:
            text += " {" + ", ".join(f"{e['id'].removeprefix('minecraft:')} {e['level']}" for e in stack[key])
            text += ", ...}" if stack.get(key + "Truncated") else "}"
    if "potion" in stack:
        text += f" ({stack['potion'].removeprefix('minecraft:')})"
    return text


def render(tick, inv):
    lines = [f"Marionette inventory | tick {tick}" + ("  (reduced detail)" if inv.get("reduced") else ""),
             f"Held (hotbar.{inv['selected']}): {describe(inv['mainHand'])}",
             f"Offhand: {describe(inv['offhand'])}",
             "Armor: " + "  ".join(f"{k}={describe(v)}" for k, v in inv["armor"].items()),
             "Hotbar:"]
    lines += [f"  {'>' if i == inv['selected'] else ' '}hotbar.{i}: {describe(s)}" for i, s in enumerate(inv["hotbar"])]
    filled = [(i, s) for i, s in enumerate(inv["main"]) if s["count"]]
    lines.append(f"Main ({len(filled)}/27 filled):")
    lines += [f"   main.{i}: {describe(s)}" for i, s in filled]
    menu = inv["menu"]
    if menu is None:
        lines.append("Menu: none open")
    else:
        ops = ", ".join(menu["operations"]) or f"none ({menu['refusal']})"
        lines.append(f"Menu {menu['type']} id={menu['containerId']} state={menu['stateId']} "
                     f"slots={menu['slotCount']}{' (truncated)' if menu.get('truncated') else ''} ops: {ops}")
        lines.append(f"  carried: {describe(menu['carried'])}")
        lines += [f"  slot {s['slot']}{' ' + s['alias'] if 'alias' in s else ''}: {describe(s)}"
                  + (f" [refused: {s['refused']}]" if "refused" in s else "")
                  for s in menu["slots"] if "alias" not in s or s["count"]]
    return "\n".join(lines)


async def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 24680
    async with connect(f"ws://127.0.0.1:{port}/", role="observer", sections=["inventory"]) as ws:
        async for frame in ws.observations():
            inv = frame.get("inventory")
            if inv is None:
                continue
            if "--json" in sys.argv:
                print(json.dumps(frame), flush=True)
            else:
                print(("\033[H\033[2J" if sys.stdout.isatty() else "") + render(frame["tick"], inv), flush=True)
            if "--once" in sys.argv:
                return


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
