"""Bind actual local browser and original-image review evidence to the frozen release candidate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from open_platform_http import ProtocolError

TITLES = {
    'publishes, grants, calls and withdraws an adapted Controller endpoint through management UI',
    'generates expiring and permanent tokens, rotates and revokes through the installation UI',
    'publishes, grants, calls and withdraws a real command through management UI',
    'publishes, grants, calls and withdraws a real handler through management UI',
}
COMBINATIONS = {(locale,viewport) for locale in ('zh-CN','en-US') for viewport in ('desktop','narrow')}
TOKEN_IMAGES = {'empty','fixed-form','fixed-secret','permanent-form','rotation-form','rotation-secret','revoke-confirmation','revoked'}
BUSINESS_IMAGES = {'QAT-06-downloads','QAT-08-guide-entry','QAT-06-configured','QAT-06-publish-confirmation','QAT-05-authorized','QAT-05-revoke-confirmation','QAT-06-withdraw-confirmation'}
ENDPOINT_IMAGES = {'CEP-04-downloads','CEP-07-recovery','CEP-08-discard-confirmation','CEP-01-catalog','CEP-02-configured','CEP-03-publish-confirmation','CEP-04-authorized','CEP-05-revoke-confirmation','CEP-06-withdraw-confirmation'}
ENDPOINT_STATES = ['menu-catalog-and-unadapted-denial','source-validation-and-dirty-cancel','dirty-discard-confirm','network-permission-source-recovery','draft-save','publish-cancel-confirm','openapi-types-download','ungranted-denial','authority-save-call','reload-persistence','revoke-cancel-confirm-denial','withdraw-cancel-confirm-denial']
TOKEN_STATES = ['empty','fixed-cancel','fixed-create-and-bearer','reload-redaction','permanent-create-and-bearer',
                'rotate-immediate-and-bearer','revoke-cancel','revoke-and-bearer','sibling-preserved','reopen-redaction']
BUSINESS_STATES = ['menu-entry','packaged-guide-entry-and-anonymous-read','source-contract-save','publication-cancel-confirm',
                   'openapi-types-download','ungranted-denial','authority-save-call','reload-persistence','revoke-cancel-confirm-denial','withdraw-cancel-confirm-denial']
SOURCE_FILES = (
    'web-admin/tests/e2e/settings/open-platform.golden.spec.ts',
    'web-admin/app/ui/smart/admin/IntegrationTokenPanel.tsx',
    'web-admin/app/ui/smart/admin/OpenApiActionPublicationEditor.tsx',
    'web-admin/app/ui/smart/admin/OpenApiInstallationGrantEditor.tsx',
    'web-admin/app/plugins/core-settings/pages/settings/api-docs.tsx',
    'web-admin/public/docs/open-platform-integration.html',
    'web-admin/public/docs/open-platform-integration.zh-CN.html',
    'web-admin/app/ui/smart/admin/OpenApiEndpointPublicationEditor.tsx',
    'web-admin/app/services/open-api-publications.ts',
)


def require(condition,message):
    if not condition: raise ProtocolError('quick-access UI: '+message)


def sha(raw): return hashlib.sha256(raw).hexdigest()


def parse_browser(raw):
    text = raw.decode('utf-8'); start=text.find('{')
    require(start>=0,'browser result absent')
    report=json.loads(text[start:]); stats=report.get('stats',{})
    require(stats.get('expected')==4 and all(type(stats.get(key)) is int and stats[key]==0 for key in ('unexpected','flaky','skipped'))
            and report.get('errors')==[], 'browser execution denominator')
    tests=[]
    def walk(suite):
        for spec in suite.get('specs',[]):
            require(spec.get('title') in TITLES and spec.get('ok') is True,'unexpected or failed browser spec')
            for test in spec.get('tests',[]):
                results=test.get('results',[])
                require(test.get('expectedStatus')=='passed' and test.get('status')=='expected' and len(results)==1
                        and results[0].get('status')=='passed' and results[0].get('retry')==0
                        and not any(a.get('type') in ('skip','fixme') for a in test.get('annotations',[])), 'browser case failed/skipped/retried')
                tests.append(spec['title'])
        for child in suite.get('suites',[]):walk(child)
    for suite in report.get('suites',[]):walk(suite)
    require(len(tests)==4 and set(tests)==TITLES,'exact executed browser actions')
    require(not re.search(rb'ab_at_[A-Za-z0-9_-]{16,}',raw),'credential material in browser evidence')
    return report


def verify_archive(archive, *, root, expected_ref):
    root=Path(root)
    require(re.fullmatch(r'[a-f0-9]{40}',expected_ref) is not None,'frozen Core commit required')
    with zipfile.ZipFile(archive) as bundle:
        names=bundle.namelist();require(len(names)==len(set(names)) and len(names)<=250,'archive entries')
        require(all(not name.startswith('/') and '..' not in Path(name).parts and '\\' not in name for name in names),'archive path')
        require(sum(info.file_size for info in bundle.infolist())<=50*1024*1024,'archive byte budget')
        receipt=json.loads(bundle.read('receipt.json'))
        require(receipt.get('profile')=='open-platform-quick-ui-v1' and receipt.get('coreCommit')==expected_ref,'candidate identity')
        require(receipt.get('sourceHashes')=={name:sha((root/name).read_bytes()) for name in SOURCE_FILES},'frozen UI source bytes')
        runs=receipt.get('runs');require(isinstance(runs,list) and len(runs)==4,'locale/viewport denominator')
        require({(run.get('locale'),run.get('viewport')) for run in runs}==COMBINATIONS,'locale/viewport matrix')
        referenced={'receipt.json'}
        image_count=0
        for run in runs:
            report_name=run['report']; referenced.add(report_name)
            raw=bundle.read(report_name);require(sha(raw)==run['reportSha256'],'browser result bytes');parse_browser(raw)
            manifests=run.get('journeys');require(isinstance(manifests,list) and len(manifests)==4,'journey manifests')
            profiles=set()
            for manifest in manifests:
                referenced.add(manifest['path']);raw=bundle.read(manifest['path']);require(sha(raw)==manifest['sha256'],'journey bytes')
                journey=json.loads(raw);profiles.add((journey.get('profile'),journey.get('kind')))
                require(journey.get('executed')==(TOKEN_STATES if journey.get('profile')=='quick-token-browser-v1' else ENDPOINT_STATES if journey.get('profile')=='controller-endpoint-browser-v1' else BUSINESS_STATES),'journey state denominator')
                require(journey.get('passed') is True and journey.get('locale')==run['locale'] and journey.get('viewport')==run['viewport']
                        and isinstance(journey.get('executed'),list) and len(journey['executed'])>=9
                        and len(set(journey['executed']))==len(journey['executed']),'executed journey states')
            require(profiles=={('quick-token-browser-v1',None),('business-api-browser-v1','command'),('business-api-browser-v1','handler'),('controller-endpoint-browser-v1',None)},'journey types')
            images=run.get('images');require(isinstance(images,list) and len(images)==31,'original-image denominator')
            base='ui-golden-20260914/'
            expected_images={base+f"quick-token-{run['locale']}-{run['viewport']}/QAT-07-{name}.png" for name in TOKEN_IMAGES}
            expected_images|={base+f"business-api-{run['locale']}-{run['viewport']}-{kind}/{name}.png" for kind in ('command','handler') for name in BUSINESS_IMAGES}
            expected_images|={base+f"controller-endpoint-{run['locale']}-{run['viewport']}/{name}.png" for name in ENDPOINT_IMAGES}
            require({entry.get('path') for entry in images}==expected_images,'original image action coverage')
            for entry in images:
                name=entry['path']; referenced.add(name); raw=bundle.read(name)
                require(raw.startswith(b'\x89PNG\r\n\x1a\n') and len(raw)>100 and sha(raw)==entry.get('sha256'),'original PNG bytes')
                width,height=struct.unpack('>II',raw[16:24])
                require(50<=width<=(1440 if run['viewport']=='desktop' else 1024) and height>=70,'original image dimensions')
                review=entry.get('review',{})
                require(review.get('verdict')=='passed' and isinstance(review.get('notes'),str) and len(review['notes'])>=12,'original image not reviewed')
                image_count+=1
            artifacts=run.get('artifacts');require(isinstance(artifacts,list) and len(artifacts)==6,'download artifacts')
            require(len({artifact.get('path') for artifact in artifacts})==6 and all(
                sum(artifact['path'].startswith(base+f"business-api-{run['locale']}-{run['viewport']}-{kind}/") for artifact in artifacts)==2
                for kind in ('command','handler')) and sum(artifact['path'].startswith(base+f"controller-endpoint-{run['locale']}-{run['viewport']}/") for artifact in artifacts)==2,'download source binding')
            for artifact in artifacts:
                name=artifact['path'];referenced.add(name);raw=bundle.read(name)
                require(sha(raw)==artifact.get('sha256') and len(raw)>100,'download bytes')
                if name.endswith('.openapi.json'):
                    doc=json.loads(raw);require(doc.get('openapi')=='3.1.0' and any(('/endpoints/' if 'controller-endpoint-' in name else '/actions/') in path for path in doc.get('paths',{})),'downloaded OpenAPI')
                else:
                    prefix = b'Endpoint' if 'controller-endpoint-' in name else b'Action'
                    require(name.endswith('.d.ts') and b'export type '+prefix+b'Request' in raw and b'export type '+prefix+b'Result' in raw,'downloaded types')
        require(referenced==set(names),'unreferenced or missing archive bytes')
    return {'profile':'open-platform-quick-ui-v1','coreCommit':expected_ref,'browserCases':16,'reviewedOriginalImages':image_count,
            'downloadedArtifacts':24,'archiveSha256':sha(Path(archive).read_bytes()),'passed':True}


def pack(inputs, *, root, review_file, destination):
    root=Path(root);descriptor=json.loads(Path(inputs).read_text());review=json.loads(Path(review_file).read_text())
    require(subprocess.check_output(['git','status','--porcelain'],cwd=root)==b'','clean Core source required')
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    source=Path(descriptor['evidenceRoot']).resolve();runs=[];files={}
    def add(relative):
        require(isinstance(relative,str) and not Path(relative).is_absolute() and '..' not in Path(relative).parts,'evidence path')
        path=(source/relative);require(path.is_file() and not path.is_symlink(),'regular evidence file')
        files[relative]=path.read_bytes();return sha(files[relative])
    for entry in descriptor['runs']:
        locale,viewport=entry['locale'],entry['viewport'];run={'locale':locale,'viewport':viewport,'report':entry['report'],'reportSha256':add(entry['report'])}
        base=Path('ui-golden-20260914');directories=[base/f'quick-token-{locale}-{viewport}']+[base/f'business-api-{locale}-{viewport}-{kind}' for kind in ('command','handler')]+[base/f'controller-endpoint-{locale}-{viewport}']
        journeys=[];images=[];artifacts=[]
        for directory in directories:
            name=str(directory/'result.json');journeys.append({'path':name,'sha256':add(name)})
            for path in sorted((source/directory).glob('*.png')):
                relative=str(path.relative_to(source));digest=add(relative)
                require(relative in review and review[relative].get('sha256')==digest,'original review digest')
                images.append({'path':relative,'sha256':digest,'review':review[relative]})
            for path in sorted((source/directory).iterdir()):
                if path.name.endswith(('.openapi.json','.d.ts')):
                    relative=str(path.relative_to(source));artifacts.append({'path':relative,'sha256':add(relative)})
        run.update(journeys=journeys,images=images,artifacts=artifacts);runs.append(run)
    receipt=dict(profile='open-platform-quick-ui-v1',coreCommit=commit,sourceHashes={name:sha((root/name).read_bytes()) for name in SOURCE_FILES},runs=runs)
    files['receipt.json']=json.dumps(receipt,sort_keys=True).encode()
    with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED) as bundle:
        for name,raw in files.items():bundle.writestr(name,raw)
    return verify_archive(destination,root=root,expected_ref=commit)


if __name__=='__main__':
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='operation',required=True)
    verify=sub.add_parser('verify');verify.add_argument('--archive',required=True);verify.add_argument('--root',required=True);verify.add_argument('--expected-ref',required=True)
    build=sub.add_parser('pack');build.add_argument('--inputs',required=True);build.add_argument('--root',required=True);build.add_argument('--review',required=True);build.add_argument('--out',required=True)
    args=parser.parse_args()
    result=verify_archive(args.archive,root=args.root,expected_ref=args.expected_ref) if args.operation=='verify' else pack(args.inputs,root=args.root,review_file=args.review,destination=args.out)
    print(json.dumps(result,sort_keys=True))
