"""Bounded block scan (blockScan): request shape, decoding, gating, errors."""
import asyncio
import json
from pathlib import Path
import unittest

from marionette_mc import (CapabilityError, RoleError, ServerError, connect, scan_block, scan_blocks)
from marionette_mc.messages import InvalidMessage, check_scan, decode

from test_client import HELLO, endpoint, idle, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())
EXAMPLE = FIXTURE['scanResults'][0]


class ScanDecodeTests(unittest.TestCase):
    def test_worked_example_decodes_y_then_z_then_x(self):
        result = decode(json.dumps(EXAMPLE))
        check_scan(result)
        self.assertEqual(scan_block(result, 11, 64, -2), 'minecraft:water')
        self.assertEqual(scan_block(result, 12, 65, -3), 'minecraft:oak_log')
        self.assertEqual(scan_block(result, 10, 64, -3), 'minecraft:stone')
        blocks = list(scan_blocks(result))
        self.assertEqual(blocks[:4], [(10, 64, -3, 'minecraft:stone'), (11, 64, -3, 'minecraft:stone'),
                                      (12, 64, -3, 'minecraft:dirt'), (10, 64, -2, 'minecraft:stone')])
        self.assertEqual(blocks[6], (10, 65, -3, 'minecraft:air'))
        for x, y, z, block in blocks:
            self.assertEqual(scan_block(result, x, y, z), block)
        with self.assertRaises(KeyError):
            scan_block(result, 13, 64, -3)

    def test_unloaded_positions_are_none(self):
        result = decode(json.dumps(FIXTURE['scanResults'][1]))
        check_scan(result)
        self.assertEqual([b for *_, b in scan_blocks(result)],
                         ['minecraft:bedrock', None, 'minecraft:void_air', None])

    def test_inconsistent_results_are_invalid(self):
        for mutate in (lambda r: r['indices'].pop(), lambda r: r['indices'].__setitem__(0, 5),
                       lambda r: r.update(order='xyz')):
            result = json.loads(json.dumps(EXAMPLE))
            mutate(result)
            with self.assertRaises(InvalidMessage):
                check_scan(decode(json.dumps(result)))
        for mutate in (lambda r: r.pop('palette'), lambda r: r['palette'].append(3),
                       lambda r: r['min'].update(x=1.5)):
            result = json.loads(json.dumps(EXAMPLE))
            mutate(result)
            with self.assertRaises(InvalidMessage):
                decode(json.dumps(result))

    def test_refusal_carries_reason_and_limits(self):
        refusal = next(m for m in FIXTURE['messages'] if m.get('reason') == 'over_radius')
        self.assertEqual(decode(json.dumps(refusal))['limits'], {'radius': 16, 'maxVolume': 8192})


def answering(**overrides):
    async def handler(ws, request):
        async for raw in ws:
            command = json.loads(raw)
            if command['type'] != 'scan':
                continue
            size = command['size']
            reply = dict(type='scan_result', id=command['id'], dimension='minecraft:overworld',
                         min=command.get('min', dict(x=-8, y=60, z=-8)), size=size, order='yzx',
                         startTick=10, tick=11, palette=['minecraft:stone', 'minecraft:air'],
                         indices=[i % 2 for i in range(size['x'] * size['y'] * size['z'])])
            reply.update(overrides)
            await send(ws, **reply)
    return handler


class ScanSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_scan_sends_documented_request_and_returns_result(self):
        frames = []
        inner = answering()
        async def handler(ws, request):
            async def recording():
                async for raw in ws:
                    frames.append(json.loads(raw))
                    yield raw
            class Proxy:
                def __aiter__(self): return recording()
                send = ws.send
            await inner(Proxy(), request)
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                result = await client.scan((16, 8, 16))
                boxed = await client.scan({'x': 2, 'y': 1, 'z': 2}, min=(5, -60, 5))
        self.assertEqual(frames[0], {'type': 'scan', 'id': 'scan-1', 'size': {'x': 16, 'y': 8, 'z': 16}})
        self.assertEqual(frames[1]['min'], {'x': 5, 'y': -60, 'z': 5})
        self.assertEqual(len(result['indices']), 2048)
        self.assertEqual(scan_block(result, -7, 60, -8), 'minecraft:air')
        self.assertEqual(boxed['min'], {'x': 5, 'y': -60, 'z': 5})

    async def test_refusal_raises_server_error_and_session_continues(self):
        async def handler(ws, request):
            command = json.loads(await ws.recv())
            await send(ws, type='error', code='scan_refused', reason='over_radius', message='too far',
                       id=command['id'], limits=dict(radius=16, maxVolume=8192), input='{}')
            await answering()(ws, request)
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                with self.assertRaises(ServerError) as caught:
                    await client.scan((16, 8, 16), min=(500, 0, 0))
                self.assertEqual((caught.exception.code, caught.exception.reason), ('scan_refused', 'over_radius'))
                self.assertEqual(caught.exception.error['limits']['radius'], 16)
                self.assertEqual(len((await client.scan((4, 4, 4)))['indices']), 64)

    async def test_mismatched_or_inconsistent_result_closes_session(self):
        for overrides in (dict(size=dict(x=1, y=1, z=1)), dict(indices=[0]), dict(min=dict(x=0, y=0, z=0))):
            with self.subTest(overrides=overrides):
                async with endpoint(answering(**overrides)) as uri:
                    async with connect(uri) as client:
                        with self.assertRaises(InvalidMessage):
                            await client.scan((2, 2, 2), min=(1, 1, 1))
                        self.assertIsNotNone(client._outcome)

    async def test_local_validation_and_gating(self):
        old = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != 'blockScan'}}
        async with endpoint(idle, old) as uri:
            async with connect(uri) as client:
                with self.assertRaises(CapabilityError):
                    await client.scan((1, 1, 1))
        async with endpoint(idle, {**HELLO}) as uri:
            async with connect(uri) as client:
                for size in ((0, 1, 1), (1, 1), (1.0, 1, 1), (8193, 1, 1), {'x': 1, 'y': 1}, [1, 1, 1]):
                    with self.subTest(size=size), self.assertRaises(ValueError):
                        await client.scan(size)
                with self.assertRaises(ValueError):
                    await client.scan((1, 1, 1), min=(0, True, 0))
            async with connect(uri, role='observer') as observer:
                with self.assertRaises(RoleError):
                    await observer.scan((1, 1, 1))

    async def test_unrequested_result_is_a_reliable_reply_not_a_crash(self):
        async def handler(ws, request):
            await send(ws, **EXAMPLE)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                self.assertEqual((await client.next_reply(1))['type'], 'scan_result')


if __name__ == '__main__':
    unittest.main()
