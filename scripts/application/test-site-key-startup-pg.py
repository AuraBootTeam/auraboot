#!/usr/bin/env python3
"""Verify the production startup catalog query against an empty managed PostgreSQL DB."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
runtime = os.environ['AURA_RUNTIME_NAME']
workspace = Path(os.environ['AURA_WORKSPACE_ROOT'])
if Path(os.environ['AURA_CORE_PROJECT_ROOT']).resolve() != root:
    raise ValueError('Core source root mismatch')
evidence = Path(os.environ['AURA_WORKSPACE_STATE_DIR'])/'evidence'/runtime/'site-key-startup'
evidence.mkdir(parents=True, exist_ok=True)
run = evidence/datetime.datetime.now().strftime('%Y%m%dT%H%M%S%f')
run.mkdir()
identity = subprocess.run([str(workspace/'aura'), 'runtime', 'verify', runtime, '--json'], capture_output=True, text=True)
(run/'runtime.json').write_text(identity.stdout)
if identity.returncode:
    raise ValueError('managed runtime verification failed before SQL execution')
source = root/'platform/src/main/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializer.java'
text = source.read_text()
queries = re.findall(r'queryForObject\("""\s*(.*?)\s*""", Boolean.class\)', text, re.S)
if len(queries) != 1:
    raise ValueError('production startup query denominator changed')
query = queries[0]
command = ['psql', '-X', '-qAt', '-h', os.environ['POSTGRES_HOST'], '-p', os.environ['POSTGRES_PORT'],
           '-U', os.environ['DATABASE_USERNAME'], '-d', os.environ['POSTGRES_DB'], '-v', 'ON_ERROR_STOP=1']
env = dict(os.environ, PGPASSWORD=os.environ['DATABASE_PASSWORD'])
def sql(value):
    return subprocess.run(command, input=value, env=env, capture_output=True, text=True)
empty = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f','S');"
initial = sql(empty)
if initial.returncode or initial.stdout.strip() != '0':
    raise ValueError('requires an empty managed public schema')
cases = [
    ('missing', '', 'f'),
    ('non-unique', 'CREATE INDEX fixture_idx ON public.mt_behavior_site_key(site_key);', 'f'),
    ('tenant-unique', 'CREATE UNIQUE INDEX fixture_idx ON public.mt_behavior_site_key(tenant_id,site_key);', 'f'),
    ('partial-unique', 'CREATE UNIQUE INDEX fixture_idx ON public.mt_behavior_site_key(site_key) WHERE tenant_id=1;', 'f'),
    ('expression-unique', 'CREATE UNIQUE INDEX fixture_idx ON public.mt_behavior_site_key(lower(site_key));', 'f'),
    ('global-unique', 'CREATE UNIQUE INDEX fixture_idx ON public.mt_behavior_site_key(site_key);', 't'),
    ('global-with-include', 'CREATE UNIQUE INDEX fixture_idx ON public.mt_behavior_site_key(site_key) INCLUDE(tenant_id);', 't'),
]
results = []
for name, ddl, expected in cases:
    result = sql('BEGIN; CREATE TABLE public.mt_behavior_site_key(site_key text,tenant_id bigint);\n'+ddl+'\n'+query+'; ROLLBACK;')
    (run/(name+'.log')).write_text(result.stdout+result.stderr)
    passed = result.returncode == 0 and result.stdout.strip() == expected
    results.append({'case': name, 'exitCode': result.returncode, 'expected': expected, 'passed': passed})
    clean = sql(empty)
    if clean.returncode or clean.stdout.strip() != '0':
        raise ValueError('fixture rollback did not restore empty schema')
receipt = {'claim': 'production startup catalog SQL only; not application boot or plugin publication',
           'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'cases': results,
           'status': 'PASS' if all(r['passed'] for r in results) else 'FAIL'}
(run/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps({'evidence': str(run), **receipt}))
if receipt['status'] != 'PASS':
    raise SystemExit(1)
