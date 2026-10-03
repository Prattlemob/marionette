# Marionette examples

Reference agents speaking the current protocol
([`protocol/v1.md`](../protocol/v1.md)). All need Python 3.11+
and `pip install websockets`, plus a running Marionette client that has
joined a world.

- `probe.py` — connect, print observations, hold forward for 3 s, release.
- `walk_square.py` — walk a ~5-block square and report the return error.
  Also the target for the disconnect-safety test: `kill -9` it mid-walk and
  the player must stop within one tick.
- `full_movement.py` — M3.1: every held control plus tap-jump vs held
  jump and a sprint-jump, with measured speeds; `--sneak-edge` runs the
  manual sneak-to-a-drop safety check.
- `observer.py` — M2.4: read-only second connection (`role: "observer"`).
  Run it *alongside* a controller example: it slows its own stream with
  `configure` (the controller's cadence is untouched) and shows actuation
  being refused with `role_forbidden`. `kill -9`-ing it must not disturb
  the player or the controller.
- `look_points.py` — M3.2: five smoothed look-at pans around the player,
  then contrast cases (double-speed pan, instant snap, delta burst); the
  D5 experiment driver and demo.
- `interact.py` — M3.3: attack/use/hotbar. Provision the hotbar first
  (slot 0 iron pickaxe, slot 1 dirt, slot 2 cooked beef, hunger not
  full): mines a block to completion, places a block, eats — no
  keyboard. Watch the rendered client.

### Inventory and containers (M3.4)

`python inventory.py [--port 24680] [--chest] [--instant]` exercises whole-stack move,
number-key hotbar swap, equip, and dropping one item. It uses the additive
`inventory` capability and the menu descriptor returned by `inspect`, without
assuming menu slot offsets. The example animates a visible cursor by default
(`inventoryAnimation` capability); `--instant` keeps same-tick execution.
Move/equip show real pickup and placement; swap/drop show a cursor approach
and click cue. Each action awaits its result before continuing. Requires Python 3.11+ and `pip install websockets`.

Use a survival/adventure test world. Close all screens, empty the cursor,
leave hotbar slot 0 and the head armor slot empty, and provision:

- First main-inventory slot (`main.0`): 16 dirt.
- Second main-inventory slot (`main.1`): one iron helmet.
- Third main-inventory slot (`main.2`): 8 cobblestone.
- Second hotbar slot (`hotbar.1`): one stick, to demonstrate a swap.

With cheats enabled, `/clear @s` followed by these commands sets up the items
(**clear removes existing inventory; use a disposable test world**):

```text
/item replace entity @s inventory.0 with minecraft:dirt 16
/item replace entity @s inventory.1 with minecraft:iron_helmet
/item replace entity @s inventory.2 with minecraft:cobblestone 8
/item replace entity @s hotbar.1 with minecraft:stick
```

For `--chest`, point at a reachable, closed chest with an empty slot before
starting. After the inventory sequence the example taps normal use, waits for
the chest menu, and moves the remaining dirt into its first empty container
slot using the same generic move command. A dropped item may be picked up
again if the player stands on it. The script reports errors rather than blindly
retrying mutations. Responses reflect client prediction; server synchronization
can still correct them. Full wire rules: [protocol/v1.md](../protocol/v1.md#inventory--menu-addressed-inventory-actions).
