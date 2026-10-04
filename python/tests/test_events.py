"""One-shot event stream (``events`` capability): offline real-socket tests."""
import asyncio
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, CapacityError, Disconnected, connect
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle, send

FIXTURES = Path(__file__).parent / 'fixtures'


def shared_events():
    return json.loads((FIXTURES / 'protocol2.json').read_text())['events']


class EventTests(unittest.IsolatedAsyncioTestCase):
    def test_every_fixture_event_decodes_unchanged(self):
        for event in shared_events():
            with self.subTest(event=event['event']):
                self.assertEqual(decode(json.dumps(event)), event)

    def test_unknown_kinds_decode_and_malformed_events_do_not(self):
        future = dict(shared_events()[0], event='future_kind', extra=1)
        self.assertEqual(decode(json.dumps(future)), future)
        for bad in [dict(future, seq='1'), dict(future, basis='guess'), dict(future, id='x'),
                    {k: v for k, v in future.items() if k != 'worldSession'},
                    dict(shared_events()[0], amount='3')]:
            with self.subTest(bad=bad), self.assertRaises(InvalidMessage):
                decode(json.dumps(bad))

    async def test_subscription_is_opt_in_and_capability_checked(self):
        requests = []
        async def handler(ws, request):
            requests.append(request)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri):
                pass
            async with connect(uri, events=True) as client:
                self.assertTrue(client.hello['capabilities']['events'])
        self.assertNotIn('events', requests[0])
        self.assertIs(requests[1]['events'], True)
        legacy = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != 'events'}}
        async with endpoint(idle, legacy) as uri:
            with self.assertRaises(CapabilityError):
                async with connect(uri, events=True):
                    self.fail('an old mod cannot deliver events')
            async with connect(uri) as client:
                with self.assertRaises(CapabilityError):
                    await client.configure(events=True)

    async def test_events_never_answer_a_pending_request(self):
        events = shared_events()
        async def handler(ws, request):
            command = json.loads(await ws.recv())
            for event in events[:3]:
                await ws.send(json.dumps(event))
            await send(ws, type='inventory_result', id=command['id'], op='inspect', menu=None)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, events=True) as client:
                result = await client.inventory('inspect', timeout=2)
                self.assertEqual(result['type'], 'inventory_result')
                self.assertEqual([await client.next_event(1) for _ in range(3)], events[:3])

    async def test_an_event_echoing_a_request_id_is_rejected_not_matched(self):
        events = shared_events()
        async def handler(ws, request):
            command = json.loads(await ws.recv())
            await ws.send(json.dumps(events[0]))
            await ws.send(json.dumps(dict(events[1], id=command['id'])))  # non-conforming peer
            await send(ws, type='inventory_result', id=command['id'], op='inspect', menu=None)
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri, events=True) as client:
                with self.assertRaises(Disconnected) as caught:
                    await client.inventory('inspect', timeout=2)
                self.assertIsInstance(caught.exception.outcome.cause, InvalidMessage)
                self.assertEqual(await client.next_event(1), events[0])
                with self.assertRaises(Disconnected):
                    await client.next_event(1)

    async def test_ordered_stream_survives_closure_and_unknown_types_are_ignored(self):
        events = shared_events()
        async def handler(ws, request):
            await send(ws, type='future_message', anything=True)
            for event in events:
                await ws.send(json.dumps(event))
            await ws.close(1013, 'event overflow')
        async with endpoint(handler) as uri:
            async with connect(uri, events=True) as client:
                outcome = await client.wait_closed()
                self.assertEqual((outcome.code, outcome.reason), (1013, 'event overflow'))
                self.assertEqual(client.unknown_messages, 1)
                self.assertEqual([await client.next_event(0.1) for _ in events], events)
                self.assertEqual(client.last_event_seq, len(events))
                with self.assertRaises(Disconnected):
                    await client.next_event(0.1)

    async def test_sequence_gap_and_local_overflow_end_the_session(self):
        events = shared_events()
        for frames, limit, cause in [([events[0], events[2]], 64, InvalidMessage),
                                     (events[:3], 2, CapacityError)]:
            async def handler(ws, request, frames=frames):
                for event in frames:
                    await ws.send(json.dumps(event))
                await ws.wait_closed()
            with self.subTest(cause=cause):
                async with endpoint(handler) as uri:
                    async with connect(uri, events=True, event_limit=limit) as client:
                        outcome = await asyncio.wait_for(client.wait_closed(), 2)
                        self.assertIsInstance(outcome.cause, cause)


class RecordedWireTests(unittest.IsolatedAsyncioTestCase):
    """Replays frames recorded from a rendered client (fixtures/events-wire.json)."""

    async def test_recorded_events_never_answer_interleaved_requests(self):
        frames = json.loads((FIXTURES / 'events-wire.json').read_text())['frames']
        recorded = [json.loads(raw) for raw in frames]
        hello, rest = frames[0], frames[1:]
        requests = []

        async def session(ws):
            request = json.loads(await ws.recv())
            self.assertIs(request['events'], True)
            await ws.send(hello)
            for raw in rest:
                message = json.loads(raw)
                if message['type'] == 'inventory_result':
                    # Release each recorded reply only after its request arrives.
                    requests.append(json.loads(await ws.recv()))
                    self.assertEqual(requests[-1]['id'], message['id'])
                await ws.send(raw)
            await ws.wait_closed()

        from websockets.asyncio.server import serve
        async with serve(session, '127.0.0.1', 0) as server:
            uri = f'ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/'
            async with connect(uri, events=True) as client:
                results = [await client.inventory('inspect', timeout=5) for _ in range(3)]
                expected_events = [m for m in recorded if m['type'] == 'event']
                received = [await client.next_event(2) for _ in expected_events]
        self.assertEqual(results, [m for m in recorded if m['type'] == 'inventory_result'])
        self.assertEqual(received, expected_events)
        self.assertEqual([e['seq'] for e in received], list(range(1, len(received) + 1)))
        self.assertTrue(all('id' not in e for e in received))
        # On the recorded wire, events arrived between each request and its result.
        positions = {m.get('id', m.get('seq')): i for i, m in enumerate(recorded)}
        self.assertLess(positions[16], positions['inventory-1'])
        self.assertLess(positions['inventory-1'], positions[21])


if __name__ == '__main__':
    unittest.main()
