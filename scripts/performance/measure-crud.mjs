#!/usr/bin/env node
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
const base = process.env.PERF_API_BASE;
const output = process.env.PERF_OUTPUT;
if (!base || !output || !process.env.AURABOOT_PASSWORD) throw new Error('PERF_API_BASE, PERF_OUTPUT and AURABOOT_PASSWORD are required');
const samples = Number(process.env.PERF_SAMPLES || 20);
if (!Number.isInteger(samples) || samples < 20) throw new Error('At least 20 samples required');
const login = await fetch(`${base}/api/auth/login`, {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:'admin@auraboot.com',password:process.env.AURABOOT_PASSWORD})});
const auth = await login.json();
if (!login.ok || auth.code !== '0' || !auth.data?.jwt) throw new Error('Login failed');
const headers = {Authorization:`Bearer ${auth.data.jwt}`,'content-type':'application/json'};
const rows={create:[],update:[],detail:[],delete:[]};
const report={commit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),base,at:new Date().toISOString(),samples,concurrency:1,warmup:3,rows};
writeFileSync(output,JSON.stringify({...report,status:'running'},null,2));
async function call(path,payload,operation,keep) {
 const start=performance.now();const response=await fetch(base+path,{headers,...(payload?{method:'POST',body:JSON.stringify(payload)}:{})});const data=await response.json();const ms=performance.now()-start;
 if(!response.ok||data.code!=='0'||data.data?.success===false)throw new Error(`Failed ${operation}: ${response.status} ${JSON.stringify(data)}`);
 const sql=response.headers.get('x-sql-count');if(sql===null)throw new Error('SQL count header missing');
 if(keep)rows[operation].push({ms,sqlCount:Number(sql),traceId:response.headers.get('x-trace-id')});return data;
}
const prefix=`PERF-CRUD-${Date.now()}`;
for(let i=0;i<samples+3;i++) {
 const keep=i>=3;const name=`${prefix}-${i}`;
 const created=await call('/api/meta/commands/execute/sc:create_showcase',{operationType:'create',payload:{sc_name:name,sc_quantity:37}},'create',keep);
 const pid=created.data?.data?.recordPid;if(!pid)throw new Error('Missing created record pid');
 await call('/api/meta/commands/execute/sc:update_showcase',{operationType:'update',targetRecordPid:pid,payload:{sc_name:`${name}-edited`,sc_quantity:38}},'update',keep);
 const detail=await call(`/api/dynamic/showcase_all_fields/${pid}`,null,'detail',keep);
 if(detail.data?.sc_name!==`${name}-edited`||detail.data?.sc_quantity!==38)throw new Error('Update did not persist');
 await call('/api/meta/commands/execute/sc:delete_showcase',{operationType:'delete',targetRecordPid:pid},'delete',keep);
 const absent=await fetch(`${base}/api/dynamic/showcase_all_fields/${pid}`,{headers});if(absent.status!==404)throw new Error(`Deleted record still accessible: ${absent.status}`);
 writeFileSync(output,JSON.stringify({...report,status:'running'},null,2));
}
const summary=Object.fromEntries(Object.entries(rows).map(([operation,values])=>{const sorted=values.map(x=>x.ms).sort((a,b)=>a-b);return[operation,{p50:sorted[Math.ceil(sorted.length*.5)-1],p95:sorted[Math.ceil(sorted.length*.95)-1],sqlMin:Math.min(...values.map(x=>x.sqlCount)),sqlMax:Math.max(...values.map(x=>x.sqlCount))}]}));
writeFileSync(output,JSON.stringify({...report,status:'measured',summary},null,2));console.log(JSON.stringify(summary,null,2));
