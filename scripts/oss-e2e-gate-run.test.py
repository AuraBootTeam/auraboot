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
        shutil.copytree(SOURCE.parent / 'lib', self.repo / 'scripts/lib')
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'runtime.yaml').write_text('repos: {}\n')
        self.env = {**os.environ, 'AURA_WORKSPACE_ROOT': str(self.root),
                    'PATH': str(self.repo / 'bin') + os.pathsep + os.environ['PATH'],
                    'CALLS': str(self.root / 'calls'), 'AURA_EVIDENCE_ROOT': str(self.root / 'evidence')}
        self.executable(self.root / 'dev.sh', '#!/bin/bash\nexit 0\n')
        self.executable(self.root / 'aura', '''#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
if (args[0] === 'control') process.exit(0);
if (process.env.RECORD_AURA === '1') fs.appendFileSync(process.env.CALLS, args.join(' ') + '\\n');
if (args[1] === 'list') console.log('name repo slot\\n' + (process.env.EXISTING_RUNTIME || ''));
else if (args[1] === 'evidence' && args[2] === 'begin') {
  const dir = path.join(process.env.AURA_WORKSPACE_ROOT, '.workspace/evidence/fixture-round');
  fs.mkdirSync(dir, { recursive: true });
  console.log(dir);
} else console.log('runtime ensure');
''')
        self.executable(self.repo / 'bin/lsof', '#!/bin/bash\nexit 1\n')
        self.executable(self.repo / 'bin/pnpm', """#!/bin/bash
printf '%s\\n' "$*" >> "$CALLS"
python3 - <<'REPORT_FIXTURE'
import json,os
from pathlib import Path
p=Path(os.environ['PW_RESULTS_JSON']);p.parent.mkdir(parents=True,exist_ok=True)
if os.environ.get('REPORT_MISSING')!='1':
 n=int(os.environ.get('REPORT_COUNT','12'))
 tests=[{'status':'expected','results':[{'status':'passed','retry':int(os.environ.get('REPORT_RETRY','0'))}]} for _ in range(n)]
 p.write_text(json.dumps({'stats':{'expected':n,'unexpected':0,'skipped':int(os.environ.get('REPORT_SKIPPED','0')),'flaky':0},'errors':[], 'config':{'projects':[{'name':'oss','retries':0}]},'suites':[{'specs':[{'tests':tests}]}]}))
REPORT_FIXTURE
printf '12 passed\\n'
exit "${PW_EXIT:-0}"
""")
        self.executable(self.repo / 'scripts/oss-golden-stack.sh', '''#!/bin/bash
printf '%s\n' "$*" >> "$CALLS"
case "$1" in
 up) exit "${UP_EXIT:-0}";;
 env) printf 'export PLAYWRIGHT_BASE_URL=http://private-fixture BACKEND_URL=http://private-fixture BFF_PORT=1 PW_RESULTS_JSON=$AURA_EVIDENCE_ROOT/report/results.json\n';;
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
        self.assertNotIn('--reporter=', calls)

    def test_missing_json_rejects_process_green(self):
        self.assertEqual(self.run_gate(REPORT_MISSING='1').returncode, 1)

    def test_skipped_report_rejects_process_green(self):
        self.assertEqual(self.run_gate(REPORT_SKIPPED='1').returncode, 1)

    def test_changed_slice_count_rejects_process_green(self):
        self.assertEqual(self.run_gate(REPORT_COUNT='11').returncode, 1)

    def test_retry_rejects_process_green(self):
        self.assertEqual(self.run_gate(REPORT_RETRY='1').returncode, 1)

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
        self.executable(self.repo / 'bin/psql', '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALLS"\nprintf 1\n')
        r = subprocess.run(['bash', str(self.repo / 'scripts/real-stack.sh'), 'up', 'owned-new', '--slot', '239', '--require-new-db'],
                           env={**self.env, 'RECORD_AURA': '1'}, capture_output=True, text=True, timeout=10)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn('already exists', r.stderr)
        self.assertNotIn('infra ensure', self.calls())
        self.assertNotIn('DROP DATABASE', self.calls())
        self.assertNotIn('CREATE DATABASE', self.calls())
        self.assertNotIn('gradle ', self.calls())

if __name__ == '__main__':
    unittest.main()
