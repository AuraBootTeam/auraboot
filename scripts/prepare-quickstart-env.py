#!/usr/bin/env python3
"""Prepare a new private local evaluation or preserve existing protocol signing material."""
import argparse
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile

KEY = 'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY'
ASSIGNMENT = re.compile(r'^[ \t]*(?:export[ \t]+)?' + KEY + r'[ \t]*=[ \t]*(.*)$', re.MULTILINE)


def literal_value(raw):
    value = raw.strip()
    if len(value) >= 2 and value[0] in "\"'" and value[-1] == value[0]:
        value = value[1:-1]
    elif value.startswith('#'):
        value = ''
    else:
        value = value.split(' #', 1)[0].strip()
    return value.strip()


def prepare(path: Path):
    if path.is_symlink():
        raise ValueError('Refusing to replace a symlinked environment file')
    original = path.read_bytes() if path.exists() else None
    text = original.decode('utf-8') if original is not None else ''
    assignments = list(ASSIGNMENT.finditer(text))
    if len(assignments) > 1:
        raise ValueError('Duplicate protocol signing key assignments; resolve them before startup')
    if KEY in os.environ:
        value = os.environ[KEY].strip()
        if not value:
            raise ValueError('An empty protocol signing environment override shadows the file; unset it before preparation')
        return value, False
    if assignments:
        value = literal_value(assignments[0].group(1))
        if value:
            return value, False
    # Compose already supports the codec's field-encryption-key fallback. Keep that
    # established material instead of silently invalidating existing cursors/ETags.
    fallback = os.environ.get('FIELD_ENCRYPTION_KEY', '').strip()
    if not fallback:
        fields = list(re.finditer(r'^[ \t]*(?:export[ \t]+)?FIELD_ENCRYPTION_KEY[ \t]*=[ \t]*(.*)$', text, re.MULTILINE))
        if len(fields) > 1:
            raise ValueError('Duplicate field encryption key assignments; resolve them before startup')
        if fields:
            fallback = literal_value(fields[0].group(1))
    if fallback:
        return fallback, False
    key = secrets.token_hex(32)
    line = KEY + '=' + key
    if assignments:
        match = assignments[0]
        updated = text[:match.start()] + line + text[match.end():]
    else:
        updated = text + ('' if not text or text.endswith('\n') else '\n') + line + '\n'
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                         prefix='.' + path.name + '-', delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(updated)
            handle.flush()
            os.fsync(handle.fileno())
        current = path.read_bytes() if path.exists() else None
        if path.is_symlink() or current != original:
            raise ValueError('Environment file changed during preparation; retry without concurrent edits')
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    return key, True


def prepare_new_local_evaluation(path: Path, run=None):
    """Prepare a new, private local evaluation; never migrate or rotate an existing one."""
    run = run or subprocess.run
    if path.name != '.env' or path.is_symlink() or path.exists():
        raise ValueError('New local evaluation requires an absent regular .env; keep existing configuration and restart normally')
    root = path.absolute().parent.resolve()
    path = root / path.name
    template = root / '.env.example'
    if not template.is_file() or template.is_symlink() or not (root / 'docker-compose.yml').is_file():
        raise ValueError('Run preparation in a complete AuraBoot source checkout')
    sensitive = ['JWT_SECRET', 'SESSION_SECRET', 'POSTGRES_PASSWORD', KEY, 'FIELD_ENCRYPTION_KEY',
                 'POSTGRES_USER', 'POSTGRES_DB']
    if any(key in os.environ for key in sensitive):
        raise ValueError('Unset inherited database/secret overrides before preparing an isolated local evaluation')
    project = os.environ.get('COMPOSE_PROJECT_NAME') or re.sub(r'[^a-z0-9_-]', '-', root.name.lower()).strip('-_')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', project):
        raise ValueError('A valid isolated Compose project name is required')
    ports = {}
    for key, default in [('AURABOOT_PORT', '3000'), ('POSTGRES_PORT', '15432')]:
        value = os.environ.get(key, default)
        if not value.isascii() or not value.isdigit() or not 1 <= int(value) <= 65535:
            raise ValueError('Evaluation ports must be numeric and within1..65535')
        ports[key] = value
    if len(set(ports.values())) != 2:
        raise ValueError('Web and database ports must be distinct')
    for key in ['AURABOOT_BIND_ADDRESS', 'POSTGRES_BIND_ADDRESS']:
        if key in os.environ and os.environ[key] != '127.0.0.1':
            raise ValueError('New local evaluation must bind only127.0.0.1')
    def docker_output(args):
        result = run(['docker'] + args, check=True, capture_output=True, text=True, timeout=20)
        return result.stdout.strip()
    if docker_output(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]):
        raise ValueError('Compose project already has containers; preserve the existing evaluation')
    if docker_output(['volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]):
        raise ValueError('Compose project already has data volumes; do not initialize or rotate it')
    for suffix in ['postgres_data', 'backend_data', 'frontend_traces']:
        name = project + '_' + suffix
        if name in docker_output(['volume', 'ls', '-q', '--filter', 'name=^' + name + '$']).splitlines():
            raise ValueError('An existing data volume must not be adopted')
    for port in ports.values():
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', int(port)))
    # Derive public files from Git; never chmod untracked customer resources or keys.
    tracked = run(['git', '-C', str(root), 'ls-files', '-z', '--', 'plugins',
                   'platform/src/main/resources/db/snapshots/schema-current.sql'],
                  check=True, capture_output=True, timeout=20).stdout
    tracked = tracked.decode() if isinstance(tracked, bytes) else tracked
    files, directories = [], set()
    schema = 'platform/src/main/resources/db/snapshots/schema-current.sql'
    relative_files = [value for value in tracked.split('\0') if value]
    if schema not in relative_files or not any(value.startswith('plugins/') for value in relative_files):
        raise ValueError('Complete tracked public schema and plugins are required')
    for relative in relative_files:
        file = root / relative
        if os.path.commonpath([str(file.resolve()), str(root)]) != str(root) or file.is_symlink() or not file.is_file():
            raise ValueError('Public Docker resources must be regular tracked files')
        parent = file.parent
        while parent != root:
            if parent.is_symlink() or not parent.is_dir():
                raise ValueError('Public resource directories must not be symlinks')
            if relative.startswith('plugins/'):
                directories.add(parent)
            parent = parent.parent
        files.append(file)
    values = dict(COMPOSE_PROJECT_NAME=project, AURABOOT_BIND_ADDRESS='127.0.0.1',
                  POSTGRES_BIND_ADDRESS='127.0.0.1', **ports,
                  JWT_SECRET=secrets.token_hex(32), SESSION_SECRET=secrets.token_hex(32),
                  POSTGRES_PASSWORD=secrets.token_urlsafe(32),
                  OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=secrets.token_hex(32))
    lines = template.read_text().splitlines()
    assignments = re.compile(r'^\s*(?:export\s+)?(' + '|'.join(values) + r')\s*=')
    lines = [line for line in lines if not assignments.match(line)]
    updated = '\n'.join(lines + [key + '=' + value for key, value in values.items()]) + '\n'
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=root,
                                         prefix='.local-evaluation-', delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(updated); handle.flush(); os.fsync(handle.fileno())
        for directory in directories:
            directory.chmod((directory.stat().st_mode & 0o777) | 0o055)
        for file in files:
            mode = file.stat().st_mode & 0o777
            file.chmod(mode | (0o055 if mode & 0o100 else 0o044))
        # Exclusive creation: a concurrent .env must never be replaced.
        os.link(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    return dict(project=project, publicFiles=len(files), publicDirectories=len(directories),
                environmentMode='0600', bindings='127.0.0.1', secretsPrinted=False,
                existingConfigurationReplaced=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    parser.add_argument('--new-local-evaluation', action='store_true',
                        help='Prepare a new empty local evaluation; refuses existing configuration and data')
    args = parser.parse_args()
    if args.new_local_evaluation:
        prepare_new_local_evaluation(args.file)
        print('New local evaluation prepared; private configuration retained, public Docker resources readable, loopback bindings only.')
        return
    key, changed = prepare(args.file)
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::add-mask::' + key.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
    print('Protocol signing material ' + ('prepared' if changed else 'preserved') + '; retain the configured signing material across restarts.')


if __name__ == '__main__':
    main()
