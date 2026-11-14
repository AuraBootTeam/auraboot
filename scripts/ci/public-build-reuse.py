"""Consume an unchanged, previously source-built public image set; never reuse data."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ALLOWED_DRIVER_CHANGES = {
    'scripts/ci/verify-public-project-install.py',
    'scripts/ci/public-build-reuse.py',
    'scripts/ci/public-build-reuse.test.py',
    'web-admin/tests/e2e/project-management/delivery-smoke.spec.ts',
}


def need(value, message):
    if not value:
        raise RuntimeError(message)


def validate(receipt, current_tree, producer_tree, checks, containers, images):
    need(receipt.get('format') == 'auraboot.public-source-build.v1', 'Unknown build receipt')
    need(re.fullmatch(r'[a-f0-9]{40}', receipt.get('sourceCommit', '')), 'Exact producer source required')
    project = receipt.get('project', '')
    need(re.fullmatch(r'aura-public-[a-f0-9]{16}', project), 'Owned public project required')
    need(any(c.get('id') == 'compose-public-entry' and c.get('status') == 'passed'
             and c.get('source') == receipt['sourceCommit'] and c.get('project') == project for c in checks),
         'Actual source build/start receipt required')
    need(any(c.get('id') == 'public-initialization' and c.get('status') == 'passed'
             and c.get('projects') == 8 and c.get('hostNodeDeniedDuringBootstrap') is True for c in checks),
         'Actual public initialization receipt required')
    changed = sorted(p for p in current_tree.keys() | producer_tree.keys()
                     if current_tree.get(p) != producer_tree.get(p))
    need(set(changed) <= ALLOWED_DRIVER_CHANGES, 'Production build inputs changed')
    need(set(receipt.get('images', {})) == {'backend', 'frontend', 'postgres'}, 'Complete image denominator required')
    for service, image in receipt['images'].items():
        need(re.fullmatch(r'sha256:[a-f0-9]{64}', image), 'Immutable image ID required')
        container = containers[service]
        labels = container.get('Config', {}).get('Labels', {})
        need(container.get('Image') == image
             and labels.get('com.docker.compose.project') == project
             and labels.get('com.docker.compose.service') == service
             and labels.get('com.docker.compose.project.working_dir') == receipt['sourceRoot']
             and container.get('State', {}).get('Running') is False, 'Producer container identity/state differs')
        metadata = images[service]
        need(metadata.get('Id') == image and metadata.get('Os') == 'linux'
             and metadata.get('Architecture') == 'amd64', 'Actual immutable Linux amd64 image required')
    return dict(producerSource=receipt['sourceCommit'], producerProject=project,
                changedDriverFiles=changed, images=receipt['images'],
                sourceBuildExecutedThisRound=False, productionInputsUnchanged=True,
                businessDataReused=False, scope='Reuse actual source-build stage only; run fresh initialization and every original browser/reopen assertion')


def load(root, filename, expected):
    path = Path(filename)
    need(not path.is_symlink() and path.is_file() and path.stat().st_uid == 0
         and path.stat().st_mode & 0o022 == 0, 'Protected root-reviewed build receipt required')
    need(path.resolve().is_relative_to('/opt/aura-ci/jenkins-inputs'), 'Registered input root required')
    raw = path.read_bytes()
    need(re.fullmatch(r'[a-f0-9]{64}', expected or '')
         and hashlib.sha256(raw).hexdigest() == expected, 'Build receipt checksum differs')
    receipt = json.loads(raw)
    producer = Path(receipt['sourceRoot']).resolve(strict=True)
    evidence = Path(receipt['evidenceRoot']).resolve(strict=True)
    need(producer.is_relative_to('/opt/aura-ci/jenkins-workspaces')
         and evidence.is_relative_to('/opt/aura-ci/jenkins-evidence'), 'Registered producer roots required')
    def git(repo, *args):
        return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
    for repo in [root, producer]:
        need(not git(repo, 'status', '--porcelain', '--untracked-files=no'), 'Clean tracked source required')
    need(git(producer, 'rev-parse', 'HEAD') == receipt['sourceCommit'], 'Producer source differs')
    checks_file = evidence / 'checks.json'
    need(not checks_file.is_symlink() and hashlib.sha256(checks_file.read_bytes()).hexdigest()
         == receipt['checksSha256'], 'Producer stage evidence differs')
    def tree(repo):
        return {r.split('\t', 1)[1]: r.split('\t', 1)[0]
                for r in git(repo, 'ls-tree', '-rz', '--full-tree', 'HEAD').split('\0') if r}
    containers = {}; images = {}
    for service in ['backend', 'frontend', 'postgres']:
        containers[service] = json.loads(subprocess.check_output(['docker', 'inspect', receipt['project']+'-'+service], text=True))[0]
        images[service] = json.loads(subprocess.check_output(['docker', 'image', 'inspect', receipt['images'][service]], text=True))[0]
    result = validate(receipt, tree(root), tree(producer), json.loads(checks_file.read_text()), containers, images)
    result.update(consumerVerifierSource=git(root, 'rev-parse', 'HEAD'), producerEvidence=str(evidence), receiptSha256=expected)
    # Retain the original resource/cache observation as historical provenance,
    # never as a new builder measurement in this consuming round.
    for name in ['builder.json', 'gradle-cache.json']:
        value = json.loads((evidence/name).read_text()); value['reusedFromProducer'] = str(evidence)
        result[name] = value
    return result
