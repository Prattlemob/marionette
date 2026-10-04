"""Gameplay controls (M3.7): respawn, chat/commands, swap hands, identity and activity fields."""
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, RoleError, ServerError, connect
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())


def recording(frames, reply):
    async def handler(ws, request):
        async for raw in ws:
            command = json.loads(raw)
            frames.append(command)
            answer = reply(command)
            if answer is not None:
                await send(ws, **answer)
    return handler


class GameplayDecodeTests(unittest.TestCase):
    def test_identity_and_activity_fields_decode(self):
        observation = next(m for m in FIXTURE['messages']
                           if m['type'] == 'observation' and 'vehicle' in m.get('player', {}))
        player = decode(json.dumps(observation))['player']
        self.assertEqual(player['name'], 'Dev')
        self.assertEqual(player['usingItem'], {'hand': 'off_hand', 'item': 'minecraft:shield', 'ticks': 12})
        self.assertEqual(player['vehicle']['type'], 'minecraft:oak_boat')
        self.assertTrue(player['blocking'])
        for field, bad in (('vehicle', {'id': 'x', 'type': 'boat'}), ('usingItem', 3), ('uuid', 5)):
            broken = json.loads(json.dumps(observation))
            broken['player'][field] = bad
            with self.subTest(field=field), self.assertRaises(InvalidMessage):
                decode(json.dumps(broken))

    def test_identity_on_events_and_older_events_without_it(self):
        chat = next(e for e in FIXTURE['events'] if e['event'] == 'chat' and e.get('senderName'))
        self.assertEqual(decode(json.dumps(chat))['senderName'], 'Dev')
        damage = next(e for e in FIXTURE['events'] if e['event'] == 'damage'
                      and (e['source'] or {}).get('attackerPlayer'))
        self.assertEqual(decode(json.dumps(damage))['source']['attackerPlayer']['name'], 'Alex')
        older = {k: v for k, v in chat.items() if k != 'senderName'}
        self.assertEqual(decode(json.dumps(older)), older, 'events from earlier mods still decode')

    def test_refusals_carry_reason_limits_and_retry(self):
        limited = next(m for m in FIXTURE['actionResults'] if m.get('reason') == 'rate_limited')
        decoded = decode(json.dumps(limited))
        self.assertEqual(decoded['limits'], {'maxMessages': 5, 'windowSeconds': 10, 'maxLength': 256})
        self.assertEqual(decoded['retryAfterMs'], 4210)


class GameplaySessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_chat_text_and_command_are_distinct_requests(self):
        frames = []
        reply = lambda c: dict(type='action_result', id=c['id'], action='command' if 'command' in c else 'chat')
        async with endpoint(recording(frames, reply)) as uri:
            async with connect(uri) as client:
                self.assertEqual((await client.chat('hello'))['action'], 'chat')
                self.assertEqual((await client.chat(command='time set day'))['action'], 'command')
        self.assertEqual(frames, [{'type': 'chat', 'id': 'chat-1', 'text': 'hello'},
                                  {'type': 'chat', 'id': 'chat-2', 'command': 'time set day'}])

    async def test_chat_refusal_raises_with_reason_and_session_continues(self):
        def reply(command):
            if 'command' in command:
                return dict(type='error', code='chat_refused', reason='commands_disabled', message='off',
                            id=command['id'], limits=dict(maxMessages=5, windowSeconds=10, maxLength=256),
                            input='{}')
            return dict(type='action_result', id=command['id'], action='chat')
        async with endpoint(recording([], reply)) as uri:
            async with connect(uri) as client:
                with self.assertRaises(ServerError) as caught:
                    await client.chat(command='kill')
                self.assertEqual((caught.exception.code, caught.exception.reason),
                                 ('chat_refused', 'commands_disabled'))
                self.assertEqual(caught.exception.error['limits']['maxLength'], 256)
                self.assertEqual((await client.chat('still here'))['action'], 'chat')

    async def test_respawn_result_and_refusal(self):
        answers = iter([dict(type='error', code='respawn_refused', reason='not_dead', message='alive', input='{}'),
                        dict(type='action_result', action='respawn')])
        frames = []
        async with endpoint(recording(frames, lambda c: {**next(answers), 'id': c['id']})) as uri:
            async with connect(uri) as client:
                with self.assertRaises(ServerError) as caught:
                    await client.respawn()
                self.assertEqual(caught.exception.reason, 'not_dead')
                self.assertEqual((await client.respawn())['action'], 'respawn')
        self.assertEqual(frames, [{'type': 'respawn', 'id': 'respawn-1'}, {'type': 'respawn', 'id': 'respawn-2'}])

    async def test_mismatched_action_closes_the_session(self):
        reply = lambda c: dict(type='action_result', id=c['id'], action='respawn')
        async with endpoint(recording([], reply)) as uri:
            async with connect(uri) as client:
                with self.assertRaises(InvalidMessage):
                    await client.chat('hi')
                self.assertIsNotNone(client._outcome)

    async def test_swap_hands_tap(self):
        frames = []
        async with endpoint(recording(frames, lambda c: None)) as uri:
            async with connect(uri) as client:
                await client.input(tap=['swap_hands'])
                await client.release()
        self.assertEqual(frames[0], {'type': 'input', 'tap': ['swap_hands']})

    async def test_local_validation_and_gating(self):
        caps = {k: v for k, v in HELLO['capabilities'].items() if k not in ('chat', 'respawn', 'swapHands')}
        async with endpoint(idle, {**HELLO, 'capabilities': caps}) as uri:
            async with connect(uri) as client:
                for call in (client.chat('x'), client.respawn(), client.input(tap=['swap_hands'])):
                    with self.assertRaises(CapabilityError):
                        await call
        async with endpoint(idle) as uri:
            async with connect(uri) as client:
                for kwargs in ({}, {'text': 'a', 'command': 'b'}, {'text': 5}):
                    with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                        await client.chat(**kwargs)
            async with connect(uri, role='observer') as observer:
                with self.assertRaises(RoleError):
                    await observer.chat('hi')
                with self.assertRaises(RoleError):
                    await observer.respawn()

    async def test_unrequested_action_result_is_a_reliable_reply(self):
        async def handler(ws, request):
            await send(ws, type='action_result', id='other', action='chat')
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                self.assertEqual((await client.next_reply(1))['type'], 'action_result')


if __name__ == '__main__':
    unittest.main()
