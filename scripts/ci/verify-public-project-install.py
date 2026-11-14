"""Run the documented Compose project example in one admitted, isolated Linux attempt.

Uses the public initialization script and existing browser journeys. Retains volumes,
files and failed attempts; never publishes images or removes another project.
"""
import hashlib
import importlib.util
import json
import os
import re
from urllib.parse import urlsplit
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request
import urllib.error


def require(value, message):
    if not value:
        raise RuntimeError(message)


def compose_rows(value):
    """Compose versions emit either an array or one JSON object per line."""
    if value.lstrip().startswith('['):
        rows = json.loads(value)
    else:
        rows = [json.loads(line) for line in value.splitlines() if line.strip()]
    require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
            'Invalid Compose service state')
    return rows


def gradle_cache_path(url):
    # Gradle PathAssembler hashes the unchanged distribution URL as unsigned base36 MD5.
    number = int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest(), 'big')
    digits = ''
    while number:
        number, remainder = divmod(number, 36)
        digits = '0123456789abcdefghijklmnopqrstuvwxyz'[remainder] + digits
    return Path('dists/gradle-8.14.6-bin') / (digits or '0') / 'gradle-8.14.6-bin.zip'


def measured_builder_limits(host):
    memory, swap = host.get('Memory'), host.get('MemorySwap')
    require(type(memory) is int and 0 < memory <= 8589934592,
            'A bounded owned builder memory limit is required')
    require(type(swap) is int and swap == memory, 'Owned builder must not expand swap')
    return dict(requestedMemoryBytes=8589934592, actualMemoryBytes=memory,
                actualMemorySwapBytes=swap, composeParallelLimit=1,
                scope='Docker HostConfig observation; Compose limit is not a measured BuildKit concurrency count')



def measured_chrome_runtime(executable):
    """Use the CI-installed Chrome channel without an unsupported OS download."""
    binary = Path(executable).resolve(strict=True)
    require(binary.is_file() and os.access(binary, os.X_OK), 'Executable CI Chrome required')
    require(binary.stat().st_mode & 0o022 == 0, 'CI Chrome must not be group/world writable')
    version = subprocess.check_output([str(binary), '--version'], text=True,
                                     stderr=subprocess.DEVNULL, timeout=15).strip()
    require(re.fullmatch(r'Google Chrome [0-9]+(?:\.[0-9]+){3}(?: unknown)?', version),
            'Recognized CI Chrome version required')
    with binary.open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    return dict(channel='chrome', version=version, binary=str(binary), sha256=sha,
                scope='Observed CI-installed browser; no global install or browser download')


def main():
    require(os.uname().sysname == 'Linux', 'Linux CI only; no local container fallback')
    job = os.environ['AURA_CI_JOB_ID']
    slot = int(os.environ['AURA_REGRESSION_SLOT'])
    root = Path(__file__).resolve().parents[2]
    output = Path(os.environ['AURA_REGRESSION_ARTIFACTS']).resolve() / 'public-install'
    output.mkdir(mode=0o700)
    private = output / 'private'
    private.mkdir(mode=0o700)
    project = 'aura-public-' + hashlib.sha256(job.encode()).hexdigest()[:16]
    web_port, db_port = 15100 + slot, 15400 + slot
    env = dict(os.environ, COMPOSE_PROJECT_NAME=project, AURABOOT_PORT=str(web_port),
               POSTGRES_PORT=str(db_port), BACKEND_URL=f'http://127.0.0.1:{web_port}',
               DELIVERY_BACKEND_URL=f'http://127.0.0.1:{web_port}',
               DELIVERY_WEB_URL=f'http://127.0.0.1:{web_port}',
               AURABOOT_BIND_ADDRESS='127.0.0.1', POSTGRES_BIND_ADDRESS='127.0.0.1',
               DELIVERY_EVIDENCE_ROOT=str(output), ADMIN_EMAIL='admin@auraboot.com',
               ADMIN_PASSWORD='Test2026x', AURA_E2E_VIDEO='off', AURA_E2E_BROWSER_CHANNEL='chrome', COMPOSE_PARALLEL_LIMIT='1', NO_PROXY='localhost,127.0.0.1')
    for key in ['JWT_SECRET', 'SESSION_SECRET', 'POSTGRES_PASSWORD', 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY',
                'FIELD_ENCRYPTION_KEY', 'POSTGRES_USER', 'POSTGRES_DB']:
        env.pop(key, None)
    browser_runtime = measured_chrome_runtime('/opt/google/chrome/chrome')
    (output / 'browser-runtime.json').write_text(json.dumps(browser_runtime, indent=2))
    for port in [web_port, db_port]:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    compose = ['docker', 'compose', '--project-name', project, '--profile', 'full']
    checks = []

    def run(args, name, timeout=900, cwd=None):
        with (private / (name + '.log')).open('xb') as log:
            result = subprocess.run(args, cwd=cwd or root, env=env, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        require(result.returncode == 0, name + ' failed; inspect retained private log')

    def check(name, **facts):
        checks.append(dict(id=name, status='passed', **facts))
        (output / 'checks.json').write_text(json.dumps(checks, indent=2))

    def ready():
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            state = subprocess.check_output(compose + ['ps', '--format', 'json'], cwd=root, env=env, text=True)
            rows = compose_rows(state)
            backend = [row for row in rows if row.get('Service') == 'backend']
            if len(backend) == 1 and backend[0].get('Health') == 'healthy':
                try:
                    with urllib.request.urlopen(env['BACKEND_URL'] + '/login', timeout=15) as response:
                        require(response.status == 200, 'Public login page did not load')
                    return
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    pass  # The BFF may still be starting; retain the same overall deadline.
            time.sleep(3)
        raise RuntimeError('Public Compose backend did not become healthy')

    def snapshot():
        spec = importlib.util.spec_from_file_location('public_admin_session', root / 'scripts/application/core-admin-session.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def request(path, method='GET', body=None, headers=None):
            data = None if body is None else json.dumps(body).encode()
            req = urllib.request.Request(env['BACKEND_URL'] + path, data=data, method=method,
                                         headers={'Content-Type': 'application/json', **(headers or {})})
            with urllib.request.urlopen(req, timeout=60) as response:
                return json.load(response)
        token = module.business_session(request, env['ADMIN_EMAIL'], env['ADMIN_PASSWORD'])
        response = request('/api/dynamic/tpm_project/list', headers={'Authorization': 'Bearer ' + token})
        require(str(response['code']) == '0', 'Project read after restart failed')
        return response['data']['records']

    freeze_command = ['node', str(Path(os.environ['AURA_CI_WORKSPACE_ROOT']) /
        'auraboot-workspace/tools/local-ci/server.mjs'), 'verify-freeze',
        '--context', os.environ['AURA_CI_FROZEN_CONTEXT'],
        '--expected-source-set', os.environ['AURA_CI_SOURCE_SET_DIGEST'],
        '--workspace-root', os.environ['AURA_CI_WORKSPACE_ROOT'], '--job', job]
    run(freeze_command, 'frozen-before')

    reused_build = None
    reuse_file = os.environ.get('AURA_PUBLIC_REUSE_BUILD_RECEIPT')
    if reuse_file:
        spec = importlib.util.spec_from_file_location('public_build_reuse', Path(__file__).with_name('public-build-reuse.py'))
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        reused_build = module.load(root, reuse_file, os.environ.get('AURA_PUBLIC_REUSE_BUILD_SHA256'))

    # The original generic wrapper caps BuildKit at 2GiB. Source-building the
    # documented full application needs its own bounded builder, not the shared default.
    image = os.environ['AURA_PUBLIC_BUILDKIT_IMAGE']
    require(re.fullmatch(r'docker.io/moby/buildkit@sha256:[a-f0-9]{64}', image), 'Exact official BuildKit image required')
    builder = project + '-build'
    buildx = private / 'buildx'
    buildx.mkdir(mode=0o700)
    env.update(BUILDX_CONFIG=str(buildx), BUILDX_BUILDER=builder)
    mirrors = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .RegistryConfig.Mirrors}}'], text=True))
    require(isinstance(mirrors, list), 'Daemon mirror list required')
    for mirror in mirrors:
        parsed = urlsplit(mirror)
        require(parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password
                and not parsed.query and not parsed.fragment, 'Only public HTTPS mirrors allowed')
    config = private / 'buildkitd.toml'
    config.write_text('[registry."docker.io"]\nmirrors = ' + json.dumps([m.removeprefix('https://').rstrip('/') for m in mirrors]) + '\n')
    config.chmod(0o400)
    existing = subprocess.check_output(compose + ['ps', '-aq'], cwd=root, env=env, text=True).strip()
    require(not existing, 'Attempt already has Compose resources; no overwrite or implicit retry')
    require(not (root / '.env').exists(), 'Public checkout must not inherit another .env')
    run(['python3', 'scripts/prepare-quickstart-env.py', '--file', '.env', '--new-local-evaluation'], 'prepare-env')
    values = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines()
                  if line and not line.lstrip().startswith('#') and '=' in line)
    keys = ['JWT_SECRET', 'SESSION_SECRET', 'POSTGRES_PASSWORD', 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY']
    require(len({values[key] for key in keys}) == 4 and all(len(values[key]) >= 32 for key in keys),
            'Independent fresh evaluation secrets required')
    require((root / '.env').stat().st_mode & 0o777 == 0o600, 'Private environment mode required')
    (output / 'preparation.json').write_text(json.dumps(dict(independentSecrets=True,
         checkedKeys=keys, secretValuesExported=False, environmentMode='0600',
         bindAddresses=[values['AURABOOT_BIND_ADDRESS'], values['POSTGRES_BIND_ADDRESS']]), indent=2))
    started = False
    builder_created = False
    try:
        if reused_build is None:
            builder_created = True
            run(['docker', 'buildx', 'create', '--name', builder, '--driver', 'docker-container',
                 '--driver-opt', 'image=' + image, '--driver-opt', 'memory=8g', '--driver-opt', 'memory-swap=8g',
                 '--buildkitd-config', str(config), '--bootstrap'], 'owned-builder-bootstrap')
            host = json.loads(subprocess.check_output(['docker', 'inspect', '--format', '{{json .HostConfig}}',
                 'buildx_buildkit_' + builder + '0'], text=True))
            (output / 'builder.json').write_text(json.dumps(dict(name=builder, image=image,
                 **measured_builder_limits(host), sharedDefaultModified=False,
                 configSha256=hashlib.sha256(config.read_bytes()).hexdigest()), indent=2))
            # An optional admitted, official-checksum-verified distribution warms only
            # this owned builder. The public Dockerfile and wrapper URL remain unchanged.
            dataset_file = os.environ.get('AURA_CI_DATASET_INPUTS')
            inputs = json.loads(Path(dataset_file).read_text())['files'] if dataset_file else []
            distributions = [item for item in inputs if item['id'] == 'public-gradle-8-14-6']
            if distributions:
                require(len(distributions) == 1, 'One Gradle distribution input required')
                item = distributions[0]
                archive = Path(item['file'])
                expected = '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24'
                require(archive.is_file() and not archive.is_symlink(), 'Regular Gradle archive required')
                with archive.open('rb') as stream:
                    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
                require(actual == item['sha256'] == expected, 'Official Gradle archive checksum mismatch')
                properties = (root / 'platform/gradle/wrapper/gradle-wrapper.properties').read_text()
                url = 'https://services.gradle.org/distributions/gradle-8.14.6-bin.zip'
                require('distributionUrl=' + url.replace(':', chr(92) + ':') in properties,
                        'Distribution prewarm must match the unchanged public wrapper URL')
                context = private / 'gradle-seed'
                cached = context / 'seed' / gradle_cache_path(url)
                cached.parent.mkdir(parents=True)
                shutil.copyfile(archive, cached)
                (context / 'Dockerfile').write_text(
                    'FROM docker.io/library/busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec\nCOPY seed /seed\n'
                    'RUN --mount=type=cache,target=/root/.gradle/wrapper cp -a /seed/. /root/.gradle/wrapper/\n')
                run(['docker', 'buildx', 'build', '--builder', builder, '--output', 'type=cacheonly',
                     str(context)], 'verified-gradle-cache-prewarm', timeout=300)
                (output / 'gradle-cache.json').write_text(json.dumps(dict(sha256=actual,
                     officialChecksumUrl='https://services.gradle.org/distributions/gradle-8.14.6-bin.zip.sha256',
                     wrapperUrl=url, sourcePropertiesModified=False, scope='owned builder cache only'), indent=2))
            started = True
            run(compose + ['up', '--build', '-d'], 'compose-build-start', timeout=2400)
        else:
            override = private / "consume-compose.json"
            override.write_text(json.dumps({"services": {service: {"image": image} for service, image in reused_build["images"].items()}}))
            compose[:] = ["docker", "compose", "-f", str(root / "docker-compose.yml"), "-f", str(override), "--project-name", project, "--profile", "full"]
            for name in ["builder.json", "gradle-cache.json"]:
                (output / name).write_text(json.dumps(reused_build[name], indent=2))
            (output / "build-reuse.json").write_text(json.dumps(reused_build, indent=2))
            check("source-build-evidence-reused", sourceBuildExecutedThisRound=False, producerSource=reused_build["producerSource"])
            started = True
            run(compose + ["up", "--no-build", "--pull", "never", "-d"], "compose-consume-build-start", timeout=2400)
        ready()
        published = {}
        for service, port, internal in [('frontend', web_port, '3000/tcp'), ('postgres', db_port, '5432/tcp')]:
            bindings = json.loads(subprocess.check_output(['docker', 'inspect', '--format',
                '{{json .HostConfig.PortBindings}}', project + '-' + service], text=True))
            rows = bindings.get(internal, [])
            require(rows == [{'HostIp':'127.0.0.1', 'HostPort':str(port)}], 'Actual published interface must be loopback')
            published[service] = rows
        (output / 'published-ports.json').write_text(json.dumps(published, indent=2))
        check('compose-public-entry', source=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(), project=project, sourceBuildExecutedThisRound=reused_build is None, producerSource=reused_build['producerSource'] if reused_build else None)
        deny = private / 'no-host-node'
        deny.mkdir(mode=0o700)
        for tool in ['node', 'npm', 'pnpm']:
            file = deny / tool
            file.write_text('#!/bin/sh\necho "Unexpected host JavaScript dependency during public bootstrap" >&2\nexit 97\n')
            file.chmod(0o700)
        run(['env', 'PATH=' + str(deny) + ':' + env['PATH'], 'bash', 'scripts/project-delivery-demo.sh'],
            'public-project-initialize')
        fixture = json.loads((output / 'fixture.json').read_text())
        require(len(fixture['projects']) == 8, 'Public initialization must create all eight sample projects')
        shutil.copyfile(root / 'plugins/project-management/scripts/acceptance-matrix.json', output / 'acceptance-matrix.json')
        check('public-initialization', projects=8, fixture='fixture.json', hostNodeDeniedDuringBootstrap=True)
        run(['pnpm', 'install', '--frozen-lockfile', '--ignore-scripts'], 'host-browser-dependencies')
        require(measured_chrome_runtime(browser_runtime['binary']) == browser_runtime,
                'CI browser identity changed during installation')
        with (private / 'public-browser.log').open('xb') as log:
            result = subprocess.run(['pnpm', 'exec', 'playwright', 'test', '--config', 'playwright.delivery.config.ts'],
                                    cwd=root / 'web-admin', env=env, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        require(result.returncode == 0, 'Public project browser journeys failed')
        stats = json.loads((output / 'browser-results.json').read_text())['stats']
        require(stats['expected'] == 3 and stats['unexpected'] == 0 and stats['skipped'] == 0
                and stats.get('flaky', 0) == 0, 'Three actual browser journeys required without skips or flaky passes')
        check('public-browser', executed=3, passed=3, skipped=0, retries=0)
        before = snapshot()
        require(len(before) == 9, 'Browser must create the ninth project')
        run(compose + ['stop'], 'documented-stop')
        run(compose + ['up', '-d'], 'documented-reopen')
        ready()
        after = snapshot()
        normalize = lambda rows: sorted(rows, key=lambda row: json.dumps(row, sort_keys=True))
        require(normalize(before) == normalize(after), 'Business records changed after documented stop/reopen')
        check('documented-stop-reopen', projects=9, recordsUnchanged=True)
        run(freeze_command, 'frozen-after')
        require((private / 'frozen-before.log').read_bytes() == (private / 'frozen-after.log').read_bytes(),
                'Frozen public source identity changed')
        (output / 'result.json').write_text(json.dumps(dict(status='passed', releaseQualification=False,
             checks=checks, recordings=False, sourceBuildExecutedThisRound=reused_build is None, scope='Documented public Compose + project sample; source-build provenance is explicit; no Enterprise or AI claims'), indent=2))
    finally:
        if started:
            run(compose + ['stop'], 'retain-stopped-project')
        if builder_created:
            run(['docker', 'buildx', 'rm', builder], 'owned-builder-remove')


if __name__ == '__main__':
    main()
