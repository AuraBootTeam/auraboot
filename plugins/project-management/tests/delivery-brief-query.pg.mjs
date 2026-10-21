import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../web-admin/package.json', import.meta.url));
const { Client } = require('pg');
const queries = JSON.parse(fs.readFileSync(new URL('../config/named-queries.json', import.meta.url), 'utf8'));
const query = queries.find(row => row.code === 'tpm_delivery_brief');

// Named-query semantics against real PostgreSQL, using statement-local rows only.
// This never imports a plugin, creates tables or mutates retained business data.
const fixtures = `WITH
project_inputs(pid, tenant_id, owner_pid, code) AS (VALUES
 ('project-01',11,'user-1','P01'),('project-02',11,'user-2','P02'),
 ('project-03',11,'user-3','P03'),('project-04',11,'user-4','P04'),
 ('project-05',11,'user-5','P05'),('project-06',11,'missing-user','P06'),
 ('project-07',11,'user-7','P07'),('project-08',11,'user-8','P08'),
 ('foreign-project',22,'user-4','P09')),
mt_tpm_project AS (SELECT pid, tenant_id, code AS tpm_pj_code, code AS tpm_pj_name,
 'customer-'||tenant_id AS tpm_pj_customer_id,owner_pid AS tpm_pj_owner_user,
 'active' AS tpm_pj_status,50 AS tpm_pj_progress,DATE '2026-10-19' AS tpm_pj_end_date,
 100::numeric AS tpm_pj_budget,'fixture risk' AS tpm_pj_risk_summary,
 'fixture next action' AS tpm_pj_next_action FROM project_inputs),
mt_tpm_customer(pid,tenant_id,tpm_cu_name) AS (VALUES
 ('customer-11',11,'Local customer'),('customer-22',22,'Foreign customer')),
ab_user(id,pid,nick_name,user_name,deleted_flag) AS (VALUES
 (1,'user-1','Active owner','login-1',false),
 (2,'user-2','Inactive owner','login-2',false),
 (3,'user-3','Deleted member','login-3',false),
 (4,'user-4','Foreign owner','login-4',false),
 (5,'user-5','No membership','login-5',false),
 (7,'user-7','','fallback-user',false),
 (8,'user-8','Deleted user','login-8',true)),
ab_tenant_member(id,pid,user_id,tenant_id,status,deleted_flag) AS (VALUES
 (1,'member-1',1,11,'active',false),(2,'member-2',2,11,'inactive',false),
 (3,'member-3',3,11,'active',true),(4,'member-4',4,22,'active',false),
 (7,'member-7',7,11,'active',false),(8,'member-8',8,11,'active',false))
`;

test('delivery brief preserves active owner labels and tenant isolation using public member identity', async () => {
  for (const key of ['PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD']) {
    assert.ok(process.env[key], `Explicit ${key} required; no fallback database`);
  }
  assert.equal(query.resourceCode, 'tpm_project');
  assert.deepEqual(query.parameter_schema.required, ['tenantId']);
  const db = new Client({ host:process.env.PGHOST,port:Number(process.env.PGPORT),database:process.env.PGDATABASE,user:process.env.PGUSER,password:process.env.PGPASSWORD });
  await db.connect();
  try {
    await db.query('BEGIN READ ONLY');
    const metadata = (await db.query("SELECT attnotnull FROM pg_attribute WHERE attrelid='ab_tenant_member'::regclass AND attname='pid' AND NOT attisdropped")).rows;
    assert.deepEqual(metadata,[{attnotnull:true}]);
    const sql = fixtures + query.fromSql.replace('#{params.tenantId}', '$1');
    const main = (await db.query(sql,[11])).rows;
    assert.deepEqual(main.map(row => [row.pid,row.owner_name,row.customer_name]),[
      ['project-01','Active owner','Local customer'],
      ['project-02','待分配','Local customer'],
      ['project-03','待分配','Local customer'],
      ['project-04','待分配','Local customer'],
      ['project-05','待分配','Local customer'],
      ['project-06','待分配','Local customer'],
      ['project-07','fallback-user','Local customer'],
      ['project-08','待分配','Local customer'],
    ]);
    const foreign = (await db.query(sql,[22])).rows;
    assert.deepEqual(foreign.map(row => [row.pid,row.owner_name,row.customer_name]),[['foreign-project','Foreign owner','Foreign customer']]);
    assert.deepEqual((await db.query(sql,[33])).rows,[]);
    for (const row of [...main,...foreign]) {
      assert.deepEqual(Object.keys(row).sort(),['pid','project_code','project_name','customer_name','owner_name','delivery_stage','progress','delivery_date','budget','risk_summary','next_action'].sort());
      assert.equal(row.delivery_stage,'实施中');
    }
  } finally {
    await db.query('ROLLBACK');
    await db.end();
  }
});
