"""Nearby entities (entityState): typing, gating, compatibility."""
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, connect
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())


def observations():
    return [m for m in FIXTURE['messages'] if m['type'] == 'observation' and 'entities' in m]


class EntitiesDecodeTests(unittest.TestCase):
    def test_sections_decode_nearest_first_within_caps(self):
        sections = [decode(json.dumps(m))['entities'] for m in observations()]
        self.assertEqual({s['truncated'] for s in sections}, {False, True})
        for section in sections:
            nearby = section['nearby']
            self.assertEqual(section['truncated'], section['total'] > section['maxCount'])
            self.assertEqual(len(nearby), min(section['total'], section['maxCount']))
            self.assertEqual([e['distance'] for e in nearby], sorted(e['distance'] for e in nearby))
            self.assertTrue(all(e['distance'] <= section['radius'] for e in nearby))

    def test_entry_fields_follow_the_entity_kind(self):
        entries = {e['type']: e for m in observations() for e in decode(json.dumps(m))['entities']['nearby']}
        self.assertEqual(entries['minecraft:zombie']['hostility'], 'hostile')
        self.assertEqual(entries['minecraft:cow']['hostility'], 'passive')
        self.assertEqual(entries['minecraft:item']['item'], {'item': 'minecraft:diamond', 'count': 3})
        self.assertNotIn('health', entries['minecraft:item'])
        self.assertEqual(entries['minecraft:player']['name'], 'Steve')
        self.assertEqual({e['targetingMe'] for e in entries.values() if 'targetingMe' in e}, {'yes', 'no', 'unknown'})

    def test_future_values_and_fields_are_kept(self):
        frame = json.loads(json.dumps(observations()[0]))
        entry = frame['entities']['nearby'][0]
        entry.update(hostility='mythic', targetingMe='probably', aura=[1])
        decoded = decode(json.dumps(frame))['entities']['nearby'][0]
        self.assertEqual((decoded['hostility'], decoded['targetingMe'], decoded['aura']), ('mythic', 'probably', [1]))

    def test_malformed_sections_are_rejected(self):
        for mutate in (lambda s: s.pop('truncated'), lambda s: s['nearby'][0].pop('velocity'),
                       lambda s: s['nearby'][0].update(id='212'), lambda s: s.update(radius=32.5)):
            frame = json.loads(json.dumps(observations()[0]))
            mutate(frame['entities'])
            with self.assertRaises(InvalidMessage):
                decode(json.dumps(frame))


class EntitiesSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_section_requires_its_capability(self):
        old = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != 'entityState'}}
        async with endpoint(idle, old) as uri:
            with self.assertRaises(CapabilityError):
                async with connect(uri, sections=['player', 'entities']):
                    self.fail('handshake should fail')
            async with connect(uri, sections=['player']) as client:
                with self.assertRaises(CapabilityError):
                    await client.configure(sections=['entities'])

    async def test_hello_and_configure_send_entity_masks(self):
        received = []
        async def handler(ws, request):
            received.append(request)
            async for raw in ws:
                received.append(json.loads(raw))
        async with endpoint(handler) as uri:
            async with connect(uri, role='observer', sections=['entities']) as client:
                await client.configure(sections=['player', 'entities'])
        self.assertEqual(received[0]['sections'], ['entities'])
        self.assertEqual(received[1], {'type': 'configure', 'sections': ['player', 'entities']})


if __name__ == '__main__':
    unittest.main()
