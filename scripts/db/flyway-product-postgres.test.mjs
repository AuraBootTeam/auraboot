// Native invocation/history verification, not Orbit handler or public Run acceptance.
import assert from 'node:assert/strict';
import {createHash, randomBytes} from 'node:crypto';
import {execFileSync, spawnSync} from 'node:child_process';
import {mkdirSync, readFileSync, realpathSync, writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {dirname, join, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';

const core=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const workspace=process.env.AURA_WORKSPACE_ROOT,runtime=process.env.AURA_PRODUCT_FLYWAY_TEST_RUNTIME;
assert(workspace&&runtime&&/^[a-z0-9-]+$/.test(runtime),'An owned development runtime is required');
const evidence=resolve(process.env.AURA_PRODUCT_FLYWAY_TEST_EVIDENCE);
assert(evidence.startsWith(join(workspace,'.workspace/evidence',runtime)+sep));
const probe=spawnSync(join(workspace,'aura'),['runtime','explain',runtime,'--json'],{encoding:'utf8'});
assert.equal(probe.error,undefined,'Runtime admission must execute');
assert.equal(probe.signal,null,'Runtime admission must terminate normally');
assert.equal(probe.status,0,'Runtime admission must succeed before any database access');
const identity=JSON.parse(probe.stdout);
assert.equal(identity.verdict,'ok','Runtime admission must be valid before any database access');
assert.equal(identity.metadata.mode,'development');
assert.equal(realpathSync(identity.sources.find(s=>s.key==='core').expected.root),realpathSync(core));
// No deployed-source claim: this is the exact helper's independent DB fixture.
const [host,port,user,database,password,...extra]=readFileSync(join(workspace,'.workspace/runtimes',runtime,'oss-stack/pgenv'),'utf8').trim().split(/\s+/);
assert.equal(extra.length,0);assert.equal(host,'127.0.0.1');assert(/^\d+$/.test(port));
assert(/^auraboot_[0-9]+$/.test(database));assert.equal(database,identity.environment.POSTGRES_DB);
assert(user&&password);mkdirSync(evidence);
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const inputs=Object.fromEntries(['flyway-common.sh','flyway-product.conf','flyway-product-postgres.test.mjs'].map(name=>[name,sha(readFileSync(join(core,'scripts/db',name)))]));
writeFileSync(join(evidence,'inputs.json'),JSON.stringify({coreCommit:execFileSync('git',['-C',core,'rev-parse','HEAD'],{encoding:'utf8'}).trim(),
  inputs,database:{host,port:Number(port),database},scope:'Native product-only Flyway helper with owned fixture SQL; no Orbit installation or public Run claim'},null,2));
const require=createRequire(join(core,'web-admin/package.json')),{Client}=require('pg');
const client=new Client({host,port:Number(port),user,database,password,connectionTimeoutMillis:5000});
await client.connect();
const suffix=randomBytes(8).toString('hex'),schemas=['fresh','dirty','resume'].map(key=>`orb_migration_it_${key}_${suffix}`);
const actions=[];
async function publicState() {
  const rows=(await client.query("SELECT c.relname,c.relkind,a.attname,a.atttypid,a.attnotnull,a.attnum FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped WHERE n.nspname='public' ORDER BY c.relname,a.attnum")).rows;
  const history=(await client.query("SELECT to_regclass('public.ab_flyway_schema_history') AS name")).rows[0].name;
  const contents=history?(await client.query('SELECT * FROM public.ab_flyway_schema_history ORDER BY installed_rank')).rows:null;
  return {structureSha256:sha(JSON.stringify(rows)),history:contents};
}
function invoke(command,schema,payload,expectedSuccess) {
  const result=spawnSync('/bin/bash',['-c','source "$1"; run_product_flyway "$2" "$3" "$4"','native-fixture',
    join(core,'scripts/db/flyway-common.sh'),command,schema,payload],{encoding:'utf8',timeout:45000,env:{...process.env,
      PG_HOST:host,PG_PORT:port,PG_USER:user,PG_DB:database,PG_PASSWORD:password}});
  const index=actions.length+1;writeFileSync(join(evidence,`${index}-${command}.log`),(result.stdout??'')+(result.stderr??''));
  actions.push({command,schema,exitCode:result.status,signal:result.signal,expectedSuccess});
  assert.equal(result.signal,null);assert.equal(result.status===0,expectedSuccess,`Unexpected native outcome; see ${index}-${command}.log`);
  return result;
}
const before=await publicState();let success=false;
try {
  const fixturePayload=join(evidence,'payload');mkdirSync(fixturePayload);const payload=realpathSync(fixturePayload);
  const original='CREATE TABLE marker(id BIGINT PRIMARY KEY);\n';
  writeFileSync(join(payload,'V20261030010000__marker.sql'),original);
  invoke('migrate',schemas[0],payload,true);
  invoke('validate',schemas[0],payload,true);
  invoke('migrate',schemas[0],payload,true);
  assert.deepEqual((await client.query('SELECT version,success FROM '+schemas[0]+'.ab_flyway_schema_history WHERE version IS NOT NULL')).rows,
    [{version:'20261030010000',success:true}]);
  assert.deepEqual((await client.query('SELECT tablename FROM pg_tables WHERE schemaname=$1 ORDER BY tablename',[schemas[0]])).rows.map(r=>r.tablename),
    ['ab_flyway_schema_history','marker']);
  // Same semantics, changed bytes: checksum validation must still fail.
  writeFileSync(join(payload,'V20261030010000__marker.sql'),original+'-- changed after installation\n');
  invoke('validate',schemas[0],payload,false);
  writeFileSync(join(payload,'V20261030010000__marker.sql'),original);
  invoke('validate',schemas[0],payload,true);
  await client.query('CREATE SCHEMA '+schemas[1]);await client.query('CREATE TABLE '+schemas[1]+'.unknown(id BIGINT)');
  invoke('migrate',schemas[1],payload,false);
  assert.equal((await client.query('SELECT to_regclass($1) AS name',[schemas[1]+'.ab_flyway_schema_history'])).rows[0].name,null);
  // A transactional second-file failure retains V1; identical V2 bytes resume
  // after its independently modelled deployment prerequisite becomes available.
  const second='CREATE TABLE second_marker(id BIGINT REFERENCES prerequisite(id));\n';
  writeFileSync(join(payload,'V20261030010001__second.sql'),second);
  invoke('migrate',schemas[2],payload,false);
  assert.deepEqual((await client.query('SELECT version,success FROM '+schemas[2]+'.ab_flyway_schema_history WHERE version IS NOT NULL')).rows,
    [{version:'20261030010000',success:true}]);
  const first=(await client.query('SELECT installed_rank,installed_on,checksum FROM '+schemas[2]+'.ab_flyway_schema_history WHERE version=$1',['20261030010000'])).rows;
  // Fixture prerequisite only, never an installation repair path.
  await client.query('CREATE TABLE '+schemas[2]+'.prerequisite(id BIGINT PRIMARY KEY)');
  assert.equal(readFileSync(join(payload,'V20261030010001__second.sql'),'utf8'),second);
  invoke('migrate',schemas[2],payload,true);invoke('validate',schemas[2],payload,true);
  assert.deepEqual((await client.query('SELECT installed_rank,installed_on,checksum FROM '+schemas[2]+'.ab_flyway_schema_history WHERE version=$1',['20261030010000'])).rows,first);
  assert.equal((await client.query('SELECT count(*)::int AS count FROM '+schemas[2]+'.ab_flyway_schema_history WHERE version IS NOT NULL AND success')).rows[0].count,2);
  assert.deepEqual(await publicState(),before);success=true;
} finally {
  for(const schema of schemas)await client.query('DROP SCHEMA IF EXISTS '+schema+' CASCADE');
  const after=await publicState();await client.end();
  writeFileSync(join(evidence,'receipt.json'),JSON.stringify({success,actions,publicBefore:before,publicAfter:after,
    publicUnchanged:JSON.stringify(before)===JSON.stringify(after),fixtureSchemasRemoved:schemas,inputs,
    didNotRun:['Orbit candidate SQL installation','SDK/Journal/outbox full transaction','public Run','browser E2E']},null,2));
  assert.deepEqual(after,before);
}
