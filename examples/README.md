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
