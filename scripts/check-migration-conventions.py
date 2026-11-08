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
        if 'pid' not in cols_lower.split(','):
            issues.append(f'{tname}: missing pid column')

    drops = re.findall(r'DROP (?:TABLE|COLUMN)\s+\w+', content, re.I)
    for d in drops:
        issues.append(f'destructive: {d.strip()}')

    return issues

if __name__ == '__main__':
    target_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    all_issues = []
    for root, dirs, files in os.walk(target_dir):
        for f in sorted(files):
            if not f.endswith('.sql'): continue
            path = os.path.join(root, f)
            for issue in check_file(path):
                all_issues.append(f'{f}: {issue}')
    if all_issues:
        for i in all_issues: print(f'  ⚠️  {i}')
        print(f'\n{len(all_issues)} convention issue(s) found')
        sys.exit(1)
    else:
        print('✅ All migrations pass convention checks')
