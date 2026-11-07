"""Verify unpublished Core packages against an attempt-owned Linux database.

Uses the packaged lifecycle, native artifact consumer and real HTTP/SQL paths.
It does not publish an image, reset shared infrastructure or qualify a release.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    current, previous, output, candidate_archive = map(lambda item: Path(item).resolve(), sys.argv[1:])
    output.mkdir(mode=0o700)
    private = output / 'private'
    private.mkdir(mode=0o700)
    root = Path(__file__).resolve().parents[2]
    job = os.environ['AURA_CI_JOB_ID']
    slot = int(os.environ['AURA_REGRESSION_SLOT'])
    require(os.uname().sysname == 'Linux', 'native Linux verification required')
    backend_port, web_port = 6400 + slot, 5100 + slot
    base = f'http://127.0.0.1:{backend_port}'
    container = 'aura-oss-trial-' + hashlib.sha256(job.encode()).hexdigest()[:16]
    pg_image = 'pgvector/pgvector@sha256:7b822b0aac60967beb1ea5e576b8602c94c300a157d187f385ae3e0da199b90a'
    lifecycle = current / 'bin/auraboot-core-env.sh'
    spec = importlib.util.spec_from_file_location('native_admin_session', current / 'bin/application/core-admin-session.py')
    session_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(session_module)
    password = 'Trial-' + secrets.token_hex(16)
    env = dict(os.environ, PGPASSWORD=secrets.token_hex(24), PGUSER='auraboot', PGHOST='127.0.0.1',
               AURA_BACKEND_PORT=str(backend_port), AURA_WEB_PORT=str(web_port),
               ADMIN_EMAIL='admin@auraboot.local', ADMIN_PASSWORD=password,
               SESSION_SECRET=secrets.token_hex(32), JWT_SECRET=secrets.token_hex(32),
               OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=secrets.token_hex(32),
               FIELD_ENCRYPTION_KEY=__import__('base64').b64encode(secrets.token_bytes(32)).decode())
    # Secret-bearing state remains outside the exported candidate and review index.
    secret_file = private / 'verification-environment.json'
    secret_file.write_text(json.dumps({key: env[key] for key in ['PGPASSWORD', 'ADMIN_PASSWORD', 'SESSION_SECRET', 'JWT_SECRET', 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY', 'FIELD_ENCRYPTION_KEY']}))
    secret_file.chmod(0o600)
    checks, active, pg_started, image_started = [], None, False, False
    image_container = container + "-runtime"
    def run(args, name, settings=None, accepted=0):
        with (private / (name + '.log')).open('ab') as log:
            result = subprocess.run(list(map(str, args)), env=settings or env, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        require(result.returncode == accepted, f'{name} failed with exit {result.returncode}; private log retained')
    def cli(action, package, database):
        settings = dict(env, AURA_APP_ARTIFACT_ROOT=str(package), PGDATABASE=database,
                        AURA_STATE_ROOT=str(private / ('state-' + database)),
                        AURA_FILE_STORAGE_PATH=str(private / 'persistent-files'))
        run(['bash', lifecycle, action], database + '-' + package.name + '-' + action, settings)
    def request(path, method='GET', body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        merged = {'Content-Type': 'application/json', **(headers or {})}
        req = urllib.request.Request(base + path, data=data, headers=merged, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            payload = json.load(error)
            return dict(payload, _httpStatus=error.code)
    def login(email=None, secret=None):
        return session_module.business_session(request, email or env['ADMIN_EMAIL'], secret or password)
    def api(token, path, method='GET', body=None):
        response = request(path, method, body, {'Authorization': 'Bearer ' + token})
        require(str(response.get('code')) == '0', f'{method} {path}: {response.get("code")} {response.get("message")}')
        return response.get('data')
    def start(package, database, initialize=False):
        nonlocal active
        cli('create-db', package, database)
        cli('migrate', package, database)
        active = (package, database)
        cli('start', package, database)
        if initialize == 'multi':
            result = request('/api/bootstrap/setup', 'POST', {
                'companyName': 'Native security tenant A', 'adminEmail': env['ADMIN_EMAIL'],
                'adminPassword': password, 'adminDisplayName': 'Security administrator', 'systemMode': 'multi'})
            require(str(result.get('code')) == '0', 'security MULTI bootstrap failed')
        elif initialize:
            cli('init-core', package, database)
        cli('publish', package, database)
        cli('verify', package, database)
    def stop():
        nonlocal active
        if active:
            cli('stop', *active)
            active = None
    def check(name, **facts):
        checks.append(dict(id=name, status='passed', **facts))
        (output / 'checks.json').write_text(json.dumps(checks, indent=2))
        print('PASS ' + name, flush=True)
    def record_probe(token, code, record_pid, amount):
        row = api(token, f'/api/dynamic/{code}/{record_pid}')
        require(row[code + '_amount'] == amount and row[code + '_customer'] == 'Huachen Manufacturing', 'business record differs')
        return row
    try:
        # --pull never pins an already available, digest-identified prerequisite.
        require(subprocess.run(['docker', 'container', 'inspect', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0, 'attempt container already exists')
        run(['docker', 'run', '--pull', 'never', '-d', '--name', container, '--label', 'aura.ci.job=' + job,
             '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=auraboot', '-e', 'POSTGRES_PASSWORD=' + env['PGPASSWORD'],
             '-e', 'POSTGRES_DB=postgres', pg_image], 'postgres-start')
        pg_started = True
        for _ in range(120):
            logs = subprocess.check_output(['docker', 'logs', container], stderr=subprocess.STDOUT).decode()
            ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'auraboot', '-d', 'postgres'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            if ready and 'PostgreSQL init process complete; ready for start up.' in logs:
                break
            time.sleep(1)
        else:
            raise RuntimeError('owned PostgreSQL did not become ready')
        port = subprocess.check_output(['docker', 'port', container, '5432/tcp'], text=True).strip().split(':')[-1]
        env['PGPORT'] = port
        start(current, 'aura_candidate_security', 'multi')
        security_spec = importlib.util.spec_from_file_location('native_security_probes', root / 'scripts/ci/oss-security-probes.py')
        security_module = importlib.util.module_from_spec(security_spec)
        security_spec.loader.exec_module(security_module)
        def publish_security_tenant(token):
            # Mirror the packaged publisher for the second tenant as well. Tenant
            # creation initializes membership/RBAC, not this Core-only config set.
            owners = subprocess.check_output(['node', current / 'bin/application/native-config-imports.mjs',
                                             current / 'application.lock'], text=True).split()
            require(bool(owners), 'security tenant configuration denominator is empty')
            for owner in owners:
                require('/' not in owner and '..' not in owner, 'invalid locked config owner')
                # This endpoint returns ImportExecuteResult directly, rather than
                # the ApiResponse envelope used by business APIs above.
                imported = request('/api/plugins/import/import-directory-sync', 'POST', {
                    'path': str(current / 'config' / owner), 'conflictStrategy': 'OVERWRITE',
                    'autoPublishModels': True, 'autoPublishFields': True, 'autoPublishCommands': True,
                    'autoPublishPages': True, 'deferReferenceValidation': True},
                    {'Authorization': 'Bearer ' + token})
                require(imported.get('_httpStatus', 200) == 200 and imported.get('success') is True,
                        'security tenant import failed for locked configuration owner ' + owner)
        security_module.verify(base, request, login, login(), password, check, require, publish_security_tenant,
                               f'http://127.0.0.1:{web_port}')
        stop()
        start(current, 'aura_candidate_cold', True)
        check('cold-install-initialize-publish', packageSource=json.loads((current / 'release-receipt.json').read_text())['source']['commit'], initialized=True)
        # Reuse the existing first-user browser case against the installed package.
        # Its denominator includes Schema refinement, persistence and a real read-only user.
        browser_output = output / 'onboarding'
        browser_output.mkdir()
        auth_script = private / 'authenticate.mts'
        auth_script.write_text("import {createRequire} from 'node:module';\n"
            + "const require=createRequire(" + json.dumps(str(root / 'web-admin/package.json')) + ");\n"
            + "const {request}=require('@playwright/test');\n"
            + "const {authenticateBffSession}=await import(" + json.dumps(str(root / 'web-admin/tests/helpers/bff-auth-session.ts')) + ");\n"
            + "const context=await request.newContext();try{await authenticateBffSession(context,process.env.PLAYWRIGHT_BASE_URL,process.env.ADMIN_EMAIL,process.env.ADMIN_PASSWORD);await context.storageState({path:process.env.PW_ADMIN_STORAGE_STATE});}finally{await context.dispose();}\n")
        browser_env = dict(env, PLAYWRIGHT_BASE_URL=f'http://127.0.0.1:{web_port}', BACKEND_URL=base,
            BE_PORT=str(backend_port), BFF_PORT=str(web_port), VITE_PORT=str(web_port), PG_DB='aura_candidate_cold',
            PW_SKIP_WEBSERVER='1', PW_PROFILE='full', PW_WORKERS='1', CI='1',
            PW_ADMIN_STORAGE_STATE=str(private / 'admin.json'), PW_ARTIFACT_DIR=str(browser_output / 'artifacts'),
            PLAYWRIGHT_JSON_OUTPUT_NAME=str(browser_output / 'results.json'))
        run(['pnpm', '--dir', root / 'web-admin', 'exec', 'tsx', auth_script], 'browser-authenticate', browser_env)
        run(['pnpm', '--dir', root / 'web-admin', 'exec', 'playwright', 'test',
             'tests/e2e/showcase/customer-designers-journey.spec.ts', '--grep', 'SC02:',
             '--project', 'chromium', '--no-deps', '--reporter=line,json'], 'browser-onboarding', browser_env)
        browser_results = json.loads((browser_output / 'results.json').read_text())['stats']
        require(browser_results['expected'] == 1 and browser_results['unexpected'] == 0
                and browser_results['skipped'] == 0 and browser_results['flaky'] == 0, 'first-user browser denominator differs')
        check('installed-package-model-crud-schema-runtime-readonly', expected=1, passed=1, skipped=0, retries=0)
        stop()
        start(previous, 'aura_candidate_upgrade', True)
        token = login()
        code = 'trial_native_followup'
        model = api(token, '/api/meta/models', 'POST', {
            'code': code, 'tableName': code, 'displayName': 'Native trial follow-up', 'modelType': 'entity', 'sourceType': 'physical',
            'capabilities': {key: True for key in ['list', 'detail', 'create', 'update', 'delete', 'filter', 'sort', 'paginate']},
            'fields': [{'code': code + '_customer', 'columnName': code + '_customer', 'displayName': 'Customer', 'dataType': 'string', 'required': True, 'editable': True},
                       {'code': code + '_amount', 'columnName': code + '_amount', 'displayName': 'Amount', 'dataType': 'integer', 'editable': True}]})
        api(token, f'/api/meta/models/{model["pid"]}/publish', 'POST', {'impactAcknowledged': True})
        api(token, '/api/templates/crud/generate', 'POST', {'modelCode': code, 'config': {'generateList': True, 'generateForm': True, 'generateDetail': True, 'createMenu': False, 'createPermissions': False, 'assignRoles': False}})
        command_code = 'trial:create_native_followup'
        command = api(token, '/api/meta/commands', 'POST', {
            'code': command_code, 'displayName': 'Create native follow-up', 'modelCode': code,
            'executionConfig': json.dumps({'type': 'create', 'inputFields': [code + '_customer', code + '_amount'],
                                           'permissions': ['model.' + code + '.create']})})
        api(token, '/api/meta/commands/' + command['pid'] + '/publish', 'POST')
        payload = {'payload': {code + '_customer': 'Huachen Manufacturing', code + '_amount': 68000},
                   'operationType': 'CREATE', 'clientRequestId': 'native-trial-' + secrets.token_hex(12)}
        execution = api(token, '/api/meta/commands/execute/' + command_code, 'POST', payload)
        require(execution['phaseReached'] == 'completed', 'command did not complete')
        pid = execution['data']['recordPid']
        replay = api(token, '/api/meta/commands/execute/' + command_code, 'POST', payload)
        require(replay['idempotentReplay'] is True and replay['data']['recordPid'] == pid, 'idempotent replay created a different record')
        record_probe(token, code, pid, 68000)
        attachment_bytes = b'Customer delivery acceptance material. Budget approved: 68000.\n'
        boundary = 'native-' + secrets.token_hex(12)
        multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="delivery-acceptance.txt"\r\nContent-Type: text/plain\r\n\r\n').encode() + attachment_bytes + ('\r\n--' + boundary + '--\r\n').encode()
        upload = urllib.request.Request(base + '/api/file/upload', data=multipart, method='POST',
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
        with urllib.request.urlopen(upload, timeout=60) as response:
            uploaded = json.load(response)
        require(str(uploaded.get('code')) == '0', 'attachment upload failed')
        file_id = uploaded['data']['fileId']
        def assert_attachment():
            download = urllib.request.Request(base + '/api/file/download/' + file_id, headers={'Authorization': 'Bearer ' + token})
            with urllib.request.urlopen(download, timeout=60) as response:
                require(response.read() == attachment_bytes, 'attachment bytes changed or missing')
        assert_attachment()
        (output / 'attachment.json').write_text(json.dumps({'fileId': file_id, 'name': 'delivery-acceptance.txt', 'sha256': hashlib.sha256(attachment_bytes).hexdigest()}))
        role_code = 'native_trial_reader'
        role = api(token, '/api/roles', 'POST', {'code': role_code, 'name': 'Native trial reader',
            'type': 'custom', 'status': 'active', 'scopeType': 'tenant', 'defaultDataScopeType': 'all'})
        permission_ids = {}
        def collect_permission(node):
            permission_ids[node['code']] = node['id']
            for child in node.get('children') or []:
                collect_permission(child)
        for node in api(token, '/api/permissions/tree'):
            collect_permission(node)
        api(token, '/api/permissions/matrix/' + role['pid'] + '/batch', 'PUT',
            [{'permissionId': permission_ids['model.' + code + '.read'], 'granted': True}])
        reader_email = 'native-reader@auraboot.local'
        api(token, '/api/admin/users', 'POST', {'email': reader_email, 'displayName': 'Native trial reader',
            'initialPassword': password, 'roleCodes': [role_code], 'sendInviteEmail': False})
        def assert_permissions_and_audit():
            reader = login(reader_email, password)
            record_probe(reader, code, pid, 68000)
            denied = request('/api/dynamic/' + code + '/' + pid, 'PUT', {code + '_amount': 123456},
                             {'Authorization': 'Bearer ' + reader})
            require(denied.get('_httpStatus') == 403 and 'model.' + code + '.update' in str(denied.get('context')), 'model permission denial was not enforced')
            denied_command = request('/api/meta/commands/execute/' + command_code, 'POST', payload,
                                     {'Authorization': 'Bearer ' + reader})
            require(denied_command.get('_httpStatus') == 403 and 'meta.command.execute' in str(denied_command.get('context')), 'command endpoint permission denial was not enforced')
            record_probe(token, code, pid, 68000)
            audit = api(token, '/api/meta/commands/audit-logs?commandCode=' + command_code)
            rows = audit['records']
            require(any(row.get('commandCode') == command_code and row.get('success') is True
                        for row in rows), 'successful command audit evidence missing')
        assert_permissions_and_audit()
        assert_attachment()
        check('previous-package-command-idempotency-permission-audit', recordPid=pid, amount=68000)

        stop()
        start(current, 'aura_candidate_upgrade')
        token = login()
        record_probe(token, code, pid, 68000)
        assert_permissions_and_audit()
        assert_attachment()
        check('old-to-new-upgrade-preserves-business', previousSource=json.loads((previous / 'release-receipt.json').read_text())['source']['commit'], currentSource=json.loads((current / 'release-receipt.json').read_text())['source']['commit'], amount=68000)
        dump = private / 'candidate-backup.dump'
        with dump.open('wb') as backup_stream, (private / 'backup.log').open('ab') as backup_log:
            backup = subprocess.run(['docker', 'exec', container, 'pg_dump', '-U', 'auraboot', '-Fc', '-d', 'aura_candidate_upgrade'], stdout=backup_stream, stderr=backup_log, timeout=900)
        require(backup.returncode == 0, 'source-major PostgreSQL backup failed')
        require(dump.stat().st_size > 0, 'empty backup rejected')
        files_backup = private / 'files-backup'
        shutil.copytree(private / 'persistent-files', files_backup)
        api(token, f'/api/dynamic/{code}/{pid}', 'PUT', {code + '_amount': 999999})
        record_probe(token, code, pid, 999999)
        stop()
        run(['createdb', '-h', env['PGHOST'], '-p', port, '-U', 'auraboot', 'aura_candidate_restore'], 'restore-create-empty-db')
        with dump.open('rb') as backup_stream, (private / 'restore.log').open('ab') as restore_log:
            restore = subprocess.run(['docker', 'exec', '-i', container, 'pg_restore', '--exit-on-error', '-U', 'auraboot', '-d', 'aura_candidate_restore'], stdin=backup_stream, stdout=restore_log, stderr=subprocess.STDOUT, timeout=900)
        require(restore.returncode == 0, 'source-major PostgreSQL restore failed')
        # Existing local metadata retains absolute paths. Preserve the configured
        # storage root, and remove the original from that path before proving recovery.
        restore_files = private / 'persistent-files'
        restore_files.rename(private / 'files-before-recovery')
        require(not restore_files.exists(), 'old attachment root still present')
        shutil.copytree(files_backup, restore_files)
        start(current, 'aura_candidate_restore')
        token = login()
        record_probe(token, code, pid, 68000)
        assert_permissions_and_audit()
        assert_attachment()
        check('backup-restore-recovers-record', observedBeforeRecovery=999999, restoredAmount=68000, attachmentSha256=hashlib.sha256(attachment_bytes).hexdigest(), originalAttachmentRootRetainedOutsideServingPath=True, backupSha256=hashlib.sha256(dump.read_bytes()).hexdigest())
        # The original unpack verifier must reject corrupt bytes before creating an active target.
        corrupt = output / 'corrupt-candidate.tar'
        corrupt.write_bytes(b'invalid archived candidate')
        expected = hashlib.sha256(candidate_archive.read_bytes()).hexdigest()
        destination = output / 'rejected-candidate'
        result = subprocess.run(['node', root / 'scripts/application/platform-artifact-package.mjs', 'unpack', corrupt, expected, destination, json.loads((current / 'release-receipt.json').read_text())['source']['commit']], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        require(result.returncode != 0 and not destination.exists(), 'corrupt candidate was activated')
        record_probe(token, code, pid, 68000)
        check('corrupt-candidate-rejected-current-business-continues', amount=68000)
        stop()
        # Exercise the delivered OCI bytes as well as the host JAR. A base-image
        # change must preserve real database reads, authentication and attachments.
        receipt = json.loads((current / 'release-receipt.json').read_text())
        image_layout = current / receipt['image']['layout']
        oci_archive = private / 'runtime.oci.tar'
        run(['tar', '-C', image_layout, '-cf', oci_archive, '.'], 'oci-archive')
        loaded = subprocess.check_output(['docker', 'load', '--input', oci_archive], text=True)
        (private / 'oci-load.log').write_text(loaded)
        references = [line.split(': ', 1)[1] for line in loaded.splitlines()
                      if line.startswith(('Loaded image: ', 'Loaded image ID: '))]
        require(len(references) == 1, 'one loaded OCI reference required')
        image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', references[0]], text=True))[0]
        require(image['Config']['Labels']['org.opencontainers.image.revision'] == receipt['source']['commit'],
                'loaded OCI source differs from candidate')
        run(['node', '--input-type=module', '-e',
             "import{verifyOciLayout}from './scripts/application/export-product-image.mjs';"
             "await verifyOciLayout(...process.argv.slice(1));", image_layout, receipt['image']['digest'], image['Id']],
            'oci-identity')
        image_env = private / 'oci-runtime.env'
        values = {key: env[key] for key in ['JWT_SECRET', 'FIELD_ENCRYPTION_KEY',
                  'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY']}
        lock = json.loads((current / 'application.lock').read_text())
        values.update(AURA_APPLICATION_ID=lock['application']['id'],
                      AURA_APPLICATION_VERSION=lock['application']['version'],
                      AURA_APPLICATION_LOCK_IDENTITY=lock['identity'],
                      AURA_APPLICATION_SOURCE_COMMIT=receipt['source']['commit'],
                      AURA_APPLICATION_IMAGE_DIGEST=receipt['image']['digest'])
        values.update(SPRING_PROFILES_ACTIVE='community', AURA_APPLICATION_MODE='core-only',
                      AURABOOT_BOOTSTRAP_ENABLED='false', AURABOOT_DEMO_SEED='false',
                      SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{port}/aura_candidate_restore',
                      SPRING_DATASOURCE_USERNAME='auraboot', SPRING_DATASOURCE_PASSWORD=env['PGPASSWORD'],
                      AURA_FILE_STORAGE_PATH=str(private / 'persistent-files'), SERVER_PORT=str(backend_port))
        image_env.write_text(''.join(key + '=' + value + '\n' for key, value in values.items()))
        image_env.chmod(0o600)
        run(['docker', 'run', '-d', '--name', image_container, '--network', 'host',
             '--label', 'aura.ci.job=' + job, '--env-file', image_env,
             '--mount', f'type=bind,src={private / "persistent-files"},dst={private / "persistent-files"}',
             '--entrypoint', 'java', image['Id'], '-Daura.plugins.dir=/tmp/empty-plugins',
             '-jar', '/opt/auraboot/runtime/application.jar'], 'oci-start')
        image_started = True
        for _ in range(90):
            try:
                if request('/actuator/health').get('status') == 'UP':
                    break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('delivered OCI did not become ready; owned container retained')
        token = login()
        identity = request('/api/application/identity', headers={'Authorization': 'Bearer ' + token})
        require(identity.get('sourceCommit') == receipt['source']['commit']
                and identity.get('lockIdentity') == lock['identity']
                and identity.get('imageDigest') == receipt['image']['digest'],
                'served OCI application identity differs from the delivered candidate')
        record_probe(token, code, pid, 68000)
        assert_attachment()
        check('delivered-oci-authentication-business-and-attachment', source=receipt['source']['commit'],
              loadedImageId=image['Id'], amount=68000, attachmentSha256=hashlib.sha256(attachment_bytes).hexdigest())
        labels = json.loads(subprocess.check_output(['docker', 'inspect', image_container], text=True))[0]['Config']['Labels']
        require(labels.get('aura.ci.job') == job, 'runtime OCI ownership changed; retained')
        run(['docker', 'stop', image_container], 'oci-stop')
        image_started = False
        check('owned-process-stop', backendPort=backend_port, webPort=web_port)
        (output / 'result.json').write_text(json.dumps({'status': 'passed', 'releaseQualification': False, 'checks': checks, 'databaseScope': 'new attempt-owned PostgreSQL container; four databases retained, including separate supported MULTI security bootstrap', 'previousLifecycle': 'current packaged installer operates the original previous artifact without relabelling its source', 'prerequisites': {'postgres': pg_image, 'node': subprocess.check_output(['node', '--version'], text=True).strip()}}, indent=2))
    finally:
        # A failed ownership check preserves resources instead of stopping others.
        if active:
            stop()
        if image_started:
            labels = json.loads(subprocess.check_output(['docker', 'inspect', image_container], text=True))[0]['Config']['Labels']
            require(labels.get('aura.ci.job') == job, 'runtime OCI ownership changed; retained')
            with (private / 'oci-runtime.log').open('wb') as log:
                subprocess.run(['docker', 'logs', image_container], stdout=log, stderr=subprocess.STDOUT, timeout=30)
            run(['docker', 'stop', image_container], 'oci-stop-after-failure')
        if pg_started:
            labels = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]['Config']['Labels']
            require(labels.get('aura.ci.job') == job, 'PostgreSQL ownership changed; retained')
            run(['docker', 'stop', container], 'postgres-stop')
        # Never remove databases, state, logs, images or historical evidence here.


if __name__ == '__main__':
    main()
