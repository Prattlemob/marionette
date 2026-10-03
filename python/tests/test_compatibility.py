import json
from pathlib import Path
import unittest
from marionette_mc.messages import (decode, validate, HelloRequest, Input, Look,
                                    Release, Configure, InventoryRequest)


class CompatibilityTests(unittest.TestCase):
    def test_shared_java_messages_and_commands(self):
        fixture = json.loads((Path(__file__).parent / 'fixtures/protocol2.json').read_text())
        for message in fixture['messages']:
            with self.subTest(message=message):
                self.assertEqual(decode(json.dumps(message)), message)
        schemas = dict(hello=HelloRequest, input=Input, look=Look, release=Release,
                       configure=Configure, inventory=InventoryRequest)
        for command in fixture['commands']:
            validate(command['wire'], schemas[command['wire']['type']])

    def test_public_package_is_typed_and_versioned(self):
        import marionette_mc
        import importlib.resources
        self.assertEqual(marionette_mc.__version__, '0.1.0a1')
        self.assertTrue(importlib.resources.files('marionette_mc').joinpath('py.typed').is_file())
