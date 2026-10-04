"""Inventory observation (inventoryState): typing, capability gating and compatibility."""
import json
from pathlib import Path
import unittest

from marionette_mc import CapabilityError, ServerError, connect
from marionette_mc.messages import InvalidMessage, decode

from test_client import HELLO, endpoint, idle, send

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())


def fixture_observation():
    return next(m for m in FIXTURE['messages'] if m['type'] == 'observation' and 'inventory' in m)


class InventoryDecodeTests(unittest.TestCase):
    def test_inventory_section_decodes_with_extras(self):
        frame = decode(json.dumps(fixture_observation()))
        inventory = frame['inventory']
        self.assertEqual(len(inventory['hotbar']), 9)
        self.assertEqual(len(inventory['main']), 27)
        self.assertEqual(inventory['mainHand'], inventory['hotbar'][inventory['selected']])
        held = inventory['mainHand']
        self.assertEqual(held['maxDamage'] - held['damage'], 1549)
        self.assertEqual(held['enchantments'][0]['id'], 'minecraft:sharpness')
        self.assertEqual(inventory['menu']['operations'], ['move', 'swap', 'drop', 'close'])
        self.assertIsNone(inventory['menu']['refusal'])

    def test_unsupported_menu_descriptor_and_future_reasons(self):
        result = next(m for m in FIXTURE['messages'] if m.get('id') == 'furnace')
        self.assertEqual(decode(json.dumps(result))['menu']['refusal'], 'unsupported_menu')
        # Reason codes are open strings: a future reason must not end the session.
        future = json.loads(json.dumps(result))
        future['menu']['refusal'] = 'some_future_reason'
        future['menu']['slots'][0]['refused'] = 'future_slot_reason'
        future['menu']['slots'][0]['futureDetail'] = {'x': 1}
        self.assertEqual(decode(json.dumps(future))['menu']['slots'][0]['futureDetail'], {'x': 1})

    def test_storage_support_and_future_support_values(self):
        furnace = next(m for m in FIXTURE['messages'] if m.get('id') == 'furnace')['menu']
        self.assertEqual(furnace['support'], {'scope': None, 'reasons': ['menu_data', 'slot_behavior']})
        self.assertEqual(fixture_observation()['inventory']['menu']['support']['scope'], 'storage')
        future = json.loads(json.dumps(furnace))
        future['support'] = {'scope': None, 'reasons': ['future_rule'], 'detail': 1}
        frame = {'type': 'inventory_result', 'op': 'inspect', 'menu': future}
        self.assertEqual(decode(json.dumps(frame))['menu']['support']['reasons'], ['future_rule'])
        for bad in ({'scope': 1, 'reasons': []}, {'scope': 'storage'}, {'scope': None, 'reasons': 'x'}):
            frame['menu']['support'] = bad
            with self.subTest(support=bad), self.assertRaises(InvalidMessage):
                decode(json.dumps(frame))

    def test_rejection_reasons_decode_and_are_optional(self):
        errors = [m for m in FIXTURE['messages'] if m['type'] == 'error']
        self.assertEqual([e.get('reason') for e in errors],
                         ['released', 'destination_rejects', 'unsupported_menu', None])
        with self.assertRaises(InvalidMessage):
            decode(json.dumps({**errors[1], 'reason': 3}))

    def test_minimal_descriptors_from_older_mods_still_decode(self):
        old = {'type': 'inventory_result', 'op': 'inspect', 'menu': {
            'type': 'minecraft:inventory', 'containerId': 0, 'stateId': 1,
            'slots': [{'slot': 9, 'alias': 'main.0', 'item': 'minecraft:dirt', 'count': 1}],
            'carried': {'item': 'minecraft:air', 'count': 0}}}
        self.assertEqual(decode(json.dumps(old)), old)

    def test_malformed_inventory_is_rejected(self):
        for mutate in (lambda i: i.update(hotbar={}), lambda i: i.pop('armor'),
                       lambda i: i['mainHand'].update(damage='x'),
                       lambda i: i['mainHand'].update(enchantments=[{'id': 'a'}]),
                       lambda i: i.update(menu=[])):
            frame = json.loads(json.dumps(fixture_observation()))
            mutate(frame['inventory'])
            with self.subTest(frame=frame['inventory'].keys()), self.assertRaises(InvalidMessage):
                decode(json.dumps(frame))


class InventoryCapabilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_server_error_exposes_rejection_reason(self):
        async def handler(ws, request):
            for code, reason in (('inventory_impossible', 'destination_full'), ('inventory_busy', None)):
                command = json.loads(await ws.recv())
                await send(ws, type='error', code=code, message='no', id=command['id'],
                           **({'reason': reason} if reason else {}))
            await ws.wait_closed()
        async with endpoint(handler) as uri:
            async with connect(uri) as client:
                with self.assertRaises(ServerError) as full:
                    await client.inventory('inspect')
                self.assertEqual((full.exception.code, full.exception.reason), ('inventory_impossible', 'destination_full'))
                self.assertIn('(destination_full)', str(full.exception))
                with self.assertRaises(ServerError) as busy:
                    await client.inventory('inspect')
                self.assertIsNone(busy.exception.reason)


    async def test_inventory_section_requires_inventory_state(self):
        old = {**HELLO, 'capabilities': {k: v for k, v in HELLO['capabilities'].items() if k != 'inventoryState'}}
        async with endpoint(idle, old) as uri:
            with self.assertRaises(CapabilityError):
                async with connect(uri, sections=['player', 'inventory']):
                    self.fail('handshake should fail')
            async with connect(uri, sections=['player']) as client:
                with self.assertRaises(CapabilityError):
                    await client.configure(sections=['inventory'])

    async def test_hello_and_configure_send_inventory_mask(self):
        received = []
        async def handler(ws, request):
            received.append(request)
            async for raw in ws:
                received.append(json.loads(raw))
        async with endpoint(handler) as uri:
            async with connect(uri, role='observer', sections=['inventory']) as client:
                await client.configure(sections=['player', 'inventory'])
        self.assertEqual(received[0]['sections'], ['inventory'])
        self.assertEqual(received[1], {'type': 'configure', 'sections': ['player', 'inventory']})


if __name__ == '__main__':
    unittest.main()
