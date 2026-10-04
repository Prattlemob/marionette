# Marionette examples

Reference agents speaking protocol 2
([`protocol/v1.md`](../protocol/v1.md)). All need Python 3.11+
and the `marionette-mc==0.1.0a1` published development-alpha package, plus a running Marionette client that has
joined a world.

- `dashboard.py` — M4.1: read-only live player state, effects, and movement flags.
  Run `python examples/dashboard.py [port]`; Ctrl-C exits without changing controls.
- `events.py` — M4.6: read-only one-shot event log (damage, death, respawn,
  item pickup, chat, block broken, dimension change), one line per event in
  `seq` order. Needs the in-repository client (`pip install -e ./python`); the
  published 0.1.0a1 alpha has no event support. Run `python examples/events.py [port]`.
- `inventory_view.py` — M4.2: read-only held item, hotbar, main inventory,
  armor, offhand and the open menu (type, D8 slot addresses, carried stack,
  supported operations or refusal, and with `inventoryStorage` the menu's
  storage support or the rules it failed) from the `inventory` section. Needs the
  in-repository client; the published 0.1.0a1 alpha cannot select that section.
  Run `python examples/inventory_view.py [port]`; `--once` prints one frame and
  `--json` prints raw frames.
- `target_view.py` — M4.3: read-only crosshair target (block position, id and
  face; entity id and type; or none, within vanilla reach) and world context
  (dimension, time of day, weather, light at the feet) from the `target` and
  `world` sections, for comparison with the F3 debug screen. Needs the
  in-repository client; the published 0.1.0a1 alpha cannot select those sections.
  Run `python examples/target_view.py [port]`; `--once` prints one frame and
  `--json` prints raw frames.
- `entity_view.py` — M4.4: read-only nearby entities from the `entities`
  section, nearest first: type, hostility (hostile/neutral/passive/player/item/
  other), position, speed, health and "targeting me" (`yes`/`no` only where the
  client knows, otherwise `unknown`), with the radius, count cap and truncation
  flag. Needs the in-repository client; the published 0.1.0a1 alpha cannot
  select that section. Run `python examples/entity_view.py [port]`; `--once`
  prints one frame and `--json` prints raw frames.
- `block_scan.py` — M4.5: requests one bounded block scan (`blockScan`,
  controller) around the player or at a given corner and prints each layer as a
  map with a legend, or the decoded blocks as JSON. Needs the in-repository
  client; the published 0.1.0a1 alpha has no scan API. Run
  `python examples/block_scan.py [port] [--size X,Y,Z] [--min X,Y,Z] [--json]`
  (default size 16,8,16). Over-cap requests print the refusal and its limits.
- `probe.py` — connect, print observations, hold forward for 0.2 s, release.
- `walk_square.py` — walk a ~5-block square and report the return error.
  Also the target for the disconnect-safety test: `kill -9` it mid-walk and
  the player must stop within one tick.
- `full_movement.py` — M3.1: every held control plus tap-jump vs held
  jump and a sprint-jump, with measured speeds; `--sneak-edge` runs the
  manual sneak-to-a-drop safety check.
- `observer.py` — M2.4: read-only second connection (`role: "observer"`).
  Run it *alongside* a controller example: it slows its own stream with
  `configure` (the controller's cadence is untouched) and shows actuation
  being refused locally with `RoleError`. `kill -9`-ing it must not disturb
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
and click cue. Each action awaits its result before continuing. Requires Python 3.11+ and the package installed.

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
a storage menu, and moves the remaining dirt into its first empty container
slot using the same generic move command. With a mod advertising
`inventoryStorage`, any menu reported as `support.scope == "storage"` counts,
including modded storage; a refusal prints its rejection `reason` (for
example `destination_rejects` for a restricted slot). A dropped item may be picked up
again if the player stands on it. The script reports errors rather than blindly
retrying mutations. Responses reflect client prediction; server synchronization
can still correct them. Full wire rules: [protocol/v1.md](../protocol/v1.md#inventory--menu-addressed-inventory-actions).

## Bridge safety

Current protocol-2 servers advertise `bridgeSafety`. Keep the WebSocket read
loop running so the library answers pings; a frozen reader is disconnected by
the configurable pong watchdog (2 seconds by default, so avoid blocking the
event loop for more than about 1.5 seconds). Do not set an Origin header on
native clients. Browser clients are rejected under the development trust
policy. Close 1013 means overload: stop sending and treat pending request
outcomes as unknown.
Reconnect explicitly and inspect inventory before retrying mutations. F8 in the
Minecraft client is the local panic control: it severs the controller (close
1008 `local panic`) and latches controller admission off. While latched, a
controller hello fails with error `panic_latched` (close 1008); with
`marionette-mc` this raises `ServerError` whose `error["code"]` is
`panic_latched`. Do not retry in a loop: wait for the player to re-arm with F9
and surface the state to your operator. Observers are unaffected. See [the wire limits](../protocol/v1.md#transport).

## Package setup

Install the explicit published pin with
`python -m pip install "marionette-mc==0.1.0a1"`.
For source development only, use `python -m pip install -e ./python` from the
repository root. The published pin has passed M2.5's clean-environment and
rendered smoke acceptance; local installation is not a substitute for separate
consumer integration verification. See [the API and bounds](../python/README.md).

All examples use typed client methods. `_common.py` demonstrates an application
policy that monitors reliable errors, interrupts the demo on error, and closes
the session. Gameplay demos have a 120-second outer deadline; observation waits
are bounded and the short probe only moves for 0.2 seconds. The package keeps
reading and coalescing observations during sleeps. There is no automatic replay
or reconnect. Inventory errors and late replies cannot be discarded by an
example-specific receive loop.
