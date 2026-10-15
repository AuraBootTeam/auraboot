#!/usr/bin/env node
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const base = process.env.PERF_API_BASE;
const output = process.env.PERF_OUTPUT;
if (!base || !output || !process.env.AURABOOT_PASSWORD) throw new Error('PERF_API_BASE, PERF_OUTPUT and AURABOOT_PASSWORD are required');
const samples = Number(process.env.PERF_SAMPLES || 40);
if (!Number.isInteger(samples) || samples < 20) throw new Error('At least 20 samples required');
const login = await fetch(`${base}/api/auth/login`, {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:process.env.PERF_EMAIL || 'admin@auraboot.com',password:process.env.AURABOOT_PASSWORD})});
const auth = await login.json();
if (!login.ok || auth.code !== '0' || !auth.data?.jwt) throw new Error('Login failed');
const headers = {Authorization:`Bearer ${auth.data.jwt}`};
const paths = ['/api/menu/user','/api/auth/me','/api/dashboards/workbench','/api/workbench/stats','/api/dynamic/showcase_all_fields_list/list?pageNum=1&pageSize=20','/api/dynamic/showcase_all_fields_list/list?pageNum=2&pageSize=20','/api/dynamic/showcase_all_fields_list/list?pageNum=1&pageSize=20&keyword=PERF'];
const report = {commit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),base,samples,at:new Date().toISOString(),conditions:{concurrency:1,warmup:5},endpoints:[]};
writeFileSync(output,JSON.stringify({...report,status:'running'},null,2));
for (const path of paths) {
 const rows=[];
 for (let i=0;i<samples+5;i++) {
  const start=performance.now(); const response=await fetch(base+path,{headers}); const body=await response.json(); const ms=performance.now()-start;
  if (!response.ok || body.code!=='0') throw new Error(`Failed ${path}: ${response.status} ${body.code}`);
  const row={ms,sqlCount:Number(response.headers.get('x-sql-count')),traceId:response.headers.get('x-trace-id'),total:body.data?.total,records:body.data?.records?.length};
  if(i>=5) rows.push(row);
 }
 const sorted=rows.map(x=>x.ms).sort((a,b)=>a-b);
 const p=q=>sorted[Math.ceil(q*sorted.length)-1];
 const entry={path,p50:p(.5),p95:p(.95),p99:p(.99),sqlMin:Math.min(...rows.map(x=>x.sqlCount)),sqlMax:Math.max(...rows.map(x=>x.sqlCount)),rows};
 report.endpoints.push(entry);console.log(JSON.stringify({...entry,rows:undefined}));
 writeFileSync(output,JSON.stringify({...report,status:'running'},null,2));
}
writeFileSync(output,JSON.stringify({...report,status:'measured'},null,2));
