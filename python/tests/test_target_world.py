"""Crosshair target and world context (targetState/worldState): typing, gating, compatibility."""
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, connect
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())


def observations(section):
    return [m for m in FIXTURE['messages'] if m['type'] == 'observation' and section in m]


class TargetWorldDecodeTests(unittest.TestCase):
    def test_every_target_kind_decodes(self):
        targets = {m['target']['kind']: decode(json.dumps(m))['target'] for m in observations('target')}
        self.assertEqual(set(targets), {'block', 'entity', 'none'})
        self.assertEqual(targets['block']['face'], 'north')
        self.assertEqual(targets['block']['pos'], {'x': 3, 'y': -60, 'z': 1})
        self.assertEqual(targets['entity']['entity'], 'minecraft:pig')
        self.assertLessEqual(targets['entity']['distance'], targets['entity']['reach']['entity'])
        self.assertEqual(set(targets['none']), {'kind', 'reach'})

    def test_world_section_decodes(self):
        worlds = [decode(json.dumps(m))['world'] for m in observations('world')]
        for world in worlds:
            self.assertEqual(world['timeOfDay'], world['dayTime'] % 24000)
            self.assertEqual(world['day'], world['dayTime'] // 24000)
            self.assertEqual(world['light']['combined'], max(world['light']['sky'], world['light']['block']))
        self.assertEqual({w['weather'] for w in worlds}, {'clear', 'rain'})

    def test_future_kinds_and_fields_are_kept(self):
        frame = json.loads(json.dumps(observations('target')[0]))
        frame['target'] = {'kind': 'future_kind', 'reach': {'block': 4.5, 'entity': 3.0}, 'extra': [1]}
        frame['world'] = dict(observations('world')[0]['world'], weather='sandstorm', biome='x')
        decoded = decode(json.dumps(frame))
        self.assertEqual(decoded['target']['extra'], [1])
        self.assertEqual(decoded['world']['weather'], 'sandstorm')

    def test_malformed_sections_are_rejected(self):
        frame = json.loads(json.dumps(observations('target')[0]))
        del frame['target']['reach']
        with self.assertRaises(InvalidMessage):
            decode(json.dumps(frame))
        frame = json.loads(json.dumps(observations('world')[0]))
        frame['world']['light']['sky'] = 'bright'
        with self.assertRaises(InvalidMessage):
            decode(json.dumps(frame))


class TargetWorldSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_sections_require_their_capabilities(self):
        for section, capability in (('target', 'targetState'), ('world', 'worldState')):
            old = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != capability}}
            async with endpoint(idle, old) as uri:
                with self.assertRaises(CapabilityError):
                    async with connect(uri, sections=['player', section]):
                        self.fail('handshake should fail')
                async with connect(uri, sections=['player']) as client:
                    with self.assertRaises(CapabilityError):
                        await client.configure(sections=[section])

    async def test_hello_and_configure_send_target_and_world_masks(self):
        received = []
        async def handler(ws, request):
            received.append(request)
            async for raw in ws:
                received.append(json.loads(raw))
        async with endpoint(handler) as uri:
            async with connect(uri, role='observer', sections=['target', 'world']) as client:
                await client.configure(sections=['player', 'target'])
        self.assertEqual(received[0]['sections'], ['target', 'world'])
        self.assertEqual(received[1], {'type': 'configure', 'sections': ['player', 'target']})


if __name__ == '__main__':
    unittest.main()
