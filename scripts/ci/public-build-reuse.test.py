"""Hermetic contract tests; these do not claim Docker or browser execution."""
import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('reuse', Path(__file__).with_name('public-build-reuse.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ReuseContract(unittest.TestCase):
    def fixture(self):
        receipt = dict(format='auraboot.public-source-build.v1', sourceCommit='a'*40,
                       project='aura-public-'+'b'*16, sourceRoot='/owned/source',
                       images={s:'sha256:'+str(i)*64 for i,s in enumerate(['backend','frontend','postgres'],1)})
        checks=[dict(id='compose-public-entry',status='passed',source=receipt['sourceCommit'],project=receipt['project']),
                dict(id='public-initialization',status='passed',projects=8,hostNodeDeniedDuringBootstrap=True)]
        containers={s:dict(Image=v,State=dict(Running=False),Config=dict(Labels={
            'com.docker.compose.project':receipt['project'],'com.docker.compose.service':s,
            'com.docker.compose.project.working_dir':receipt['sourceRoot']})) for s,v in receipt['images'].items()}
        images={s:dict(Id=v,Os='linux',Architecture='amd64')for s,v in receipt['images'].items()}
        tree={'Dockerfile':'product','plugins/role.json':'role'}
        return [receipt,dict(tree),dict(tree),checks,containers,images]
    def test_same_product_driver_change_preserves_provenance(self):
        a=self.fixture();a[1]['scripts/ci/public-build-reuse.py']='newdriver';r=m.validate(*a)
        self.assertFalse(r['sourceBuildExecutedThisRound']);self.assertFalse(r['businessDataReused'])
        self.assertTrue(r['productionInputsUnchanged'])
    def test_any_production_change_rejected(self):
        for path in ['Dockerfile','plugins/role.json','platform/build.gradle','pnpm-lock.yaml','docker-compose.yml']:
            a=self.fixture();a[1][path]='mutated'
            with self.assertRaisesRegex(RuntimeError,'Production'):m.validate(*a)
    def test_missing_or_wrong_original_success_is_rejected(self):
        for change in [lambda a:a[3].clear(),lambda a:a[3][0].update(status='failed'),lambda a:a[3][0].update(source='c'*40),lambda a:a[3][1].update(hostNodeDeniedDuringBootstrap=False)]:
            a=self.fixture();change(a)
            with self.assertRaises(RuntimeError):m.validate(*a)
    def test_tags_missing_images_or_replaced_image_rejected(self):
        for change in [lambda a:a[0]['images'].update(backend='mutable:tag'),lambda a:a[0]['images'].pop('postgres'),lambda a:a[5]['backend'].update(Id='sha256:'+'f'*64)]:
            a=self.fixture();change(a)
            with self.assertRaises(RuntimeError):m.validate(*a)
    def test_foreign_active_or_different_container_is_rejected(self):
        for change in [lambda a:a[4]['backend']['State'].update(Running=True),lambda a:a[4]['frontend']['Config']['Labels'].update({'com.docker.compose.project.working_dir':'/other'}),lambda a:a[4]['postgres'].update(Image='sha256:'+'e'*64)]:
            a=self.fixture();change(a)
            with self.assertRaises(RuntimeError):m.validate(*a)
    def test_wrong_architecture_or_os_rejected(self):
        for field,value in [('Os','other'),('Architecture','arm64')]:
            a=self.fixture();a[5]['backend'][field]=value
            with self.assertRaises(RuntimeError):m.validate(*a)

class NetworkContract(unittest.TestCase):
    def test_scoped_nonoverlapping_subnet(self):
        self.assertEqual(m.validate_subnet('10.253.200.0/28', ['10.66.0.0/24', '172.17.0.0/16', '::/64']), '10.253.200.0/28')
    def test_overlap_wrong_scope_size_and_host_bits_are_rejected(self):
        for value,used in [('10.253.200.0/28',['10.253.200.3/32']), ('10.253.200.0/28',['10.0.0.0/8']),
                           ('192.168.1.0/28',[]), ('10.253.200.0/24',[]), ('10.253.200.1/28',[])]:
            with self.assertRaises((RuntimeError, ValueError)): m.validate_subnet(value,used)

class CompletionContract(unittest.TestCase):
    def fixture(self):
        spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('verify-public-project-install.py'))
        installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)
        return installer, [dict(id='compose-public-entry',status='passed'),
            dict(id='public-initialization',status='passed',projects=8,hostNodeDeniedDuringBootstrap=True),
            dict(id='public-browser',status='passed',executed=3,passed=3,skipped=0,retries=0),
            dict(id='documented-stop-reopen',status='passed',projects=9,recordsUnchanged=True)]
    def test_actual_completion_is_structured_and_requires_restart(self):
        installer, rows = self.fixture(); self.assertEqual(installer.completion_receipt(rows,True)['browser']['passed'],3)
        rows[-1]['recordsUnchanged']=False
        with self.assertRaises(RuntimeError): installer.completion_receipt(rows,True)
    def test_empty_partial_skipped_or_unbound_consumption_cannot_emit_success(self):
        for mode in ['empty','partial','skip','unbound']:
            installer, rows=self.fixture()
            if mode=='empty': rows=[]
            if mode=='partial': rows[2]['passed']=2
            if mode=='skip': rows[2]['skipped']=1
            with self.assertRaises(RuntimeError): installer.completion_receipt(rows,mode!='unbound')

if __name__=='__main__': unittest.main()
