"""Offline real-socket tests: no Minecraft, no external network services."""
import asyncio
from contextlib import asynccontextmanager
import json
import unittest

from websockets.asyncio.server import serve

from marionette_mc import (connect, CapabilityError, CapacityError, Disconnected,
                           RequestTimeout, RoleError, ServerError, VersionError)
from marionette_mc.messages import InvalidMessage, decode

HELLO = dict(type="hello", version=2, mod="0.1.0", capabilities=dict(
    configure=True, observer=True, playerState=True, tap=True, camera=True,
    interact=True, inventory=True, inventoryAnimation=True, bridgeSafety=True, events=True,
    inventoryState=True, targetState=True, worldState=True, entityState=True, blockScan=True,
    crafting=True, swapHands=True, respawn=True, chat=True, playerIdentity=True, playerActivity=True))


async def send(ws, **message):
    await ws.send(json.dumps(message))


@asynccontextmanager
async def endpoint(handler, hello=HELLO):
    async def session(ws):
        request = json.loads(await ws.recv())
        assert request['type'] == 'hello' and request['versions'] == [2]
        assert 'Origin' not in ws.request.headers
        if hello is not None:
            await ws.send(json.dumps(hello))
        await handler(ws, request)
    async with serve(session, '127.0.0.1', 0) as server:
        yield f'ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/'


async def idle(ws, request):
    await ws.wait_closed()


class ClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_hello_capability_version_and_role_checks(self):
        for hello, kwargs, error in [
            ({**HELLO, 'version': 1}, {}, VersionError),
            ({**HELLO, 'capabilities': {}}, {'required_capabilities': ['inventory']}, CapabilityError),
            ({**HELLO, 'capabilities': {}}, {'role': 'observer'}, CapabilityError),
            ({**HELLO, 'capabilities': {}}, {'sections': []}, CapabilityError),
            ({'type': 'error', 'code': 'unsupported_version', 'message': 'no', 'supported': [2]}, {}, ServerError),
            ({'type': 'observation', 'tick': 1}, {}, InvalidMessage),
        ]:
            with self.subTest(hello=hello, kwargs=kwargs):
                async with endpoint(idle, hello) as uri:
                    with self.assertRaises(error):
                        async with connect(uri, **kwargs):
                            self.fail('handshake should fail')

    async def test_actual_methods_match_java_fixture_commands(self):
        from pathlib import Path
        fixture = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())
        frames = []
        async def handler(ws, request):
            async for raw in ws:
                command = json.loads(raw)
                frames.append(command)
                if command['type'] == 'inventory':
                    await send(ws, type='inventory_result', id=command['id'], op=command['op'], menu=None)
                if command['type'] == 'respawn':
                    await send(ws, type='action_result', id=command['id'], action='respawn')
                if command['type'] == 'chat':
                    await send(ws, type='action_result', id=command['id'],
                               action='command' if 'command' in command else 'chat')
                if command['type'] == 'scan':
                    size = command['size']
                    await send(ws, type='scan_result', id=command['id'], dimension='minecraft:overworld',
                               min=command.get('min', dict(x=0, y=0, z=0)), size=size, order='yzx',
                               startTick=1, tick=2, palette=['minecraft:air'],
                               indices=[0] * (size['x'] * size['y'] * size['z']))
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                for item in fixture['commands']:
                    wire = dict(item['wire'])
                    kind = wire.pop('type')
                    wire.pop('id', None)
                    if kind == 'hello':
                        continue
                    if kind == 'configure' and 'rateDivisor' in wire:
                        wire['rate_divisor'] = wire.pop('rateDivisor')
                    if kind == 'inventory':
                        if 'from' in wire: wire['source'] = wire.pop('from')
                        if 'to' in wire: wire['destination'] = wire.pop('to')
                    await getattr(client, kind)(**wire)
        expected = [dict(item['wire']) for item in fixture['commands'] if item['wire']['type'] != 'hello']
        for wire, actual in zip(expected, frames, strict=True):
            wire.pop('id', None)
            actual.pop('id', None)
            if wire['type'] == 'inventory':
                wire.setdefault('all', False)
                wire.setdefault('animated', False)
            self.assertEqual(actual, wire)

    async def test_cancel_race_preserves_already_routed_result(self):
        async with endpoint(idle) as uri:
            async with connect(uri) as client:
                async def delivered(message):
                    future = client._pending.pop(message['id'])
                    future.set_result(dict(type='inventory_result', op='inspect', id=message['id'], menu=None))
                    asyncio.current_task().cancel()
                    await asyncio.sleep(0)
                client._send = delivered
                task = asyncio.create_task(client.inventory('inspect'))
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertEqual((await client.next_reply(1))['id'], 'inventory-1')

    async def test_handshake_timeout(self):
        async with endpoint(idle, None) as uri:
            with self.assertRaises(TimeoutError):
                async with connect(uri, handshake_timeout=.03):
                    self.fail()

    async def test_observation_coalesces_and_pongs_without_consumer(self):
        complete = asyncio.Event()
        async def handler(ws, request):
            for tick in range(100):
                await send(ws, type='observation', tick=tick)
            pong = await ws.ping()
            await asyncio.wait_for(pong, 1)
            complete.set()
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                await asyncio.wait_for(complete.wait(), 2)
                await asyncio.sleep(.02)
                self.assertEqual((await client.next_observation())['tick'], 99)
                self.assertEqual(client.observations_coalesced, 99)

    async def test_out_of_order_correlation_and_error(self):
        async def handler(ws, request):
            first, second = [json.loads(await ws.recv()) for _ in range(2)]
            await send(ws, type='observation', tick=1)
            await send(ws, type='inventory_result', op=second['op'], id=second['id'], menu=None)
            await send(ws, type='error', code='inventory_unavailable', message='no menu', id=first['id'])
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                results = await asyncio.gather(client.inventory('open'), client.inventory('inspect'), return_exceptions=True)
                self.assertIsInstance(results[0], ServerError)
                self.assertEqual(results[1]['op'], 'inspect')
                self.assertEqual((await client.next_observation())['tick'], 1)

    async def test_timeout_late_result_and_error_are_reliable(self):
        async def handler(ws, request):
            first = json.loads(await ws.recv())
            await asyncio.sleep(.06)
            await send(ws, type='inventory_result', op='inspect', id=first['id'], menu=None)
            second = json.loads(await ws.recv())
            await asyncio.sleep(.06)
            await send(ws, type='error', code='inventory_cancelled', message='released', id=second['id'])
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                for expected in ('inventory_result', 'error'):
                    with self.assertRaises(RequestTimeout) as caught:
                        await client.inventory('inspect', timeout=.01)
                    late = await client.next_reply(1)
                    self.assertEqual(late['id'], caught.exception.request_id)
                    self.assertEqual(late['type'], expected)

    async def test_cancelled_request_keeps_late_reply(self):
        received, reply_now = asyncio.Event(), asyncio.Event()
        async def handler(ws, request):
            command = json.loads(await ws.recv())
            received.set()
            await reply_now.wait()
            await send(ws, type='inventory_result', op='inspect', id=command['id'], menu=None)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                task = asyncio.create_task(client.inventory('inspect'))
                await received.wait()
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                reply_now.set()
                self.assertEqual((await client.next_reply(1))['id'], 'inventory-1')

    async def test_disconnect_wakes_all_waiters_and_pending(self):
        async def handler(ws, request):
            await ws.recv()
            await ws.close(1001, 'world left')
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                tasks = [client.inventory('inspect'), client.next_observation(), client.next_reply()]
                results = await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 2)
                self.assertTrue(all(isinstance(result, Disconnected) for result in results))
                outcome = await client.wait_closed()
                self.assertEqual((outcome.code, outcome.reason), (1001, 'world left'))
                await client.close()

    async def test_reliable_overflow_is_explicit_and_bounded(self):
        async def handler(ws, request):
            for i in range(3):
                await send(ws, type='error', code='invalid_field', message=str(i))
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, reliable_limit=2) as client:
                outcome = await asyncio.wait_for(client.wait_closed(), 2)
                self.assertIsInstance(outcome.cause, CapacityError)
                self.assertEqual((await client.next_reply())['message'], '0')
                self.assertEqual((await client.next_reply())['message'], '1')
                with self.assertRaises(Disconnected):
                    await client.next_reply()

    async def test_pending_limit_and_session_cleanup(self):
        received = asyncio.Event()
        async def handler(ws, request):
            await ws.recv()
            received.set()
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, pending_limit=1) as client:
                task = asyncio.create_task(client.inventory('inspect'))
                await received.wait()
                with self.assertRaises(CapacityError):
                    await client.inventory('inspect')
            with self.assertRaises(Disconnected):
                await task
            self.assertTrue(client._reader.done())

    async def test_observer_rejects_actuation_locally(self):
        async def handler(ws, request):
            self.assertEqual(request['role'], 'observer')
            self.assertEqual(json.loads(await ws.recv()), {'type': 'configure', 'rateDivisor': 5, 'sections': []})
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, role='observer') as client:
                for action in (client.input(forward=True), client.release(), client.inventory('inspect'), client.look(yaw=0, pitch=0)):
                    with self.assertRaises(RoleError):
                        await action
                await client.configure(rate_divisor=5, sections=[])

    async def test_methods_validate_and_emit_supported_shapes(self):
        frames = []
        async def handler(ws, request):
            async for raw in ws:
                frames.append(json.loads(raw))
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                await client.input(forward=True, tap=['jump'], hotbar=0)
                await client.look(mode='smooth', x=1, y=2, z=3, speed=2)
                await client.look(yaw=90, pitch=0)
                await client.release()
                for action in (client.input(hotbar=9), client.input(forward=1), client.look(yaw=float('nan'), pitch=0),
                               client.look(mode='smooth', x=1, y=2), client.configure(rate_divisor=True),
                               client.inventory('move'), client.inventory('inspect', timeout=0)):
                    with self.assertRaises(ValueError):
                        await action
        self.assertEqual([f['type'] for f in frames], ['input', 'look', 'look', 'release'])
        self.assertEqual(frames[0]['tap'], ['jump'])

    async def test_malformed_incoming_closes_reader(self):
        for frame in ('{"type":"observation","tick":true}', '{"type":"event"}', b'binary'):
            async def handler(ws, request):
                await ws.send(frame)
                await ws.wait_closed()
            async with endpoint(handler) as uri:
                async with connect(uri) as client:
                    outcome = await asyncio.wait_for(client.wait_closed(), 2)
                    self.assertIsInstance(outcome.cause, InvalidMessage)

    async def test_body_cancellation_closes_transport(self):
        ready, closed = asyncio.Event(), asyncio.Event()
        async def handler(ws, request):
            await ws.wait_closed()
            closed.set()
        async with endpoint(handler) as uri:
            async def body():
                async with connect(uri):
                    ready.set()
                    await asyncio.Future()
            task = asyncio.create_task(body())
            await ready.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            await asyncio.wait_for(closed.wait(), 1)


class DecodeTests(unittest.TestCase):
    def test_additive_fields_retained(self):
        self.assertEqual(decode('{"type":"observation","tick":0,"future":42}')['future'], 42)

    def test_invalid_frames(self):
        for raw in ('[]', 'null', '{', '{"type":[]}', '{"type":"error","code":3}',
                    '{"type":"observation","tick":NaN}', '{"type":"inventory_result","op":"inspect"}'):
            with self.subTest(raw=raw), self.assertRaises(InvalidMessage):
                decode(raw)


if __name__ == '__main__':
    unittest.main()
