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



class NewLocalEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.path = self.root / '.env'
        self.schema = self.root / 'platform/src/main/resources/db/snapshots/schema-current.sql'
        self.schema.parent.mkdir(parents=True)
        self.schema.write_text('CREATE TABLE fixture (id bigint);\n')
        self.plugin = self.root / 'plugins/fixture/config.json'
        self.plugin.parent.mkdir(parents=True)
        self.plugin.write_text('{"public":"fixture"}\n')
        self.private = self.plugin.with_name('private-key.txt')
        self.private.write_text('untracked-private-fixture')
        for file in [self.schema, self.plugin, self.private]: file.chmod(0o600)
        self.plugin.parent.chmod(0o700)
        (self.root / '.env.example').write_text('JWT_SECRET=public-template-fixture\nOTHER=preserved\n')
        (self.root / 'docker-compose.yml').write_text('services: {}\n')
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', str(self.schema), str(self.plugin)], check=True)
        values = dict(COMPOSE_PROJECT_NAME='isolated-test', AURABOOT_PORT='3000', POSTGRES_PORT='15432')
        self.environment = patch.dict(os.environ, values)
        self.environment.start(); self.addCleanup(self.environment.stop)
        for key in ['JWT_SECRET', 'SESSION_SECRET', 'POSTGRES_PASSWORD', module.KEY,
                    'FIELD_ENCRYPTION_KEY', 'POSTGRES_USER', 'POSTGRES_DB',
                    'AURABOOT_BIND_ADDRESS', 'POSTGRES_BIND_ADDRESS']:
            os.environ.pop(key, None)
        self.probe = patch.object(module.socket, 'socket')
        self.probe.start(); self.addCleanup(self.probe.stop)
        self.docker_results = {}
        self.calls = []

    def command(self, args, **kwargs):
        if args[0] == 'git': return subprocess.run(args, **kwargs)
        self.calls.append(args)
        self.assertEqual(args[0], 'docker')
        self.assertTrue(args[1:3] in [['ps', '-aq'], ['volume', 'ls']], 'Only read-only Docker preflight allowed')
        return subprocess.CompletedProcess(args, 0, self.docker_results.get(tuple(args), ''))

    def prepare(self): return module.prepare_new_local_evaluation(self.path, run=self.command)

    def test_new_config_has_independent_secrets_local_bindings_and_public_readability(self):
        before = {p:p.read_bytes() for p in [self.schema, self.plugin, self.private]}
        result = self.prepare()
        values = dict(line.split('=', 1) for line in self.path.read_text().splitlines())
        keys = ['JWT_SECRET', 'SESSION_SECRET', 'POSTGRES_PASSWORD', module.KEY]
        self.assertEqual(len({values[key] for key in keys}), 4)
        self.assertTrue(all(len(values[key]) >= 32 for key in keys))
        self.assertEqual(values['OTHER'], 'preserved')
        self.assertEqual(values['AURABOOT_BIND_ADDRESS'], '127.0.0.1')
        self.assertEqual(values['POSTGRES_BIND_ADDRESS'], '127.0.0.1')
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.schema.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.plugin.parent.stat().st_mode & 0o777, 0o755)
        self.assertEqual(self.private.stat().st_mode & 0o777, 0o600)
        self.assertTrue(all(p.read_bytes() == data for p,data in before.items()))
        self.assertFalse(result['existingConfigurationReplaced'])

    def test_only_tracked_executable_resources_regain_execute_bits(self):
        script = self.plugin.with_name('public.sh')
        script.write_text('#!/bin/sh\nexit 0\n'); script.chmod(0o700)
        subprocess.run(['git','-C',str(self.root),'add',str(script)],check=True)
        self.prepare()
        self.assertEqual(script.stat().st_mode & 0o777, 0o755)
        self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.private.stat().st_mode & 0o777, 0o600)

    def test_existing_environment_is_preserved_before_any_docker_or_permission_change(self):
        self.path.write_text('existing-private-config')
        with self.assertRaisesRegex(ValueError, 'absent'): self.prepare()
        self.assertEqual(self.path.read_text(), 'existing-private-config')
        self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o600)
        self.assertFalse(self.calls)

    def test_existing_project_or_volume_stops_without_creating_environment(self):
        for command in [
            ['docker','ps','-aq','--filter','label=com.docker.compose.project=isolated-test'],
            ['docker','volume','ls','-q','--filter','label=com.docker.compose.project=isolated-test'],
            ['docker','volume','ls','-q','--filter','name=^isolated-test_postgres_data$']]:
            self.docker_results = {tuple(command): 'isolated-test_postgres_data'}
            with self.subTest(command=command), self.assertRaises(ValueError): self.prepare()
            self.assertFalse(self.path.exists())
            self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o600)

    def test_inherited_secrets_or_public_binding_refuse_to_shadow_new_configuration(self):
        for key,value in [('JWT_SECRET','external-private-fixture'), ('AURABOOT_BIND_ADDRESS','0.0.0.0')]:
            with patch.dict(os.environ, {key:value}), self.subTest(key=key), self.assertRaises(ValueError): self.prepare()
            self.assertFalse(self.path.exists())

    def test_invalid_ports_and_busy_listener_fail_without_config_or_public_permission_mutation(self):
        for value in ['127.0.0.1:3000','0','65536','not-a-port']:
            with patch.dict(os.environ, {'AURABOOT_PORT':value}), self.subTest(port=value), self.assertRaises(ValueError): self.prepare()
        with patch.object(module.socket, 'socket', side_effect=OSError('busy')), self.assertRaises(OSError): self.prepare()
        self.assertFalse(self.path.exists())
        self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o600)

    def test_tracked_symlink_is_rejected_before_any_public_read_grant(self):
        link = self.root / 'plugins/fixture/linked-key'
        link.symlink_to(self.private)
        subprocess.run(['git','-C',str(self.root),'add',str(link)],check=True)
        with self.assertRaisesRegex(ValueError, 'regular tracked'): self.prepare()
        self.assertFalse(self.path.exists())
        self.assertEqual(self.private.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.plugin.stat().st_mode & 0o777, 0o600)

    def test_concurrent_environment_creation_is_not_overwritten(self):
        real_link = module.os.link
        def race(source, destination):
            Path(destination).write_text('concurrent-owner-config')
            return real_link(source, destination)
        with patch.object(module.os, 'link', side_effect=race), self.assertRaises(FileExistsError): self.prepare()
        self.assertEqual(self.path.read_text(), 'concurrent-owner-config')
        self.assertFalse(list(self.root.glob('.local-evaluation-*')))


if __name__ == '__main__':
    unittest.main()
