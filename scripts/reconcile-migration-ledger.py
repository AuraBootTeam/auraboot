#!/usr/bin/env python3
"""Reconcile migration directory vs flyway ledger: find gaps before they become production incidents.

Usage: python3 reconcile-migration-ledger.py <migration_dir> <ledger_query_output>
  <migration_dir>  = path to db/migration/core/ (or similar)
  <ledger_query_output> = file containing output of:
    SELECT DISTINCT version FROM <ledger_table> WHERE deleted_flag=false ORDER BY 1

Exit 0 = no gaps, exit 1 = gaps found (prints them)
"""
import sys, os, re

migration_dir = sys.argv[1]
ledger_file = sys.argv[2]

dir_versions = set()
for f in os.listdir(migration_dir):
    m = re.match(r'[Vv](\d+)__', f)
    if m: dir_versions.add(m.group(1))

ledger_versions = set()
for line in open(ledger_file):
    line = line.strip()
    if line and re.match(r'^\d+$', line):
        ledger_versions.add(line)

gaps = sorted(dir_versions - ledger_versions)
extra = sorted(ledger_versions - dir_versions)

if gaps:
    print(f'⚠️  {len(gaps)} migration(s) in directory but NOT in ledger:')
    for g in gaps: print(f'  GAP: V{g}')
    sys.exit(1)

if extra:
    print(f'ℹ️  {len(extra)} ledger version(s) not in directory (likely from other modules)')

print('✅ No migration gaps detected')
