# marionette-mc

Typed asyncio client for the [Marionette](https://github.com/Prattlemob/marionette)
Minecraft bridge. Python 3.11+, protocol **2**, package **0.1.0a1** (independent
of mod versions). MIT, confirmed by the project owner. The exact reviewed
[development alpha is published on PyPI](https://pypi.org/project/marionette-mc/0.1.0a1/).
Install with `python -m pip install "marionette-mc==0.1.0a1"`; keep the explicit
pin rather than silently accepting new prereleases. A fresh PyPI installation
passed rendered observation, brief movement and release acceptance on 2026-10-03.
For source development only, use `python -m pip install -e ./python`.

The owner approved this client-only alpha as a narrow exception to the
project's authentication release gate. Mod and stable-release gates remain;
no further upload is authorized. This source README was updated after
publication: the approved wheel/sdist retain their original reviewed bytes,
including pre-publication wording in embedded metadata. M2.5 acceptance does
not establish a separate consumer's integration acceptance.

```python
import asyncio
from marionette_mc import connect

async def main():
    async with connect(required_capabilities=["bridgeSafety", "playerState"]) as client:
        print(client.hello)
        await client.configure(rate_divisor=1, sections=["player"])
        print(await client.next_observation(timeout=5))
        try:
            await client.input(forward=True)
            await asyncio.sleep(0.2)
        finally:
            await client.release()

asyncio.run(main())
```

Run only against a disposable, rendered world with a clear walking area.
Context exit closes the session even on exceptions/cancellation; controller loss
triggers the mod's release-all safety. `release()` explicitly clears queued
controller intent. Neither sending nor closing is an action acknowledgment.
Keep the asyncio event loop responsive: move blocking/CPU work off the loop.
The websockets library answers server pings automatically; there is no reconnect
or command replay policy in this package. Native connections omit Origin and
ignore environment proxies. Current local trust has no authentication.

## API and outcomes

- `connect(uri=..., role="controller" | "observer", sections=..., required_capabilities=...)`
  yields one `Client` after validating protocol 2 and required capability flags.
  `client.require(...)` checks additional capabilities. Missing flags are unsupported.
- `input(forward=..., back=..., left=..., right=..., jump=..., sneak=..., sprint=...,
  attack=..., use=..., tap=["jump", "attack", "use"], hotbar=0)` applies only supplied
  fields. Methods check capabilities for taps/interactions and guard observer roles.
- `look(yaw=..., pitch=..., mode="instant" | "delta" | "smooth", speed=...)` or
  `look(mode="smooth", x=..., y=..., z=..., speed=...)` controls the camera.
  Success is silent; use observations for convergence. No completion event exists.
- `configure(rate_divisor=1, sections=["player"])` selects this session's stream.
  `sections=[]` requests cadence-only frames. Omitted arguments leave settings unchanged.
  `sections=["player", "inventory"]` (in hello via `connect(sections=...)` or in
  configure) adds the typed `inventory` section (`messages.Inventory`: held item,
  hotbar, main, armor, offhand and the open menu descriptor) and requires the
  `inventoryState` capability. Stacks carry the bounded item extras
  (`messages.Stack`), and menu descriptors list `operations` or a `refusal`.
  The inventory section is in the repository source only; the published 0.1.0a1
  alpha cannot select it and ignores the new descriptor fields.
  `sections=["target"]` adds the crosshair hit result (`messages.Target`: kind
  `"block"` with `pos`/`block`/`face`, `"entity"` with `id`/`entity`, or
  `"none"`, always with the vanilla `reach` it is limited to) and requires
  `targetState`; `sections=["world"]` adds dimension, time of day, weather and
  light at the feet (`messages.World`) and requires `worldState`. Both are in
  the repository source only; the published 0.1.0a1 alpha cannot select them.
  `sections=["entities"]` adds the nearby entities (`messages.Entities`: the
  nearest `maxCount` of `total` within `radius`, `truncated` when the cap cut
  the list; each `messages.Entity` has `id`, `type`, `hostility`, position,
  `velocity`, `distance`, and where applicable `health`/`maxHealth`,
  `targetingMe` (`"yes"`/`"no"`/`"unknown"`), `name` or `item`) and requires
  `entityState`. Repository source only, like the sections above.
- `inventory(op, menu=..., source=..., destination=..., hotbar=..., all=False,
  animated=False, timeout=...)` supports open/inspect/move/swap/equip/drop/close.
  Obtain `menu_ref(result["menu"])` from a recent inspect before mutations.
  Results describe client prediction, not authoritative server acknowledgment.
  With `inventoryStorage`, menu descriptors carry `support` (`messages.MenuSupport`:
  scope `"player"`, `"storage"` or `None` with the failed rules), so modded
  storage that passes the mod's storage analysis is mutable, and refusals carry
  a machine-readable `ServerError.reason` (`messages.RejectionReason`, for example
  `destination_full` or `destination_rejects`). Both are in the repository
  source only; the published 0.1.0a1 alpha keeps the new fields undecoded.
- `scan(size, min=None, timeout=...)` requests one bounded block scan (requires
  `blockScan`; controller only). `size` and `min` are `(x, y, z)` tuples or
  `{"x", "y", "z"}` mappings; without `min` the box is centred on the player's
  feet. The result (`messages.ScanResult`) carries a `palette` of block ids and
  y→z→x `indices`; read it with `scan_block(result, x, y, z)` or iterate
  `scan_blocks(result)`. A `None` block means the client had no data there
  (unloaded chunk). The mod enforces its caps: a box beyond the configured
  radius or above 8192 blocks raises `ServerError` with code `scan_refused`,
  `reason` `over_radius`/`over_volume` and `error["limits"]`; a concurrent scan
  is refused as `busy`, and `release` or leaving the world cancels one
  (`scan_cancelled`). A result inconsistent with the request ends the session as
  `InvalidMessage`. Repository source only; the published 0.1.0a1 alpha has no
  scan API.
- `connect(..., events=True)` subscribes in hello (requires the `events`
  capability); `configure(events=True | False)` changes it later.
  `next_event(timeout=...)` / `events()` consume events in `seq` order from a
  separate bounded queue (`event_limit`, default 1024). Events never answer
  requests. A sequence gap or a frame claiming an `id` ends the session as
  `InvalidMessage`; an overflowing local queue ends it with `CapacityError`.
  `last_event_seq` is the last event received; events already received stay
  readable after closure. Event kinds are typed in `messages` (`DamageEvent`,
  `ChatEvent`, ...); unknown kinds decode as `OtherEvent`. Message types this
  client does not know are counted in `unknown_messages` and ignored.
  These event APIs are in the repository source only; the published
  0.1.0a1 alpha has none and ends its session on unknown message types.
- `next_observation(timeout=...)` / `observations()` consume latest observations.
  A slow consumer skips frames; `observations_coalesced` counts replacements.
- `next_reply(timeout=...)` / `replies()` consume uncorrelated errors and late or
  unmatched inventory results. Awaited inventory errors raise `ServerError` with
  the original typed `.error`; uncorrelated errors remain in this reliable stream.
  Applications should service it alongside their observation/gameplay loop.
- `wait_closed()` returns local `Disconnect(code, reason, cause)`;
  `Disconnected.outcome` carries it when an operation/stream cannot continue.
  This is a Python lifecycle outcome, never a fabricated protocol event.
  Reliable replies already received can be drained after closure. Observations
  cannot be read as live state after disconnect.

TypedDict wire models (including nested player, effects, inventory menus/slots,
commands, events and all incoming types) live in `marionette_mc.messages`. Decoding
checks required fields/types and retains unknown additive fields. `ServerError`,
`VersionError`, `CapabilityError`, `RoleError`, `CapacityError` and `InvalidMessage`
(the latter in `messages`) distinguish failure causes. Opening failures preserve
websockets/OS exceptions; handshake timeout is `TimeoutError`.

Local panic in Minecraft is reported with existing types. Severing an attached
controller ends the session with `Disconnect(1008, "local panic")`. While the
player keeps panic latched, `connect(role="controller")` raises `ServerError`
whose `.error["code"]` is `panic_latched` (`controller_attached` means another
controller holds the slot). Neither is transport loss nor the `pong timeout`
watchdog. Do not reconnect in a loop while latched; the player re-arms in game.

Each inventory call uses a unique session ID. `RequestTimeout.request_id` lets a
caller identify the later reply. Timeout or task cancellation **does not cancel
server work**. Late results/errors go to `next_reply()`; do not blindly resend a
move/drop. A disconnect leaves outstanding outcomes unknown. Re-inspect state
in a separately chosen new session before deciding what to do. To stop current
controller work, use release or close, understanding already-completed world
changes cannot be undone. An unmatched reply is not proof that it is safe to retry.

Default bounds: 64 reliable replies, 32 pending requests, 1024 events, one latest observation,
128 KiB incoming frames, 16 transport frames, 32 KiB write high-water mark,
64 KiB outgoing commands, 5-second connect/hello timeout, 10-second request/write
timeout and 1-second close timeout. Queue limits are configurable integers 1–1024.
Reliable overflow closes with an explicit local `CapacityError` cause; replies
beyond capacity are lost with unknown outcomes. The reader never waits for a
consumer queue and therefore continues servicing WebSocket liveness. Streams have
one logical consumer each; concurrent readers compete rather than receive broadcasts.

## Local verification and future publication

From the repository root:

```sh
python3.11 -m venv .venv
.venv/bin/pip install -r python/requirements-dev.txt
.venv/bin/pip install --no-build-isolation -e ./python
.venv/bin/python -m unittest discover -s python/tests -v
(cd python && ../.venv/bin/mypy)
.venv/bin/python -m build --no-isolation python
./gradlew build
```

`requirements-dev.txt` pins build/test dependencies, including transitives.
The shared `tests/fixtures/protocol2.json` ships in the sdist; Java's
`PythonCompatibilityTest` parses those commands and compares its actual message
builders to the same expected frames. Offline tests use real local WebSockets.
The wheel contains only the client, type marker and license/metadata. The sdist
also contains self-contained offline tests and fixtures. Neither includes worlds,
private harnesses, consumer policy, caches, credentials or mod build output.

For any future publication: review exact distributions and SHA256 hashes,
resolve applicable release prerequisites, establish PyPI project ownership and
credentials, and obtain explicit upload authorization. The initial client-alpha
exception does not authorize subsequent uploads or settle authentication.
After each release, verify the exact published pin in a clean environment and
repeat rendered smoke acceptance; local wheel/sdist success alone cannot do so.
