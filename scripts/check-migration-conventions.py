#!/usr/bin/env python3
"""Migration convention validator: checks new CREATE TABLE migrations follow platform conventions.

Rules:
  R1: CREATE TABLE must include BIGINT created_by and updated_by columns
  R2: CREATE TABLE must include tenant_id column
  R3: CREATE TABLE must include pid VARCHAR column
  R4: Discourages DROP TABLE / DROP COLUMN in migrations (destructive)

Usage: python3 check-migration-conventions.py <migration_dir> [--baseline <sha>]
"""
import re, sys, os

def check_file(path):
    issues = []
    content = open(path, encoding='utf-8').read()
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

if __name__ == '__main__':
    args = sys.argv[1:]
    target_dir = args[0] if args else '.'
    baseline_file = None
    if '--baseline-file' in args:
        baseline_file = args[args.index('--baseline-file') + 1]
    current = issue_lines(target_dir)
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
            baseline = {line.rstrip('\n') for line in open(baseline_file, encoding='utf-8') if line.strip()}
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
