"""Human precedence (M5.1, ``humanPrecedence``): control events and ``human_paused`` refusals."""
import json
from pathlib import Path
import typing
import unittest

from marionette_mc import ServerError, connect
from marionette_mc.messages import ControlCause, ControlMode, HumanInputKind, InvalidMessage, decode

from test_client import HELLO, endpoint, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())
CONTROL = [e for e in FIXTURE['events'] if e['event'] == 'control']


class ControlEventTests(unittest.TestCase):
    def test_fixture_covers_every_mode_and_cause(self):
        self.assertEqual({e['mode'] for e in CONTROL}, set(typing.get_args(ControlMode)))
        self.assertEqual({e['cause'] for e in CONTROL}, set(typing.get_args(ControlCause)))
        inputs = {name for e in CONTROL for name in e['inputs']}
        self.assertLessEqual(inputs, set(typing.get_args(HumanInputKind)))

    def test_control_events_decode_and_are_validated(self):
        for event in CONTROL:
            with self.subTest(cause=event['cause']):
                self.assertEqual(decode(json.dumps(event)), event)
        paused = next(e for e in CONTROL if e['paused'])
        for field, bad in (('paused', 'yes'), ('inputs', 'movement'), ('mode', None)):
            with self.subTest(field=field), self.assertRaises(InvalidMessage):
                decode(json.dumps(dict(paused, **{field: bad})))

    def test_only_pauses_carry_inputs(self):
        for event in CONTROL:
            self.assertEqual(bool(event['inputs']), event['cause'] == 'human_input')
            self.assertFalse(event['paused'] and event['mode'] != 'human_priority')

    def test_paused_refusals_decode(self):
        refusals = [m for m in FIXTURE['actionResults'] + FIXTURE['messages'] if m.get('reason') == 'human_paused']
        self.assertEqual(sorted(m['code'] for m in refusals),
                         ['chat_refused', 'inventory_unavailable', 'respawn_refused'])
        for refusal in refusals:
            self.assertEqual(decode(json.dumps(refusal)), refusal)


class PausedSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_paused_requests_raise_with_reason_and_the_session_continues(self):
        async def handler(ws, request):
            async for raw in ws:
                command = json.loads(raw)
                if command['type'] in ('chat', 'respawn'):
                    code = 'chat_refused' if command['type'] == 'chat' else 'respawn_refused'
                    extra = dict(limits=dict(maxMessages=5, windowSeconds=10, maxLength=256)) \
                        if command['type'] == 'chat' else {}
                    await send(ws, type='error', code=code, reason='human_paused', id=command['id'],
                               message='human input has paused agent control', input=raw, **extra)
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                client.require('humanPrecedence')
                for call in (lambda: client.respawn(), lambda: client.chat('hi')):
                    with self.assertRaises(ServerError) as caught:
                        await call()
                    self.assertEqual(caught.exception.reason, 'human_paused')

    async def test_control_events_arrive_on_the_event_stream(self):
        async def handler(ws, request):
            for event in CONTROL[:3]:
                await send(ws, **dict(event, seq=CONTROL.index(event) + 1))
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, events=True) as client:
                kinds = [(await client.next_event(2))['cause'] for _ in range(3)]
        self.assertEqual(kinds, [e['cause'] for e in CONTROL[:3]])
        self.assertTrue(HELLO['capabilities']['humanPrecedence'])


if __name__ == '__main__':
    unittest.main()
