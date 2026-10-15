#!/usr/bin/env node
const base = process.env.PERF_API_BASE;
if (!base || !process.env.AURABOOT_PASSWORD) throw new Error('Explicit PERF_API_BASE and AURABOOT_PASSWORD required');
async function call(path, body, token) {
  const response = await fetch(base + path, { headers: { 'content-type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { method: 'POST', body: JSON.stringify(body) } : {}) });
  const data = await response.json();
  if (!response.ok || data.code !== '0') throw new Error(`Fixture request failed: ${response.status} ${data.code}`);
  return data.data;
}
const { jwt } = await call('/api/auth/login', { email: 'admin@auraboot.com', password: process.env.AURABOOT_PASSWORD });
if (!jwt) throw new Error('Login did not return a token');
const before = await call('/api/dynamic/showcase_all_fields/list?pageNum=1&pageSize=20', null, jwt);
if (before.total !== 0) throw new Error('Fixture requires an explicitly initialized empty model; existing data is never overwritten');
for (let offset=0; offset<1000; offset+=100) {
  const rows = Array.from({length:100}, (_,n) => ({sc_name:`PERF baseline ${String(offset+n).padStart(5,'0')}`,sc_code:`PERF-${String(offset+n).padStart(5,'0')}`,sc_quantity:offset+n,sc_status:'draft'}));
  const result = await call('/api/dynamic/showcase_all_fields/batch', rows, jwt);
  if (result.success !== 100 || result.failed !== 0) throw new Error('Incomplete fixture batch');
}
const after = await call('/api/dynamic/showcase_all_fields/list?pageNum=1&pageSize=20', null, jwt);
if (after.total !== 1000 || after.records.length !== 20) throw new Error('Fixture count mismatch');
console.log('Created exactly 1000 deterministic fixture records via the real API');
