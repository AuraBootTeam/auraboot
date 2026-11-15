"""Hermetic shell contracts; cold database startup is verified by the public installer."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PostgresBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.capture = self.root / 'calls.jsonl'
        stub = '''#!/usr/bin/env python3
import json, os, pathlib, sys
with open(os.environ['CAPTURE'], 'a') as output:
    output.write(json.dumps({'program': pathlib.Path(sys.argv[0]).name, 'args': sys.argv[1:], 'password': os.environ.get('PGPASSWORD')}) + '\\n')
if pathlib.Path(sys.argv[0]).name == 'pg_isready':
    sys.exit(int(os.environ.get('READY_EXIT', '0')))
print(os.environ.get('QUERY_RESULT', 't'))
sys.exit(int(os.environ.get('QUERY_EXIT', '0')))
'''
        for name in ['psql', 'pg_isready']:
            path = self.root / name
            path.write_text(stub)
            path.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(self.root) + os.pathsep + os.environ['PATH'],
                    'CAPTURE': str(self.capture), 'POSTGRES_USER': 'fixture-user',
                    'POSTGRES_DB': 'fixture-db', 'POSTGRES_PASSWORD': 'private-fixture-value'}

    def run_script(self, name, **overrides):
        return subprocess.run(['bash', str(ROOT / 'scripts/docker' / name)],
                              env={**self.env, **overrides}, text=True, capture_output=True)

    def calls(self):
        return [json.loads(row) for row in self.capture.read_text().splitlines()]

    def test_schema_is_atomic_fail_fast_and_sourced_options_do_not_leak(self):
        script = ROOT / 'scripts/docker/postgres-init.sh'
        result = subprocess.run(['bash', '-c', 'set +u; source "$1"; [[ $- != *u* ]]', 'test', str(script)],
                                env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        call = self.calls()[0]
        for argument in ['--single-transaction', '--set=ON_ERROR_STOP=1', '--file=/auraboot-bootstrap/schema-current.sql']:
            self.assertIn(argument, call['args'])
        self.assertNotIn(self.env['POSTGRES_PASSWORD'], result.stdout + result.stderr + ' '.join(call['args']))

    def test_initialization_failure_is_not_swallowed(self):
        self.assertEqual(self.run_script('postgres-init.sh', QUERY_EXIT='3').returncode, 3)

    def test_health_requires_tcp_and_committed_core_tables(self):
        self.assertEqual(self.run_script('postgres-health.sh').returncode, 0)
        ready, query = self.calls()
        self.assertIn('--host=127.0.0.1', ready['args'])
        self.assertIn('--host=127.0.0.1', query['args'])
        self.assertIn('public.ab_meta_model', query['args'][-1])
        self.assertIn('public.ab_command_definition', query['args'][-1])
        self.assertEqual(query['password'], self.env['POSTGRES_PASSWORD'])
        self.assertNotIn(self.env['POSTGRES_PASSWORD'], ' '.join(query['args']))

    def test_missing_tables_query_failure_and_noncanonical_output_are_unhealthy(self):
        for overrides in [{'QUERY_RESULT': 'f'}, {'QUERY_EXIT': '1'}, {'QUERY_RESULT': 't\nf'}, {'QUERY_RESULT': ''}]:
            with self.subTest(overrides=overrides):
                self.assertNotEqual(self.run_script('postgres-health.sh', **overrides).returncode, 0)

    def test_health_relations_exist_in_the_shipped_schema(self):
        import re
        script = (ROOT / 'scripts/docker/postgres-health.sh').read_text()
        schema = (ROOT / 'platform/src/main/resources/db/snapshots/schema-current.sql').read_text()
        relations = re.findall(r"to_regclass\('public\.([a-z_]+)'\)", script)
        self.assertGreaterEqual(len(relations), 2)
        for relation in relations:
            self.assertRegex(schema, r'CREATE TABLE public\.' + re.escape(relation) + r'\s*\(')

    def test_unready_tcp_does_not_attempt_schema_query(self):
        self.assertNotEqual(self.run_script('postgres-health.sh', READY_EXIT='2').returncode, 0)
        self.assertEqual([call['program'] for call in self.calls()], ['pg_isready'])


if __name__ == '__main__':
    unittest.main()
