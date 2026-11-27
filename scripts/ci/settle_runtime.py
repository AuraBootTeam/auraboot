#!/usr/bin/env python3
"""Suspend one finished native CI gate without deleting its retained environment."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def settle(workspace, repo, name, artifacts, environment):
    require(re.fullmatch(r'job-[a-f0-9]{40}', environment.get('AURA_CI_JOB_ID', '')),
            'An exact admitted native job is required')
    frozen = Path(environment.get('AURA_CI_WORKSPACE_ROOT', ''))
    require(frozen.is_absolute() and frozen.resolve(strict=True) == frozen
            and repo.is_absolute() and repo.resolve(strict=True) == repo and repo == frozen / 'auraboot'
            and Path(environment.get('AURA_CI_FROZEN_CONTEXT', '')).is_file(),
            'Only this native job source may settle its runtime')
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]*', name)
            and artifacts.is_absolute() and artifacts.is_dir() and artifacts.resolve(strict=True) == artifacts,
            'Physical admitted runtime evidence is required')
    output = artifacts / ('runtime-settlement-' + name)
    output.mkdir(mode=0o700)
    cli = str(workspace / 'aura')

    def document(action, filename):
        result = subprocess.run([cli, 'runtime', action, name, '--json'], env=environment,
                                capture_output=True, text=True, timeout=60)
        (output / filename).write_text(result.stdout)
        # explain can report a failed startup; its exact source still has to
        # prove ownership before the original lifecycle handler is invoked.
        require(result.returncode in ((0, 1) if action == 'explain' else (0,)),
                'Original runtime inventory is unavailable')
        return json.loads(result.stdout)

    shown = document('show', 'allocation-before.json')
    require('allocation' in shown and (shown['allocation'] is None or isinstance(shown['allocation'], dict)),
            'Missing allocation evidence cannot mean a runtime never started')
    if shown.get('allocation') is None:
        result = {'runtime': name, 'status': 'not-started', 'processMutation': False}
    else:
        before = document('explain', 'identity-before.json')
        head = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'],
                                       env=environment, text=True, timeout=30).strip()
        # The deployed registry generations support core and auraboot source keys.
        # Validate the recorded identity, not the spelling passed to migrate.
        sources = [row for row in before['sources'] if row['key'] in ('core', 'auraboot')]
        require(before['allocation']['name'] == name and before['allocation']['repo'] == 'auraboot'
                and len(sources) == 1 and sources[0]['expected']['root'] == str(repo)
                and sources[0]['actual']['root'] == str(repo)
                and sources[0]['expected']['commit'] == sources[0]['actual']['commit'] == head
                and sources[0]['actual']['dirtyFingerprint'] == 'clean',
                'A foreign or drifted runtime cannot be suspended by this native job')
        stopped = subprocess.run([cli, 'runtime', 'suspend', name], env=environment,
                                 capture_output=True, text=True, timeout=120)
        (output / 'suspend.log').write_text(stopped.stdout + stopped.stderr)
        require(stopped.returncode == 0, 'Original runtime lifecycle suspension failed')
        after = document('explain', 'identity-after.json')
        require(after['metadata']['lifecycle'] == 'suspended' and after['processes'] == []
                and isinstance(after['ports'], dict) and bool(after['ports'])
                and set(after['ports']) == set(before['ports'])
                and all(row['status'] == 'stopped' for row in after['ports'].values())
                and after['allocation']['name'] == name,
                'The retained runtime still has active processes or ports')
        result = {'runtime': name, 'status': 'suspended', 'retained': ['allocation', 'database', 'caches', 'evidence'],
                  'remainingProcesses': [], 'production': False}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('workspace', 'repo', 'artifacts'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--name', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(settle(args.workspace, args.repo, args.name, args.artifacts, dict(os.environ))))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print('ci-runtime-settlement: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
