"""Hermetic lifecycle admission; no real runtime is started or suspended."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('settlement', Path(__file__).with_name('settle_runtime.py'))
settlement = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(settlement)


class CIRuntimeSettlementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve(); self.repo = self.root/'auraboot'; self.repo.mkdir()
        self.workspace = self.root/'workspace'; self.workspace.mkdir()
        self.artifacts = self.root/'artifacts'; self.artifacts.mkdir()
        subprocess.run(['git','init','-q',str(self.repo)],check=True)
        (self.repo/'fixture.txt').write_text('owned synthetic source')
        subprocess.run(['git','-C',str(self.repo),'add','fixture.txt'],check=True)
        subprocess.run(['git','-C',str(self.repo),'-c','user.name=Fixture','-c','user.email=fixture@example.invalid',
                        'commit','-qm','synthetic source'],check=True)
        head = subprocess.check_output(['git','-C',str(self.repo),'rev-parse','HEAD'],text=True).strip()
        self.identity = {'allocation':{'name':'owned-gate','repo':'auraboot'},
            'metadata':{'lifecycle':'open'},'processes':[{'pid':1}],
            'ports':{key:{'status':'listening'} for key in ('backend','web','bff')},
            'sources':[{'key':'auraboot','expected':{'root':str(self.repo),'commit':head},
                'actual':{'root':str(self.repo),'commit':head,'dirtyFingerprint':'clean'}}]}
        self.config = self.root/'identity.json'; self.config.write_text(json.dumps(self.identity))
        self.calls = self.root/'calls.txt'; self.calls.write_text('')
        cli = self.workspace/'aura'
        cli.write_text('''#!/usr/bin/env python3
import os,json,sys
from pathlib import Path
args=sys.argv[1:];root=Path(os.environ['FIXTURE_ROOT']);x=json.loads((root/'identity.json').read_text())
with (root/'calls.txt').open('a') as f:f.write(' '.join(args)+'\\n')
if args[1]=='show':
 print(json.dumps({} if os.environ.get('EMPTY_INVENTORY') else {'allocation':None if os.environ.get('NOT_STARTED') else x['allocation']}))
elif args[1]=='suspend':
 if os.environ.get('SUSPEND_FAIL'):raise SystemExit(2)
 (root/'suspended').write_text('owned fake lifecycle only')
elif args[1]=='explain':
 if (root/'suspended').exists():
  x['metadata']['lifecycle']='suspended';x['processes']=[]
  for row in x['ports'].values():row['status']='stopped'
  if os.environ.get('REMAINING_PORT'):x['ports']['web']['status']='listening'
 print(json.dumps(x))
''');cli.chmod(0o755)
        self.context = self.root/'frozen.json'; self.context.write_text('{}')
        self.env = dict(os.environ,AURA_CI_JOB_ID='job-'+'a'*40,AURA_CI_WORKSPACE_ROOT=str(self.root),
                        AURA_CI_FROZEN_CONTEXT=str(self.context),FIXTURE_ROOT=str(self.root))

    def invoke(self, **changes):
        return settlement.settle(self.workspace,self.repo,'owned-gate',self.artifacts,{**self.env,**changes})

    def test_exact_job_source_uses_original_lifecycle_and_retains_allocation(self):
        result = self.invoke()
        self.assertEqual(result['status'],'suspended'); self.assertEqual(result['remainingProcesses'],[])
        self.assertEqual(result['retained'],['allocation','database','caches','evidence'])
        self.assertIn('runtime suspend owned-gate',self.calls.read_text())
        self.assertEqual((self.repo/'fixture.txt').read_text(),'owned synthetic source')
        after = json.loads((self.artifacts/'runtime-settlement-owned-gate/identity-after.json').read_text())
        self.assertEqual(after['allocation'],self.identity['allocation'])

    def test_foreign_or_drifted_source_cannot_reach_suspend(self):
        bad = copy.deepcopy(self.identity);bad['sources'][0]['expected']['root']='/another/owner'
        self.config.write_text(json.dumps(bad))
        with self.assertRaises(ValueError):self.invoke()
        self.assertNotIn('runtime suspend',self.calls.read_text())

    def test_missing_admitted_context_cannot_reach_runtime_cli(self):
        with self.assertRaises(ValueError):self.invoke(AURA_CI_FROZEN_CONTEXT='/missing/frozen.json')
        self.assertEqual(self.calls.read_text(),'')

    def test_missing_inventory_is_not_an_empty_green(self):
        with self.assertRaises(ValueError):self.invoke(EMPTY_INVENTORY='1')
        self.assertNotIn('runtime suspend',self.calls.read_text())

    def test_unallocated_startup_does_not_stop_another_runtime(self):
        self.assertEqual(self.invoke(NOT_STARTED='1')['status'],'not-started')
        self.assertNotIn('runtime suspend',self.calls.read_text())

    def test_failed_lifecycle_cannot_create_a_success_receipt(self):
        with self.assertRaises(ValueError):self.invoke(SUSPEND_FAIL='1')
        self.assertFalse((self.artifacts/'runtime-settlement-owned-gate/result.json').exists())

    def test_remaining_listener_blocks_success_after_the_original_handler_returns(self):
        with self.assertRaises(ValueError):self.invoke(REMAINING_PORT='1')
        self.assertFalse((self.artifacts/'runtime-settlement-owned-gate/result.json').exists())


if __name__ == '__main__':unittest.main()
