"""Run the documented Compose project example in one admitted, isolated Linux attempt.

Uses the public initialization script and existing browser journeys. Retains volumes,
files and failed attempts; never publishes images or removes another project.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request


def require(value, message):
    if not value:
        raise RuntimeError(message)


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
               DELIVERY_EVIDENCE_ROOT=str(output), ADMIN_EMAIL='admin@auraboot.com',
               ADMIN_PASSWORD='Test2026x', AURA_E2E_VIDEO='off', AURA_E2E_BROWSER_CHANNEL='chromium', NO_PROXY='localhost,127.0.0.1')
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
            rows = [json.loads(line) for line in state.splitlines() if line.strip()]
            backend = [row for row in rows if row.get('Service') == 'backend']
            if len(backend) == 1 and backend[0].get('Health') == 'healthy':
                with urllib.request.urlopen(env['BACKEND_URL'] + '/login', timeout=15) as response:
                    require(response.status == 200, 'Public login page did not load')
                return
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

    existing = subprocess.check_output(compose + ['ps', '-aq'], cwd=root, env=env, text=True).strip()
    require(not existing, 'Attempt already has Compose resources; no overwrite or implicit retry')
    require(not (root / '.env').exists(), 'Public checkout must not inherit another .env')
    shutil.copyfile(root / '.env.example', root / '.env')
    (root / '.env').chmod(0o600)
    run(['python3', 'scripts/prepare-quickstart-env.py', '--file', '.env'], 'prepare-env')
    started = False
    try:
        started = True
        run(compose + ['up', '--build', '-d'], 'compose-build-start', timeout=2400)
        ready()
        check('compose-public-entry', source=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(), project=project)
        run(['bash', 'scripts/project-delivery-demo.sh'], 'public-project-initialize')
        fixture = json.loads((output / 'fixture.json').read_text())
        require(len(fixture['projects']) == 8, 'Public initialization must create all eight sample projects')
        shutil.copyfile(root / 'plugins/project-management/scripts/acceptance-matrix.json', output / 'acceptance-matrix.json')
        check('public-initialization', projects=8, fixture='fixture.json')
        run(['pnpm', 'exec', 'playwright', 'install', 'chromium'], 'browser-install', cwd=root / 'web-admin')
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
        (output / 'result.json').write_text(json.dumps(dict(status='passed', releaseQualification=False,
             checks=checks, recordings=False, scope='Documented public Compose + project sample; no Enterprise or AI claims'), indent=2))
    finally:
        if started:
            run(compose + ['stop'], 'retain-stopped-project')


if __name__ == '__main__':
    main()
