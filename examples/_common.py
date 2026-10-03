"""Example-only policy: stop the demo on a server error; never reconnect/replay."""
import asyncio
from contextlib import asynccontextmanager
from marionette_mc import connect as open_session, ServerError


@asynccontextmanager
async def connect(*args, **kwargs):
    async with open_session(*args, **kwargs) as client:
        async def monitor():
            async for reply in client.replies():
                if reply['type'] == 'error':
                    raise ServerError(reply)
                print('late/unmatched inventory result:', reply)

        # A reliable error interrupts the demo and closes its controller session.
        async with asyncio.TaskGroup() as group:
            watcher = group.create_task(monitor())
            try:
                yield client
            finally:
                watcher.cancel()
