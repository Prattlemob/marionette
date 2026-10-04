"""Diagnostics (M5.2, ``status``): the status query, its reply and the hello agent name."""
import asyncio
import json
from pathlib import Path
import typing
import unittest

from marionette_mc import CapabilityError, RoleError, connect
from marionette_mc.messages import HeldControl, InvalidMessage, StatusState, decode

from test_client import HELLO, endpoint, idle, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())
STATUS = FIXTURE['statusResults']


class StatusMessageTests(unittest.TestCase):
    def test_fixtures_cover_every_state_and_decode_unchanged(self):
        self.assertEqual({r['state'] for r in STATUS}, set(typing.get_args(StatusState)))
        self.assertLessEqual({c for r in STATUS for c in r['held']}, set(typing.get_args(HeldControl)))
        for result in STATUS:
            with self.subTest(state=result['state']):
                self.assertEqual(decode(json.dumps(result)), result)

    def test_only_a_connected_state_names_a_controller(self):
        for result in STATUS:
            self.assertEqual(result['controller'] is not None, result['state'] == 'connected')
            if result['controller'] is not None and result['session']['role'] == 'controller':
                for key, value in result['controller'].items():
                    self.assertEqual(result['session'][key], value, key)

    def test_malformed_status_results_are_rejected(self):
        connected = next(r for r in STATUS if r['state'] == 'connected')
        for field, bad in (('held', 'forward'), ('paused', 'no'), ('session', None), ('tick', 'x'),
                           ('controller', {'agent': None})):
            with self.subTest(field=field), self.assertRaises(InvalidMessage):
                decode(json.dumps(dict(connected, **{field: bad})))


class StatusSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_status_round_trip_for_both_roles(self):
        requests = []
        async def handler(ws, request):
            async for raw in ws:
                command = json.loads(raw)
                requests.append((request['role'], command))
                reply = dict(STATUS[0] if request['role'] == 'controller' else STATUS[1], id=command['id'])
                await send(ws, **reply)
        async with endpoint(handler) as uri:
            for role in ('controller', 'observer'):
                async with connect(uri, role=role) as client:
                    result = await client.status(timeout=2)
                    self.assertEqual(result['type'], 'status_result')
                    self.assertEqual(result['session']['role'], role)
        self.assertEqual(requests, [('controller', {'type': 'status', 'id': 'status-1'}),
                                    ('observer', {'type': 'status', 'id': 'status-1'})])

    async def test_status_requires_the_capability_and_observers_still_cannot_actuate(self):
        hello = dict(HELLO, capabilities={k: v for k, v in HELLO['capabilities'].items() if k != 'status'})
        async with endpoint(idle, hello) as uri:
            async with connect(uri) as client:
                with self.assertRaises(CapabilityError):
                    await client.status()
        async with endpoint(idle) as uri:
            async with connect(uri, role='observer') as client:
                with self.assertRaises(RoleError):
                    await client.input(forward=True)

    async def test_a_status_result_never_resolves_another_request(self):
        async def handler(ws, request):
            await send(ws, **dict(STATUS[2], id='unrelated'))
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                reply = await client.next_reply(2)
                self.assertEqual((reply['type'], reply['id']), ('status_result', 'unrelated'))

    async def test_agent_name_is_sent_in_hello_and_validated_locally(self):
        hellos = []
        async def handler(ws, request):
            hellos.append(request)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, agent='walker'):
                pass
            async with connect(uri):
                pass
        self.assertEqual(hellos[0]['agent'], 'walker')
        self.assertNotIn('agent', hellos[1])
        for bad in ('', 'x' * 65, 'a\nb', '\u00a7cred', 'del\x7f', 7):
            with self.subTest(agent=bad), self.assertRaises(ValueError):
                async with connect('ws://127.0.0.1:9/', agent=bad):  # type: ignore[arg-type]
                    self.fail('validated before connecting')
        async with endpoint(handler) as uri:
            async with connect(uri, agent='x' * 64):
                pass

    async def test_status_after_disconnect_is_refused_locally(self):
        async def handler(ws, request):
            await ws.close()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                await asyncio.wait_for(client.wait_closed(), 2)
                with self.assertRaises(Exception):
                    await client.status()


if __name__ == '__main__':
    unittest.main()
