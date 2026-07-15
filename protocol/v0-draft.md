# Marionette protocol v0 — DRAFT

> **Status: superseded by [v1.md](v1.md).** This documents exactly what
> the Phase 1 walking skeleton spoke, nothing more. Kept for history; do
> not target it.

## Transport

- WebSocket, text frames, one JSON object per frame.
- Endpoint: `ws://127.0.0.1:24680/` (loopback only; port fixed in v0).
- One controller connection at a time. While one is attached, further
  connections are closed with WebSocket close code `1013`, reason
  `controller already connected`. When the controller drops, the next
  connect is accepted.
- Unknown JSON fields are ignored. Unknown `type` values are errors.

## Handshake

The first message on a connection MUST be `hello`:

```json
{"type": "hello", "version": 0}
```

The mod replies:

```json
{"type": "hello", "version": 0, "mod": "0.1.0"}
```

Any other first message → close code `1002`, reason `hello required first`.
A `version` other than `0` → close code `1002`, reason
`unsupported protocol version`. A repeated `hello` later → an `error`
message (connection stays open).

## Agent → mod messages (after hello)

### input — set-and-hold movement controls

```json
{"type": "input", "forward": true, "sprint": true}
```

Fields (all optional booleans): `forward`, `back`, `left`, `right`,
`jump`, `sneak`, `sprint`. **Omitted fields are unchanged.** Held values
persist until a later message changes them (set-and-hold). While no world
is loaded, `input`, `look`, and `release` are discarded.

### look — raw instant camera set

```json
{"type": "look", "yaw": 90.0, "pitch": 0.0}
```

Both fields required, finite numbers, degrees (Minecraft convention:
yaw 0 = south, increases clockwise; pitch −90 = up, +90 = down). Applied
next tick with no smoothing (smoothing arrives in M3.2).

### release — everything to neutral

```json
{"type": "release"}
```

## Mod → agent messages

### observation — one per client tick while in a world

```json
{"type": "observation", "tick": 1234, "x": 12.5, "y": 64.0, "z": -8.25, "yaw": 90.0, "pitch": 0.0}
```

`tick` counts client ticks since world join. Position is the player's feet;
`yaw`/`pitch` are the actual current rotation. No observations are sent on
the title screen or while no controller is attached.

### error — malformed or invalid input

```json
{"type": "error", "message": "unknown type: fly"}
```

Sent in reply to unparseable/invalid messages; the connection stays open
and held controls are unaffected.

## Safety behavior (normative)

- On disconnect, socket error, or the client leaving the world, **all
  controls release within one client tick**. The player idles; nothing
  stays held.
- Malformed input never crashes the client and never alters held controls.
