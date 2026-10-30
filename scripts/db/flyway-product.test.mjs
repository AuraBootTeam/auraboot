import assert from 'node:assert/strict';
import {chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

const common=resolve(dirname(fileURLToPath(import.meta.url)), 'flyway-common.sh');
// Invocation-policy component tests only; this stub does not prove migration effects.
function run({command='migrate', schema='orbit_control', override={}, exit=0, payloadKind='regular'}={}) {
  const root=realpathSync(mkdtempSync(join(tmpdir(),'product-flyway-')));
  try {
    const bin=join(root,'bin'), payload=join(root,'payload'), log=join(root,'args');
    mkdirSync(bin); mkdirSync(payload);
    writeFileSync(join(payload,'V20261030010000__fixture.sql'),'CREATE TABLE marker(id BIGINT);');
    writeFileSync(join(bin,'flyway'),'#!/bin/bash\nprintf "%s\\n" "$@" > "$CALL_LOG"\nprintf "%s" "$FLYWAY_PASSWORD" > "$PASSWORD_LOG"\nexit "$STUB_EXIT"\n');
    chmodSync(join(bin,'flyway'),0o755);
    if(payloadKind==='symlink')symlinkSync(join(payload,'V20261030010000__fixture.sql'),join(payload,'V20261030010001__alias.sql'));
    const env={...process.env};for(const key of Object.keys(env))if(key.startsWith('FLYWAY_'))delete env[key];
    Object.assign(env,{PATH:`${bin}:/usr/bin:/bin`, PG_DB:'owned_fixture',PG_HOST:'127.0.0.1',PG_PORT:'5432',PG_USER:'fixture',PG_PASSWORD:'synthetic-fixture',
      AURA_FLYWAY_CORE_MIGRATION_DIR:'', AURA_FLYWAY_EXTRA_LOCATIONS:'',AURA_FLYWAY_OUT_OF_ORDER:'0',AURA_FLYWAY_PRE1900_CORE_COMPAT:'0',
      CALL_LOG:log,PASSWORD_LOG:join(root,'password'),STUB_EXIT:String(exit),...override});
    const result=spawnSync('/bin/bash',['-c','source "$1"; run_product_flyway "$2" "$3" "$4"','policy-test',common,command,schema,
      payloadKind==='relative'?'payload':payload],{env,encoding:'utf8',cwd:root});
    return {...result,args:existsSync(log)?readFileSync(log,'utf8').trim().split('\n'):[],
      password:existsSync(join(root,'password'))?readFileSync(join(root,'password'),'utf8'):null,payload};
  } finally {rmSync(root,{recursive:true,force:true});}
}
for(const command of ['migrate','validate','info'])test(`${command} selects only the explicit private product payload`,()=>{
  const r=run({command});assert.equal(r.status,0,r.stderr);
  assert.equal(r.args.at(-1),command);
  assert.deepEqual(r.args.filter(a=>a.startsWith('-locations=')),[`-locations=filesystem:${r.payload}`]);
  for(const arg of ['-schemas=orbit_control','-defaultSchema=orbit_control','-table=ab_flyway_schema_history','-outOfOrder=false',
    '-baselineOnMigrate=false','-validateMigrationNaming=true','-validateOnMigrate=true','-ignoreMigrationPatterns=',
    '-cleanDisabled=true','-failOnMissingLocations=true','-placeholderReplacement=false','-skipDefaultCallbacks=true','-callbacks=','-resolvers='])assert(r.args.includes(arg),arg);
  assert(r.args.some(a=>a===`-configFiles=${resolve(dirname(common),'flyway-product.conf')}`));
  assert(!r.args.some(a=>a.includes('/migration/core')||a.startsWith('-password=')));
  assert.equal(r.password,'synthetic-fixture');
});
for(const schema of ['public','pg_catalog','information_schema','two,schemas','orbit;DROP','UPPER','a'.repeat(64)])
  test(`rejects unsafe schema ${schema}`,()=>{const r=run({schema});assert.equal(r.status,2);assert.deepEqual(r.args,[]);});
for(const command of ['baseline','repair','clean','migrate -outOfOrder=true'])
  test(`rejects ${command}`,()=>{const r=run({command});assert.equal(r.status,2);assert.deepEqual(r.args,[]);});
for(const override of [{AURA_FLYWAY_EXTRA_LOCATIONS:'/tmp/extra'},{AURA_FLYWAY_OUT_OF_ORDER:'1'},
  {AURA_FLYWAY_PRE1900_CORE_COMPAT:'1'},{FLYWAY_INIT_SQL:'SELECT 1'},{FLYWAY_URL:'jdbc:postgresql://wrong/db'},
  {FLYWAY_IGNORE_MIGRATION_PATTERNS:'*:missing'},{FLYWAY_CONFIG_FILES:'/tmp/ambient.conf'}])
  test(`rejects ambient policy ${Object.keys(override)[0]}`,()=>{const r=run({override});assert.equal(r.status,2);assert.deepEqual(r.args,[]);});
for(const payloadKind of ['symlink','relative'])test(`rejects ${payloadKind} payload`,()=>{
  const r=run({payloadKind});assert.equal(r.status,2);assert.deepEqual(r.args,[]);
});
test('preserves the first native failure without repair or retry',()=>{
  const r=run({exit:7});assert.equal(r.status,7);assert.equal(r.args.filter(a=>a==='migrate').length,1);
});

for(const [verdict,status] of [['invalid',0],['ok',1]])test(`native fixture refuses runtime verdict=${verdict}/exit=${status} before database access`,()=>{
  const root=realpathSync(mkdtempSync(join(tmpdir(),'migration-admission-')));
  try {
    const cli=join(root,'aura'),evidence=join(root,'.workspace/evidence/fixture/run');
    writeFileSync(cli,`#!/bin/bash\nprintf '%s\\n' '${JSON.stringify({verdict,metadata:{mode:'development'}})}'\nexit ${status}\n`);
    chmodSync(cli,0o755);
    const r=spawnSync(process.execPath,[resolve(dirname(common),'flyway-product-postgres.test.mjs')],{encoding:'utf8',env:{...process.env,
      AURA_WORKSPACE_ROOT:root,AURA_PRODUCT_FLYWAY_TEST_RUNTIME:'fixture',AURA_PRODUCT_FLYWAY_TEST_EVIDENCE:evidence}});
    assert.notEqual(r.status,0);assert.match(r.stderr,/Runtime admission must/);assert.equal(existsSync(evidence),false);
    // No pgenv exists: failure must be admission, not an attempted DB resolution.
    assert(!r.stderr.includes('ENOENT'));
  } finally {rmSync(root,{recursive:true,force:true});}
});
