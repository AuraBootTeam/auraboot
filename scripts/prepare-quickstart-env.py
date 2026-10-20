#!/usr/bin/env python3
"""Persist missing protocol signing material without printing or rotating existing keys."""
import argparse
import os
from pathlib import Path
import re
import secrets
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    args = parser.parse_args()
    key, changed = prepare(args.file)
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::add-mask::' + key.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
    print('Protocol signing material ' + ('prepared' if changed else 'preserved') + '; retain the configured signing material across restarts.')


if __name__ == '__main__':
    main()
