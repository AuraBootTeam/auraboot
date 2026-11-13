import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const normalize=input=>{const r=spawnSync('python3',[new URL('./normalize-schema-dump.py',import.meta.url).pathname],{input,encoding:'utf8'});assert.equal(r.status,0,r.stderr);return r.stdout;};
test('patched server attribute versions and session metadata cannot create nondeterministic schema drift',()=>{
 const logical='CREATE TABLE public.invoice (amount numeric(18,2) NOT NULL);\nCREATE INDEX invoice_amount ON public.invoice(amount);\n';
 for(const version of [123,987654])assert.equal(normalize('\\restrict token\n-- Dumped by pg_dump version 16.4\nSET transaction_timeout = 0;\n'+logical+'ALTER TABLE ONLY public.invoice ALTER COLUMN amount SET (pg_attver='+version+');\n\\unrestrict token\n'),logical);
});
test('business schema and real attribute options remain exact drift inputs',()=>{
 const ddl=['ALTER TABLE ONLY public.invoice ALTER COLUMN amount SET (n_distinct=100);',
 'ALTER TABLE ONLY public.invoice ALTER COLUMN amount SET (pg_attver=42, n_distinct=100);',
 'ALTER TABLE ONLY public.invoice ALTER COLUMN amount SET DEFAULT 42;',
 'ALTER TABLE ONLY public.invoice ADD CONSTRAINT positive CHECK (amount > 0);',
 'CREATE UNIQUE INDEX invoice_id ON public.invoice(id);',
 "COMMENT ON COLUMN public.invoice.amount IS 'pg_attver=42';"].join('\n')+'\n';
 assert.equal(normalize(ddl),ddl);
 assert.notEqual(normalize(ddl),normalize(ddl.replace('> 0','> 1')));
});
