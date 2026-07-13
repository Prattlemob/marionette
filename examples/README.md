# Marionette examples

Reference agents speaking the current protocol
([`protocol/v0-draft.md`](../protocol/v0-draft.md)). All need Python 3.11+
and `pip install websockets`, plus a running Marionette client that has
joined a world.

- `probe.py` — connect, print observations, hold forward for 3 s, release.
- `walk_square.py` — walk a ~5-block square and report the return error.
  Also the target for the disconnect-safety test: `kill -9` it mid-walk and
  the player must stop within one tick.
