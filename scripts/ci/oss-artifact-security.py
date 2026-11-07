"""Scan the exact unpublished native package and its OCI layout; never publish.

All severities are retained. High/critical vulnerabilities and any secret hit
keep this gate red until fixed or individually reviewed outside this runner.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import zipfile


VERSION = '0.76.1'
ARCHIVE_SHA = 'c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0'
URL = f'https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/trivy_{VERSION}_Linux-64bit.tar.gz'


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    package, output = [Path(value).resolve() for value in sys.argv[1:]]
    output.mkdir(mode=0o700)
    private = output / 'private'
    private.mkdir(mode=0o700)
    receipt = json.loads((package / 'release-receipt.json').read_text())
    archive = private / 'trivy.tar.gz'
    def dataset(identity, expected=None):
        inputs = json.loads(Path(os.environ['AURA_CI_DATASET_INPUTS']).read_text())
        matches = [item for item in inputs['files'] if item['id'] == identity]
        if not matches:
            return None
        if len(matches) != 1:
            raise RuntimeError('one admitted scanner input required')
        source = Path(matches[0]['file'])
        if source.is_symlink() or not source.is_file():
            raise RuntimeError('one admitted regular scanner input required')
        actual = digest(source)
        if actual != matches[0]['sha256'] or (expected and actual != expected):
            raise RuntimeError('scanner input integrity mismatch')
        return source

    scanner_input = dataset('trivy-linux-0761', ARCHIVE_SHA)
    # Curl follows the admitted host's HTTPS/proxy policy; transient transfer
    # failures can retry, but a completed checksum mismatch never retries.
    if scanner_input:
        __import__('shutil').copyfile(scanner_input, archive)
    else:
        with (private / 'scanner-download.log').open('xb') as log:
            result = subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--retry-all-errors',
                '--connect-timeout', '20', '--max-time', '300', '--output', str(archive), URL],
                stdout=log, stderr=subprocess.STDOUT, timeout=960)
        if result.returncode:
            raise RuntimeError('scanner download failed; original transfer log retained')
    if digest(archive) != ARCHIVE_SHA:
        raise RuntimeError('scanner archive integrity mismatch')
    with tarfile.open(archive) as compressed:
        member = compressed.getmember('trivy')
        if not member.isfile():
            raise RuntimeError('scanner binary is not regular')
        with compressed.extractfile(member) as source, (private / 'trivy').open('xb') as target:
            __import__('shutil').copyfileobj(source, target)
    executable = private / 'trivy'
    executable.chmod(0o700)
    cache = private / 'cache'
    cache_input = dataset('trivy-databases-20261105')
    if cache_input:
        cache.mkdir(mode=0o700)
        with tarfile.open(cache_input) as snapshot:
            if any(not (member.isfile() or member.isdir()) for member in snapshot.getmembers()):
                raise RuntimeError('scanner DB snapshot has a nonregular entry')
            snapshot.extractall(cache, filter='data')
        if not (cache / 'db/metadata.json').is_file() or not (cache / 'java-db/metadata.json').is_file():
            raise RuntimeError('scanner DB snapshot is incomplete')
    env = dict(os.environ, TRIVY_CACHE_DIR=str(cache))
    # Disable ambient CLI config/ignore inputs. This is not a source lock audit.
    empty_config, empty_ignore = private / 'trivy.yaml', private / 'trivyignore'
    empty_config.write_text('{}\n')
    empty_ignore.write_text('')
    common = ['--config', str(empty_config), '--ignorefile', str(empty_ignore), '--timeout', '20m']
    if cache_input:
        common += ['--skip-db-update', '--skip-java-db-update', '--skip-check-update']

    def run(args, label):
        with (private / (label + '.log')).open('xb') as log:
            result = subprocess.run([str(executable)] + args, env=env, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=1500)
        if result.returncode:
            raise RuntimeError(f'{label} scanner failed; private log retained')

    inventory = [{'path': str(file.relative_to(package)), 'bytes': file.stat().st_size,
                  'sha256': digest(file)} for file in sorted(package.rglob('*')) if file.is_file()]
    (output / 'file-inventory.json').write_text(json.dumps(inventory, indent=2))
    jar = package / 'runtime/AuraBoot-1.0.0-boot.jar'
    with zipfile.ZipFile(jar) as source:
        libraries = sorted(name for name in source.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'))
    if not libraries:
        raise RuntimeError('native JAR dependency inventory is empty')
    (output / 'jar-libraries.json').write_text(json.dumps(libraries, indent=2))
    image = package / receipt['image']['layout']
    if not (image / 'index.json').is_file():
        raise RuntimeError('actual OCI layout required')
    # rootfs enables post-build JAR analysis; fs deliberately does not analyze
    # Java archives. Expand the delivered npm tarballs as a separately inventoried
    # target so their exact capsule manifests/locks are visible to library analysis.
    expanded = private / 'expanded-npm'
    expanded.mkdir(mode=0o700)
    npm_inputs = []
    catalog = json.loads((package / 'artifact-catalog.json').read_text())
    for artifact in catalog['artifacts']:
        if artifact['type'] != 'npm':
            continue
        source = (package / artifact['localPath']).resolve()
        if not source.is_relative_to(package) or not source.is_file():
            raise RuntimeError('invalid packaged npm artifact')
        sha = digest(source)
        if 'sha256:' + sha != artifact['digest']:
            raise RuntimeError('packaged npm artifact digest differs')
        destination = expanded / hashlib.sha256(artifact['id'].encode()).hexdigest()[:16]
        destination.mkdir(mode=0o700)
        with tarfile.open(source) as snapshot:
            if any(not (member.isfile() or member.isdir()) for member in snapshot.getmembers()):
                raise RuntimeError('npm artifact has a nonregular entry')
            snapshot.extractall(destination, filter='data')
        npm_inputs.append({'id': artifact['id'], 'path': artifact['localPath'], 'sha256': sha,
                           'expandedDirectory': destination.name})
    if not npm_inputs or not list(expanded.rglob('pnpm-lock.yaml')):
        raise RuntimeError('delivered npm capsule/lock inventory missing')
    (output / 'expanded-npm-inventory.json').write_text(json.dumps(npm_inputs, indent=2))
    targets = [('native', ['rootfs', str(package)]), ('npm-capsules', ['fs', str(expanded)]),
               ('oci', ['image', '--input', str(image)])]
    findings, blockers = [], []
    for label, command in targets:
        raw = private / (label + '-scan.json')
        run(command + common + ['--scanners', 'vuln,secret,misconfig', '--format', 'json',
                                '--exit-code', '0', '--list-all-pkgs', '--output', str(raw)], label + '-scan')
        report = json.loads(raw.read_text())
        sbom = output / (label + '.cdx.json')
        run(command + common + ['--scanners', 'vuln', '--format', 'cyclonedx',
                                '--output', str(sbom)], label + '-sbom')
        components = json.loads(sbom.read_text()).get('components', [])
        if not components:
            raise RuntimeError(f'{label} SBOM has no components')
        for result in report.get('Results') or []:
            for vulnerability in result.get('Vulnerabilities') or []:
                row = {'target': label, 'file': result.get('Target'), 'kind': 'vulnerability',
                       **{key: vulnerability.get(key) for key in ['VulnerabilityID', 'PkgName',
                           'InstalledVersion', 'FixedVersion', 'Severity', 'PrimaryURL', 'Status']}}
                findings.append(row)
                if row['Severity'] in ('HIGH', 'CRITICAL'):
                    blockers.append(row)
            for secret in result.get('Secrets') or []:
                row = {'target': label, 'file': result.get('Target'), 'kind': 'secret',
                       **{key: secret.get(key) for key in ['RuleID', 'Category', 'Severity', 'StartLine', 'EndLine']}}
                findings.append(row)
                blockers.append(row)
            for misconfig in result.get('Misconfigurations') or []:
                row = {'target': label, 'file': result.get('Target'), 'kind': 'misconfiguration',
                       **{key: misconfig.get(key) for key in ['ID', 'Title', 'Severity', 'Status']}}
                findings.append(row)
                if row['Severity'] in ('HIGH', 'CRITICAL') and row['Status'] == 'FAIL':
                    blockers.append(row)
        print(f'SCANNED {label}: {len(components)} SBOM components', flush=True)
    db_inventory = [{'path': str(file.relative_to(cache)), 'bytes': file.stat().st_size,
                     'sha256': digest(file)} for file in sorted(cache.rglob('*'))
                    if file.is_file() and (file.suffix == '.db' or file.name == 'metadata.json')]
    if not db_inventory:
        raise RuntimeError('vulnerability DB identity missing')
    report = {'status': 'failed' if blockers else 'passed', 'source': receipt['source'],
              'image': receipt['image'], 'scanner': {'version': VERSION, 'archiveSha256': ARCHIVE_SHA,
              'binarySha256': digest(executable)}, 'databases': db_inventory, 'findings': findings,
              'blockers': blockers, 'jarLibraryCount': len(libraries),
              'claim': 'actual native files and OCI image only; not a penetration-test certification'}
    (output / 'result.json').write_text(json.dumps(report, indent=2))
    return 1 if blockers else 0


if __name__ == '__main__':
    sys.exit(main())
