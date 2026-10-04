# Marionette examples

Reference agents in Python speaking protocol 2
([`protocol/v1.md`](../protocol/v1.md)). Each one is a short, standalone
script; together they are the protocol's conformance spot-check. New to
Marionette? Follow the [quickstart](../README.md#quickstart) first.

Every example needs Python 3.11+, the `marionette-mc` client and a running
Marionette client that has joined a world. Run them from the repository root,
for example `python examples/probe.py`. Each takes the bridge port as an
optional first argument (default 24680).

## Choosing a client package

- **Published alpha** — `python -m pip install "marionette-mc==0.1.0a1"`
  ([PyPI](https://pypi.org/project/marionette-mc/0.1.0a1/)). It speaks protocol
  2 with movement, camera, interaction, observers and inventory actions, and
  keeps working against the current mod, but it predates events, the extra
  observation sections, block scans, crafting, chat/respawn and `status`.
- **In-repository client** — `python -m pip install -e ./python` from the
  repository root. It supports every current capability. It is unreleased: no
  further publication is authorized yet, so install it from this checkout.

The *Client* column below says which you need. Examples marked *0.1.0a1* also
work with the in-repository client.

## Start here

| Example | What it does | Role | Moves the player | Client |
|---|---|---|---|---|
| [`probe.py`](probe.py) | Connects, prints the handshake and observations, holds forward for 0.2 s, then shows `configure` slowing the stream | controller | 0.2 s forward | 0.1.0a1 |
| [`dashboard.py`](dashboard.py) | Live read-only player dashboard: position, rotation, velocity, health, hunger, XP, flags and effects | observer | no | 0.1.0a1 |
| [`walk_square.py`](walk_square.py) | Walks a ~5-block square with bounded holds (at most 3 s a side, stops when blocked, never jumps) and reports how far from the start it ended | controller | yes | 0.1.0a1 |
| [`reference_agent.py`](reference_agent.py) | The end-to-end scripted reference agent (no AI): scans the terrain, chooses the direction with the most walkable ground, walks out with a bounded hold, looks at the nearest entity, walks back, stops if the human takes over, and reports its events | controller | yes | in-repo |

`walk_square.py` is also the disconnect-safety check: `kill -9` it mid-walk
and the player stops within one tick. `reference_agent.py` shows the parts a
real agent needs: capability checks, a display name for the status HUD, a
perceive–decide–act loop, an event watcher that stops the plan when a
`control` event reports a human pause or panic, bounded holds, and release on
every exit path.

## Feature demos

Read-only (observers; they never change the controls, and Ctrl-C exits):

| Example | Shows | Client |
|---|---|---|
| [`observer.py`](observer.py) | A second, read-only connection (`observer`): slows its own stream with `configure` and shows actuation refused locally with `RoleError`. Run it alongside a controller example. | 0.1.0a1 |
| [`events.py`](events.py) | One-shot events in `seq` order (`events`): damage, death, respawn, item pickup, chat, block broken, dimension change, and human precedence `control` changes | in-repo |
| [`status.py`](status.py) | The `status` query: connection state, agent name, precedence mode, held controls and observation/drop/latency counters, as the HUD shows them. `--once`, `--json` | in-repo |
| [`inventory_view.py`](inventory_view.py) | The `inventory` section: held item, hotbar, main inventory, armor, offhand and the open menu with its slot addresses, operations, storage support and crafting/furnace progress. `--once`, `--json` | in-repo |
| [`target_view.py`](target_view.py) | The `target` and `world` sections: crosshair block or entity within vanilla reach; dimension, time, weather and light. `--once`, `--json` | in-repo |
| [`entity_view.py`](entity_view.py) | The `entities` section: nearby entities nearest first with hostility, speed, health and "targeting me". `--once`, `--json` | in-repo |

Controller demos (each holds controls only briefly and releases on exit):

| Example | Shows | Setup | Client |
|---|---|---|---|
| [`full_movement.py`](full_movement.py) | Every held control with measured speeds: walk, sprint, sneak, strafes, back. `--jumps` adds tap vs held jump and a sprint-jump; `--sneak-edge` is the manual sneak-at-a-drop check | ~35 blocks of flat ground ahead | 0.1.0a1 |
| [`look_points.py`](look_points.py) | Smoothed camera pans to five points, a double-speed pan, an instant snap and delta turns (`camera`) | none | 0.1.0a1 |
| [`interact.py`](interact.py) | Attack, use and hotbar (`interact`): mines the block in front, places dirt, eats | hotbar 0 iron pickaxe, 1 dirt, 2 cooked beef; hunger not full | 0.1.0a1 |
| [`inventory.py`](inventory.py) | Inventory actions (`inventory`): move, hotbar swap, equip, drop with a visible cursor; `--chest` deposits into the storage under the crosshair; `--instant` skips the animation | see [below](#inventory-and-containers) | 0.1.0a1 |
| [`crafting.py`](crafting.py) | Places an ingredient layout chosen on the command line and crafts (`crafting`), e.g. `--place 1=main.0:2 --to hotbar.0 --crafts 2` turns two logs into 8 planks | logs in `main.0` | in-repo |
| [`block_scan.py`](block_scan.py) | One bounded block scan (`blockScan`) printed as layer maps or JSON; `--size X,Y,Z`, `--min X,Y,Z`, `--json`. Over-cap requests print the refusal and its limits | none | in-repo |
| [`gameplay.py`](gameplay.py) | One explicit action per run: `say TEXT`, `command TEXT` (refused unless the human set `chat.allowCommands`), `respawn` (refused unless dead), `swap` (hands), `hold-use SECONDS` (bow, shield, crossbow) | item in hand for `hold-use` | in-repo |

The mod never decides anything in these demos: recipes, destinations, chat
text and targets all come from the command line or from the script's own
rules.

## Inventory and containers

`python examples/inventory.py [--port 24680] [--chest] [--instant]` exercises
whole-stack move, number-key hotbar swap, equip, and dropping one item, using
the menu descriptor returned by `inspect` rather than assumed slot offsets.
Each action awaits its result before continuing.

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
starting. After the inventory sequence the example taps use, waits for a
storage menu, and moves the remaining dirt into its first empty container slot.
With `inventoryStorage`, any menu reported as `support.scope == "storage"`
counts, including modded storage; a refusal prints its `reason` (for example
`destination_rejects` for a restricted slot). A dropped item may be picked up
again if the player stands on it. Responses reflect client prediction; server
synchronization can still correct them. Full wire rules:
[protocol/v1.md](../protocol/v1.md#inventory--menu-addressed-inventory-actions).

## Writing your own agent

- Keep the WebSocket read loop running so the library answers pings. The pong
  watchdog disconnects a frozen reader after 2 seconds by default, so never
  block the event loop for more than about 1.5 seconds; move blocking work to a
  thread.
- Bound every hold and release on every exit path. Closing the session also
  releases everything, but an explicit `release()` makes intent clear.
- Feature-detect with `required_capabilities` or `client.require(...)`; never
  compare version numbers.
- Do not set an Origin header: native clients only. Browser clients are
  rejected under the development trust policy.
- Close 1013 means overload: stop sending and treat pending request outcomes
  as unknown. Reconnect explicitly and inspect inventory before retrying
  mutations; the client never reconnects or replays by itself.
- F8 in Minecraft is the local panic control: it severs the controller (close
  1008 `local panic`) and latches controller admission off. While latched, a
  controller hello raises `ServerError` with `error["code"] == "panic_latched"`.
  Do not retry in a loop: the player re-arms with F9. Observers are unaffected.
- Human input pauses an agent in the default human-priority mode: its
  movement and camera commands are discarded and other actions refused with
  `human_paused`. Subscribe to events to see `control` changes.

`_common.py` holds the examples' shared policy: it watches the reliable reply
stream, interrupts the demo on a server error and closes the session. Gameplay
demos have a 120-second outer deadline. See
[the wire limits](../protocol/v1.md#transport) and
[the client API and bounds](../python/README.md).
