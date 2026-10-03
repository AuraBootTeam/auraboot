#!/usr/bin/env python3
"""Hermetic process contracts; does not qualify product browser behavior."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).with_name('oss-e2e-gate-run.sh')
STACK = SOURCE.with_name('oss-golden-stack.sh')

class GateContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='oss-gate-contract-')
        self.root = Path(self.temp.name)
        self.repo = self.root / 'auraboot'
        for d in ['scripts', 'web-admin', 'bin']:
            (self.repo / d).mkdir(parents=True)
        shutil.copy(SOURCE, self.repo / 'scripts/oss-e2e-gate-run.sh')
        self.env = {**os.environ, 'AURA_WORKSPACE_ROOT': str(self.root),
                    'PATH': str(self.repo / 'bin') + os.pathsep + os.environ['PATH'],
                    'CALLS': str(self.root / 'calls'), 'AURA_EVIDENCE_ROOT': str(self.root / 'evidence')}
        self.executable(self.root / 'dev.sh', '#!/bin/bash\nexit 0\n')
        self.executable(self.root / 'aura', '#!/bin/bash\nif [ "$2" = list ]; then printf "name repo slot\\n%s" "${EXISTING_RUNTIME:-}"; else echo "runtime ensure"; fi\n')
        self.executable(self.repo / 'bin/lsof', '#!/bin/bash\nexit 1\n')
        self.executable(self.repo / 'bin/pnpm', '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\nprintf "4 passed\\n"\nexit "${PW_EXIT:-0}"\n')
        self.executable(self.repo / 'scripts/oss-golden-stack.sh', '''#!/bin/bash
printf '%s\n' "$*" >> "$CALLS"
case "$1" in
 up) exit "${UP_EXIT:-0}";;
 env) printf 'export PLAYWRIGHT_BASE_URL=http://private-fixture BACKEND_URL=http://private-fixture BFF_PORT=1\n';;
 destroy|down) exit 99;;
esac
''')

    def tearDown(self):
        self.temp.cleanup()

    def executable(self, p, text):
        p.write_text(text)
        p.chmod(0o755)

    def run_gate(self, **env):
        return subprocess.run(['bash', str(self.repo / 'scripts/oss-e2e-gate-run.sh'), '--name', 'owned-new', '--slot', '239'],
                              env={**self.env, **env}, capture_output=True, text=True, timeout=10)

    def calls(self):
        p = self.root / 'calls'
        return p.read_text() if p.exists() else ''

    def test_success_keeps_environment_and_original_slice(self):
        r = self.run_gate()
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.calls()
        self.assertNotIn('destroy', calls)
        self.assertNotIn('down', calls)
        self.assertIn('--require-new-db', calls)
        self.assertNotIn('--fresh-db', calls)
        self.assertIn('--project=oss --no-deps --repeat-each=1', calls)
        self.assertEqual(calls.count('.spec.ts'), 4)

    def test_browser_failure_keeps_exit_and_environment(self):
        r = self.run_gate(PW_EXIT='1')
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertNotIn('destroy', self.calls())

    def test_bringup_failure_never_runs_browser_or_cleanup(self):
        r = self.run_gate(UP_EXIT='1')
        self.assertEqual(r.returncode, 2)
        self.assertNotIn('playwright', self.calls())
        self.assertNotIn('destroy', self.calls())

    def test_registered_runtime_is_not_reused_or_destroyed(self):
        r = self.run_gate(EXISTING_RUNTIME='owned-new auraboot 239\n')
        self.assertEqual(r.returncode, 2)
        self.assertEqual(self.calls(), '')

    def test_existing_evidence_is_not_overwritten(self):
        p = self.root / '.workspace/golden/owned-new'
        p.mkdir(parents=True)
        marker = p / 'retained'
        marker.write_text('previous evidence')
        r = self.run_gate()
        self.assertEqual(r.returncode, 2)
        self.assertEqual(self.calls(), '')
        self.assertEqual(marker.read_text(), 'previous evidence')

    def test_stack_refuses_existing_database_before_infra_or_build(self):
        shutil.copy(STACK, self.repo / 'scripts/real-stack.sh')
        p = self.root / '.workspace/env/owned-new.env'
        p.parent.mkdir(parents=True)
        p.write_text('SERVER_PORT=1\nVITE_PORT=2\nBFF_PORT=3\nPOSTGRES_DB=auraboot_239\nREDIS_DATABASE=1\nPOSTGRES_HOST=127.0.0.1\nPOSTGRES_PORT=5432\nPOSTGRES_USER=fixture\nPOSTGRES_PASSWORD=fixture\n')
        self.executable(self.root / 'aura', '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\nif [ "$1" = runtime ] && [ $# = 1 ]; then echo "runtime ensure"; fi\n')
        self.executable(self.repo / 'bin/psql', '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\nprintf 1\n')
        r = subprocess.run(['bash', str(self.repo / 'scripts/real-stack.sh'), 'up', 'owned-new', '--slot', '239', '--require-new-db'],
                           env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn('already exists', r.stderr)
        self.assertNotIn('infra ensure', self.calls())
        self.assertNotIn('DROP DATABASE', self.calls())
        self.assertNotIn('CREATE DATABASE', self.calls())
        self.assertNotIn('gradle ', self.calls())

if __name__ == '__main__':
    unittest.main()
