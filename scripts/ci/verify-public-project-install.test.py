"""Hermetic protocol parsing checks for the isolated public installer."""
import importlib.util
from pathlib import Path
import unittest
import tempfile
import hashlib

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
    def test_actual_resource_clamp_is_recorded_instead_of_requested_limit(self):
        receipt = installer.measured_builder_limits({'Memory': 4294967296, 'MemorySwap': 4294967296})
        self.assertEqual(receipt['requestedMemoryBytes'], 8589934592)
        self.assertEqual(receipt['actualMemoryBytes'], 4294967296)
        for host in [{'Memory': 0, 'MemorySwap': 0}, {'Memory': 4294967296, 'MemorySwap': -1}, {'Memory': '4g', 'MemorySwap': 4294967296}]:
            with self.subTest(host=host), self.assertRaises(RuntimeError): installer.measured_builder_limits(host)



class ChromeRuntimeTests(unittest.TestCase):
    def test_existing_chrome_is_measured_and_unsafe_inputs_fail_before_build(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'chrome'
            binary.write_text('#!/bin/sh\necho "Google Chrome 151.0.7922.173"\n')
            binary.chmod(0o700)
            record = installer.measured_chrome_runtime(binary)
            self.assertEqual(record['channel'], 'chrome')
            self.assertEqual(record['version'], 'Google Chrome 151.0.7922.173')
            self.assertEqual(record['sha256'], hashlib.sha256(binary.read_bytes()).hexdigest())
            binary.chmod(0o600)
            with self.assertRaises(RuntimeError): installer.measured_chrome_runtime(binary)
            binary.chmod(0o722)
            with self.assertRaises(RuntimeError): installer.measured_chrome_runtime(binary)
            binary.chmod(0o700)
            binary.write_text('#!/bin/sh\necho "unrecognized tool"\n')
            with self.assertRaises(RuntimeError): installer.measured_chrome_runtime(binary)
            with self.assertRaises(FileNotFoundError): installer.measured_chrome_runtime(binary.with_name('missing'))


if __name__ == '__main__':
    unittest.main()
