#!/usr/bin/env python3
"""Exercise the actual carrier build against hostile inputs in isolated fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def groovy(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'").replace('\n', '\\n').replace('\r', '\\r') + "'"


def main():
    if sys.version_info < (3, 11):
        raise RuntimeError('Carrier input verification requires Python 3.11 or newer')
    parser = argparse.ArgumentParser()
    for name in ['module', 'conventions', 'gradle', 'gradle-home', 'version', 'expected-jar', 'output']:
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    module, output = Path(args.module).resolve(), Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=output))
    fixture = run / 'fixture'
    fixture.mkdir()
    for name in ['build.gradle', 'gradle.lockfile', 'NOTICE.auraboot', 'upstream-inputs.json']:
        shutil.copy2(module / name, fixture / name)
    shutil.copytree(module / 'licenses', fixture / 'licenses')
    conventions = fixture / 'java-conventions.gradle'
    shutil.copy2(args.conventions, conventions)
    (fixture / 'settings.gradle').write_text("rootProject.name = 'carrier-input-fixture'\n")
    common = run / 'fixture.init.gradle'
    common.write_text('gradle.beforeProject { p ->\n'
                      + '    p.version = ' + groovy(args.version) + '\n'
                      + '    p.apply from: ' + groovy(conventions) + '\n}\n'
                      + 'gradle.taskGraph.whenReady { graph ->\n'
                      + '    def names = graph.allTasks.collect { it.name }\n'
                      + "    ['relocateDependencies', 'generateCarrierProvenance'].each { name ->\n"
                      + '        if (names.contains(name)) {\n'
                      + "            assert names.indexOf('verifyCarrierInputs') >= 0\n"
                      + "            assert names.indexOf('verifyCarrierInputs') < names.indexOf(name)\n"
                      + '        }\n'
                      + '    }\n}\n')
    repo = run / 'repository'
    publication = repo / 'org/mybatis/mybatis/3.5.19'
    publication.mkdir(parents=True)
    poisoned = publication / 'mybatis-3.5.19.jar'
    marker = run / 'escape/LICENSE-escaped.txt'
    base = fixture / 'build/carrier-provenance/licenses/mybatis-3.5.19'
    relative = os.path.relpath(marker, base).replace(os.sep, '/')
    with zipfile.ZipFile(poisoned, 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
        jar.writestr('META-INF/LICENSE.txt', 'Owned harmless fixture license\n')
        jar.writestr(relative, 'This owned marker must never be written.\n')
    (publication / 'mybatis-3.5.19.pom').write_text(
        '<project><modelVersion>4.0.0</modelVersion><groupId>org.mybatis</groupId>'
        '<artifactId>mybatis</artifactId><version>3.5.19</version></project>\n')
    negative = run / 'negative.init.gradle'
    negative.write_text('gradle.beforeProject { p ->\n'
                        + '    p.repositories.exclusiveContent {\n'
                        + '        forRepository { p.repositories.maven { url = uri('
                        + groovy(repo.as_uri()) + ') } }\n'
                        + "        filter { includeModule('org.mybatis', 'mybatis') }\n"
                        + '    }\n}\n')
    cases = []

    def execute(name, task, hostile=False):
        command = [args.gradle, '--no-daemon', '--max-workers=2',
                   '--gradle-user-home', args.gradle_home, '--project-dir', str(fixture),
                   '--init-script', str(common)]
        if hostile:
            command += ['--init-script', str(negative)]
        command += [task, '--rerun-tasks']
        log = run / (name + '.log')
        with log.open('w') as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
        return result.returncode, log.read_text(), log

    code, text, log = execute('reject-digest-before-unpack', 'jar', True)
    assert code != 0 and 'Carrier upstream input digest differs' in text
    assert ':verifyCarrierInputs FAILED' in text and ':relocateDependencies' not in text
    assert not marker.exists(), 'Unreviewed archive wrote a file before rejection'
    cases.append({'id': 'reject-digest-before-unpack', 'status': 'passed', 'log': str(log)})

    lock_file = fixture / 'upstream-inputs.json'
    original_lock = lock_file.read_bytes()
    lock = json.loads(original_lock)
    for item in lock['inputs']:
        if item['coordinate'] == 'org.mybatis:mybatis:3.5.19':
            item['sha256'] = digest(poisoned)
    lock_file.write_text(json.dumps(lock, indent=2) + '\n')
    code, text, log = execute('reject-license-path-even-with-matching-digest', 'generateCarrierProvenance', True)
    assert code != 0 and 'Carrier license entry escapes its reviewed input directory' in text
    assert not marker.exists(), 'License extraction escaped its input directory'
    cases.append({'id': 'reject-license-path-even-with-matching-digest', 'status': 'passed', 'log': str(log)})

    lock_file.write_bytes(original_lock)
    code, text, log = execute('reviewed-inputs-preserve-qualified-jar', 'jar')
    assert code == 0, str(log)
    actual = fixture / ('build/libs/mybatis-compat-' + args.version + '.jar')
    assert digest(actual) == digest(Path(args.expected_jar)), 'Reviewed carrier content changed'
    cases.append({'id': 'reviewed-inputs-preserve-qualified-jar', 'status': 'passed', 'log': str(log)})
    result = {'status': 'passed', 'cases': cases, 'buildScriptSha256': digest(module / 'build.gradle'),
              'carrierSha256': digest(actual), 'run': str(run), 'outsideMarkerAbsent': not marker.exists()}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
