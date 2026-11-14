"""Hermetic protocol parsing checks for the isolated public installer."""
import importlib.util
from pathlib import Path
import unittest
import tempfile

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


class IsolatedEnvironmentTests(unittest.TestCase):
    def test_public_schema_read_grant_preserves_bytes_and_private_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            schema, private = Path(directory) / 'schema.sql', Path(directory) / '.env'
            schema.write_bytes(b'CREATE TABLE example (id bigint);\n')
            private.write_text('PRIVATE_TEST_FIXTURE=unchanged\n')
            schema.chmod(0o600); private.chmod(0o600)
            receipt = installer.prepare_public_schema(schema)
            self.assertEqual(schema.stat().st_mode & 0o777, 0o644)
            self.assertEqual(private.stat().st_mode & 0o777, 0o600)
            self.assertEqual(schema.read_bytes(), b'CREATE TABLE example (id bigint);\n')
            self.assertFalse(receipt['sourceBytesModified'])
            self.assertEqual(receipt['modeBefore'], '0o600')

    def test_schema_symlink_is_rejected_without_changing_target(self):
        with tempfile.TemporaryDirectory() as directory:
            target, link = Path(directory) / 'target', Path(directory) / 'schema.sql'
            target.write_text('private'); target.chmod(0o600); link.symlink_to(target)
            with self.assertRaises(RuntimeError): installer.prepare_public_schema(link)
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_actual_resource_clamp_is_recorded_instead_of_requested_limit(self):
        receipt = installer.measured_builder_limits({'Memory': 4294967296, 'MemorySwap': 4294967296})
        self.assertEqual(receipt['requestedMemoryBytes'], 8589934592)
        self.assertEqual(receipt['actualMemoryBytes'], 4294967296)
        for host in [{'Memory': 0, 'MemorySwap': 0}, {'Memory': 4294967296, 'MemorySwap': -1}, {'Memory': '4g', 'MemorySwap': 4294967296}]:
            with self.subTest(host=host), self.assertRaises(RuntimeError): installer.measured_builder_limits(host)


if __name__ == '__main__':
    unittest.main()
