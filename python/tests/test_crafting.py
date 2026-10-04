"""Crafting and processing menus (crafting): descriptors, craft/count requests, gating."""
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, ServerError, connect, menu_ref
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())


def fixture(message_id):
    return next(m for m in FIXTURE['messages'] if m.get('id') == message_id)


class CraftingDecodeTests(unittest.TestCase):
    def test_crafting_descriptor(self):
        menu = decode(json.dumps(fixture('crafting')))['menu']
        crafting = menu['crafting']
        self.assertEqual(menu['support']['scope'], 'crafting')
        self.assertIn('craft', menu['operations'])
        self.assertEqual(len(crafting['grid']), crafting['width'] * crafting['height'])
        result = next(s for s in menu['slots'] if s['slot'] == crafting['result'])
        self.assertEqual((result['item'], result.get('refused')), ('minecraft:chest', 'result'))

    def test_processing_descriptor(self):
        menu = decode(json.dumps(fixture('smelting')))['menu']
        processing = menu['processing']
        self.assertEqual(menu['support']['scope'], 'processing')
        self.assertEqual((processing['input'], processing['fuel'], processing['result']), (0, 1, 2))
        self.assertTrue(processing['lit'])
        self.assertLess(processing['cookTime'], processing['cookDuration'])
        empty = json.loads(json.dumps(fixture('smelting')))
        empty['menu']['processing']['smeltable'] = None
        empty['menu']['processing']['kind'] = 'future_kind'
        self.assertIsNone(decode(json.dumps(empty))['menu']['processing']['smeltable'])
        for field, bad in (('cookTime', 1.5), ('lit', 1), ('grid', None)):
            broken = json.loads(json.dumps(fixture('smelting')))
            broken['menu']['processing'][field] = bad
            if field == 'grid':
                broken['menu']['crafting'] = {'result': 0, 'grid': 'x', 'width': 2, 'height': 2}
            with self.subTest(field=field), self.assertRaises(InvalidMessage):
                decode(json.dumps(broken))

    def test_craft_result_decodes(self):
        self.assertEqual(decode(json.dumps(fixture('craft')))['op'], 'craft')


class CraftingRequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_craft_and_counted_move_requests(self):
        received = []
        async def handler(ws, request):
            async for raw in ws:
                command = json.loads(raw)
                received.append(command)
                if command.get('op') == 'craft' and command.get('count') == 9:
                    await send(ws, type='error', code='inventory_impossible', message='no', id=command['id'],
                               reason='missing_ingredients')
                else:
                    await send(ws, type='inventory_result', id=command['id'], op=command['op'], menu=None)
        menu = menu_ref(fixture('crafting')['menu'])
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                await client.inventory('move', menu=menu, source='main.0', destination=1, count=1)
                await client.inventory('craft', menu=menu, destination='hotbar.0', count=2, animated=True)
                await client.inventory('craft', menu=menu, destination='hotbar.0')
                with self.assertRaises(ServerError) as error:
                    await client.inventory('craft', menu=menu, destination='hotbar.0', count=9)
                self.assertEqual(error.exception.reason, 'missing_ingredients')
                for bad in (dict(op='craft', menu=menu), dict(op='move', menu=menu, source=0, destination=1, count=0),
                            dict(op='drop', menu=menu, source=0, count=1)):
                    with self.subTest(bad=bad), self.assertRaises(ValueError):
                        await client.inventory(**bad)
        self.assertEqual([(c['op'], c.get('count'), c.get('to')) for c in received],
                         [('move', 1, 1), ('craft', 2, 'hotbar.0'), ('craft', None, 'hotbar.0'),
                          ('craft', 9, 'hotbar.0')])
        self.assertNotIn('from', received[1])

    async def test_crafting_requires_capability(self):
        old = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != 'crafting'}}
        menu = menu_ref(fixture('crafting')['menu'])
        async with endpoint(idle, old) as uri:
            async with connect(uri) as client:
                with self.assertRaises(CapabilityError):
                    await client.inventory('craft', menu=menu, destination='hotbar.0')
                with self.assertRaises(CapabilityError):
                    await client.inventory('move', menu=menu, source=0, destination=1, count=1)


if __name__ == '__main__':
    unittest.main()
