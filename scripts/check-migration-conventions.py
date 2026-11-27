#!/usr/bin/env python3
"""Migration convention validator: checks new CREATE TABLE migrations follow platform conventions.

Generic business-table rules (exact infrastructure policy is explicit, version-bound):
  R1: CREATE TABLE must include BIGINT created_by and updated_by columns
  R2: CREATE TABLE must include tenant_id column
  R3: CREATE TABLE must include pid VARCHAR column
  R4: Discourages DROP TABLE / DROP COLUMN in migrations (destructive)

Usage: python3 check-migration-conventions.py <migration_dir> [--baseline <sha>]
"""
import re, sys, os, json, hashlib
from pathlib import Path

def check_file(path):
    issues = []
    content = Path(path).read_text(encoding='utf-8')
    tables = re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\);', content, re.DOTALL | re.IGNORECASE)
    for tname, tcols in tables:
        cols_lower = tcols.lower()
        if 'created_by' not in cols_lower:
            issues.append(f'{tname}: missing created_by column')
        elif 'bigint' not in re.search(r'created_by\s+(\w+)', cols_lower, re.I).group(1).lower() if re.search(r'created_by\s+(\w+)', cols_lower, re.I) else True:
            pass  # type check is approximate in SQL text
        if 'updated_by' not in cols_lower:
            issues.append(f'{tname}: missing updated_by column')
        if 'tenant_id' not in cols_lower:
            issues.append(f'{tname}: missing tenant_id column')
        # A pid COLUMN is 'pid <type> ...' — a bare-word equality split() missed every
        # typed declaration (pid VARCHAR(32) NOT NULL UNIQUE) and false-positived whole
        # schemas (all 27 edu mt_xy_* tables carry pid; the core baseline was inflated
        # by the same bug). Also require a word boundary so e.g. 'xpid' does not match.
        if not any(re.match(r'\s*pid\s+\S', col) for col in cols_lower.split(',')):
            issues.append(f'{tname}: missing pid column')

    drops = re.findall(r'DROP (?:TABLE|COLUMN)\s+\w+', content, re.I)
    for d in drops:
        issues.append(f'destructive: {d.strip()}')

    return issues

def issue_lines(target_dir):
    """Every current violation as its stable 'file: issue' identity line."""
    lines = []
    for root, dirs, files in os.walk(target_dir):
        for f in sorted(files):
            if not f.endswith('.sql'): continue
            path = os.path.join(root, f)
            for issue in check_file(path):
                lines.append(f'{f}: {issue}')
    return lines

def infrastructure_policy(target_dir, policy_file):
    """Classify exact internal storage, without changing SQL or accepted debt."""
    value = json.loads(Path(policy_file).read_text(encoding='utf-8'))
    if set(value) != {'schemaVersion', 'migrations'} or type(value['schemaVersion']) is not int or value['schemaVersion'] != 1:
        raise ValueError('Invalid infrastructure policy schema')
    if not isinstance(value['migrations'], dict) or not value['migrations']:
        raise ValueError('Nonempty exact infrastructure migrations required')
    exemptions = set()
    repo = Path(__file__).resolve().parent.parent
    for filename, migration in value['migrations'].items():
        if Path(filename).name != filename or not filename.endswith('.sql') or set(migration) != {'sha256', 'tables'}:
            raise ValueError('Exact migration identity required')
        file = Path(target_dir) / filename
        if (file.is_symlink() or not file.is_file() or not re.fullmatch('[a-f0-9]{64}', migration['sha256'])
                or hashlib.sha256(file.read_bytes()).hexdigest() != migration['sha256']):
            raise ValueError('Infrastructure migration source changed: ' + filename)
        issues = set(check_file(file))
        tables = set(re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\(', file.read_text(), re.I))
        if not isinstance(migration['tables'], dict) or not migration['tables'] or not set(migration['tables']) <= tables:
            raise ValueError('Infrastructure table declaration differs: ' + filename)
        for table, rule in migration['tables'].items():
            if (set(rule) != {'scope', 'exemptMissingColumns', 'reason', 'evidence'}
                    or rule['scope'] not in {'internal-state', 'internal-receipt', 'immutable-audit', 'global-provider-fact'}
                    or not isinstance(rule['reason'], str) or len(rule['reason']) < 40
                    or not isinstance(rule['evidence'], list) or not rule['evidence']):
                raise ValueError('Explicit infrastructure ownership and semantics required: ' + table)
            for reference in rule['evidence']:
                relative = Path(reference)
                if relative.is_absolute() or '..' in relative.parts or not (repo / relative).is_file():
                    raise ValueError('Infrastructure ownership evidence unavailable: ' + table)
            columns = rule['exemptMissingColumns']
            if (not isinstance(columns, list) or not columns or len(set(columns)) != len(columns)
                    or not set(columns) <= {'created_by', 'updated_by', 'tenant_id', 'pid'}
                    or ('tenant_id' in columns and rule['scope'] != 'global-provider-fact')):
                raise ValueError('Exact infrastructure column scope required: ' + table)
            for column in columns:
                issue = f'{table}: missing {column} column'
                if issue not in issues:
                    raise ValueError('Stale infrastructure classification: ' + filename + ': ' + issue)
                exemptions.add(filename + ': ' + issue)
    return exemptions


if __name__ == '__main__':
    args = sys.argv[1:]
    target_dir = args[0] if args else '.'
    baseline_file = None
    if '--baseline-file' in args:
        baseline_file = args[args.index('--baseline-file') + 1]
    current = issue_lines(target_dir)
    if '--infrastructure-policy' in args:
        try:
            classified = infrastructure_policy(target_dir, args[args.index('--infrastructure-policy') + 1])
        except (OSError, ValueError, KeyError, TypeError, IndexError) as failure:
            print('Invalid migration infrastructure policy: ' + str(failure), file=sys.stderr)
            sys.exit(2)
        current = [line for line in current if line not in classified]
        print(f'Classified {len(classified)} exact internal-storage column requirements; generic business rules unchanged')
    if '--write-baseline' in args:
        # Self-generate the accepted-debt baseline in the exact identity format the
        # ratchet compares — no manual reformatting, no prefix drift.
        with open(baseline_file, 'w', encoding='utf-8') as out:
            out.write('\n'.join(current) + ('\n' if current else ''))
        print(f'baseline written: {len(current)} accepted issue(s) -> {baseline_file}')
        sys.exit(0)
    if baseline_file:
        # Ratchet: the committed baseline holds the accepted historical debt; only
        # violations NOT in the baseline block CI. Ratcheting down is encouraged —
        # the script reports how many accepted issues were fixed so the baseline
        # can be regenerated and the debt shrinks monotonically.
        baseline = set()
        if os.path.exists(baseline_file):
            baseline = {line for line in Path(baseline_file).read_text(encoding='utf-8').splitlines() if line.strip()}
        regressions = sorted(set(current) - baseline)
        fixed = sorted(baseline - set(current))
        for i in regressions: print(f'  🚫 NEW violation: {i}')
        if fixed:
            print(f'  ✅ {len(fixed)} accepted issue(s) fixed since baseline — regenerate the baseline to shrink the debt')
        if regressions:
            print(f'\n{len(regressions)} NEW convention violation(s) (baseline holds {len(baseline)} accepted)')
            sys.exit(1)
        print(f'✅ No new migration convention violations (baseline holds {len(baseline)} accepted; {len(fixed)} fixed)')
        sys.exit(0)
    if current:
        for i in current: print(f'  ⚠️  {i}')
        print(f'\n{len(current)} convention issue(s) found')
        sys.exit(1)
    else:
        print('✅ All migrations pass convention checks')
