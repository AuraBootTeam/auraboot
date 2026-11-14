"""Hermetic protocol parsing checks for the isolated public installer."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('public_install', Path(__file__).with_name('verify-public-project-install.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class ComposeStateTests(unittest.TestCase):
    def test_compose_array_keeps_service_and_health(self):
        rows = installer.compose_rows('[{"Service":"backend","Health":"healthy"},{"Service":"web-admin","Health":""}]')
        self.assertEqual([row['Service'] for row in rows], ['backend', 'web-admin'])
        self.assertEqual(rows[0]['Health'], 'healthy')

    def test_compose_ndjson_keeps_each_container(self):
        rows = installer.compose_rows('{"Service":"backend","Health":"starting"}\n{"Service":"postgres","Health":"healthy"}\n')
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]['Health'], 'starting')

    def test_empty_project_cannot_become_healthy(self):
        self.assertEqual(installer.compose_rows(''), [])
        self.assertEqual(installer.compose_rows('[]'), [])

    def test_invalid_container_entries_fail_closed(self):
        for value in ['[null]', '[1]', '[[]]', '"healthy"', '{bad json}']:
            with self.subTest(value=value), self.assertRaises((RuntimeError, ValueError)):
                installer.compose_rows(value)


if __name__ == '__main__':
    unittest.main()
