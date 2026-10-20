"""Hermetic signing-material preparation checks; container startup is qualified separately."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('quickstart_env', Path(__file__).with_name('prepare-quickstart-env.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class QuickstartEnvTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        os.environ.pop(module.KEY, None)
        os.environ.pop('FIELD_ENCRYPTION_KEY', None)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / '.env'

    def test_missing_file_gets_persistent_private_random_material(self):
        key, changed = module.prepare(self.path)
        self.assertTrue(changed)
        self.assertRegex(key, r'^[0-9a-f]{64}$')
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(module.prepare(self.path), (key, False))

    def test_blank_assignment_is_replaced_and_other_configuration_is_preserved(self):
        self.path.write_text('DATABASE_PASSWORD=fixture-only\n# keep comment\nOPEN_PLATFORM_PROTOCOL_SIGNING_KEY=\nOTHER=one\n')
        key, changed = module.prepare(self.path)
        self.assertTrue(changed)
        self.assertIn('DATABASE_PASSWORD=fixture-only\n# keep comment\n', self.path.read_text())
        self.assertIn('OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=' + key + '\nOTHER=one\n', self.path.read_text())

    def test_quoted_empty_assignment_is_prepared(self):
        self.path.write_text('OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=""\n')
        self.assertTrue(module.prepare(self.path)[1])

    def test_existing_assignment_is_never_rotated_or_rewritten(self):
        original = '# managed externally\nOPEN_PLATFORM_PROTOCOL_SIGNING_KEY="existing-fixture-key"\n'
        self.path.write_text(original)
        stamp = self.path.stat().st_mtime_ns
        self.assertEqual(module.prepare(self.path), ('existing-fixture-key', False))
        self.assertEqual(self.path.read_text(), original)
        self.assertEqual(self.path.stat().st_mtime_ns, stamp)

    def test_existing_field_key_fallback_is_not_rotated_or_rewritten(self):
        original = 'FIELD_ENCRYPTION_KEY="existing-field-fixture"\nOPEN_PLATFORM_PROTOCOL_SIGNING_KEY=\n'
        self.path.write_text(original)
        self.assertEqual(module.prepare(self.path), ('existing-field-fixture', False))
        self.assertEqual(self.path.read_text(), original)

    def test_external_field_key_fallback_is_preserved_without_creating_a_file(self):
        os.environ['FIELD_ENCRYPTION_KEY'] = 'external-field-fixture'
        self.assertEqual(module.prepare(self.path), ('external-field-fixture', False))
        self.assertFalse(self.path.exists())

    def test_external_protocol_key_has_precedence_over_file_and_field_fallback(self):
        os.environ[module.KEY] = 'external-protocol-fixture'
        os.environ['FIELD_ENCRYPTION_KEY'] = 'external-field-fixture'
        self.path.write_text(module.KEY + '=file-protocol-fixture\n')
        self.assertEqual(module.prepare(self.path), ('external-protocol-fixture', False))

    def test_empty_external_protocol_override_fails_without_creating_material(self):
        os.environ[module.KEY] = ''
        with self.assertRaisesRegex(ValueError, 'override shadows'):
            module.prepare(self.path)
        self.assertFalse(self.path.exists())

    def test_explicit_file_protocol_key_precedes_the_field_fallback(self):
        self.path.write_text(module.KEY + '=file-protocol-fixture\nFIELD_ENCRYPTION_KEY=field-fixture\n')
        self.assertEqual(module.prepare(self.path), ('file-protocol-fixture', False))

    def test_duplicate_field_fallback_fails_without_modification(self):
        original = 'FIELD_ENCRYPTION_KEY=one\nFIELD_ENCRYPTION_KEY=two\n'
        self.path.write_text(original)
        with self.assertRaisesRegex(ValueError, 'Duplicate field encryption'):
            module.prepare(self.path)
        self.assertEqual(self.path.read_text(), original)

    def test_duplicate_assignments_fail_without_modification(self):
        original = 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=one\nOPEN_PLATFORM_PROTOCOL_SIGNING_KEY=two\n'
        self.path.write_text(original)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            module.prepare(self.path)
        self.assertEqual(self.path.read_text(), original)

    def test_symlinks_fail_without_modifying_the_target(self):
        target = self.path.with_name('managed.env')
        target.write_text('OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=\n')
        self.path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            module.prepare(self.path)
        self.assertEqual(target.read_text(), 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=\n')

    def test_cli_does_not_print_signing_material(self):
        result = subprocess.run([sys.executable, str(Path(module.__file__)), '--file', str(self.path)],
                                env={**os.environ, 'GITHUB_ACTIONS': 'false'}, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        key, _ = module.prepare(self.path)
        self.assertNotIn(key, result.stdout + result.stderr)
        self.assertIn('prepared', result.stdout)


if __name__ == '__main__':
    unittest.main()
