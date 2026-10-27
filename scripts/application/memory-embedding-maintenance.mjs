#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, openSync, closeSync, unlinkSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
export function validateManifest(value) {
  if (!value || Object.keys(value).sort().join(',') !== 'agentCode,baseUrl,expectedSpace,pids,reason,tenantId') throw Error('exact manifest fields required');
  const url = new URL(value.baseUrl);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw Error('baseUrl must be an origin');
  if (!/^[1-9][0-9]*$/.test(String(value.tenantId)) || typeof value.tenantId !== 'string') throw Error('tenantId must be a decimal string');
  if (typeof value.agentCode !== 'string' || !value.agentCode.trim() || value.agentCode.length > 128) throw Error('agentCode required');
  if (!/^[a-f0-9]{64}$/.test(value.expectedSpace)) throw Error('expectedSpace required');
  if (typeof value.reason !== 'string' || !value.reason.trim() || value.reason.length > 512) throw Error('reason required');
  if (!Array.isArray(value.pids) || value.pids.length < 1 || value.pids.length > 100 || new Set(value.pids).size !== value.pids.length
      || value.pids.some(pid => typeof pid !== 'string' || !pid.trim() || pid.length > 128)) throw Error('1..100 unique explicit pids required');
  return { baseUrl: url.origin, tenantId: value.tenantId, agentCode: value.agentCode, expectedSpace: value.expectedSpace, reason: value.reason, pids: value.pids };
}

export async function run(manifest, checkpointPath, { token, resume = false, maxItems = 100, request = fetch } = {}) {
  const target = validateManifest(manifest), digest = hash(target);
  if (!token || !Number.isInteger(maxItems) || maxItems < 1 || maxItems > 100) throw Error('token and max-items 1..100 required');
  const lockPath = `${checkpointPath}.lock`;
  const lock = openSync(lockPath, 'wx', 0o600);
  let state;
  try {
    if (resume) {
      state = JSON.parse(readFileSync(checkpointPath, 'utf8'));
      if (state.schema !== 1 || state.manifestSha256 !== digest || !Array.isArray(state.completed)
          || new Set(state.completed.map(row => row.pid)).size !== state.completed.length
          || state.completed.some(row => !target.pids.includes(row.pid) || !['rebuilt', 'already_current'].includes(row.outcome))) throw Error('checkpoint identity mismatch');
    } else {
      if (existsSync(checkpointPath)) throw Error('checkpoint exists; use --resume');
      state = { schema: 1, manifestSha256: digest, completed: [], remaining: [...target.pids] };
    }
    const save = () => {
      const temporary = `${checkpointPath}.tmp`;
      writeFileSync(temporary, JSON.stringify(state, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
      renameSync(temporary, checkpointPath);
    };
    const api = async (path, body) => {
      const response = await request(`${target.baseUrl}/api/admin/memory/embeddings${path}`, {
        method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(180_000),
        redirect: 'error',
      });
      const result = await response.json();
      if (!response.ok || String(result.code) !== '0') throw Error(`maintenance rejected: HTTP ${response.status}, code ${String(result.code)}`);
      return result.data;
    };
    const inspect = async pid => {
      const row = await api(`/${encodeURIComponent(pid)}?agentCode=${encodeURIComponent(target.agentCode)}`);
      if (String(row.tenantId) !== target.tenantId || row.agentCode !== target.agentCode || row.pid !== pid || row.expectedSpace !== target.expectedSpace) throw Error('live target identity mismatch');
      return row;
    };
    // Reconcile every acknowledged item against current data before any new write.
    for (const row of state.completed) if ((await inspect(row.pid)).status !== 'current') throw Error('completed item changed; create a new reviewed manifest');
    state.remaining = target.pids.filter(pid => !state.completed.some(row => row.pid === pid));
    delete state.failure;
    save();
    let attempted = 0;
    try {
      for (const pid of [...state.remaining]) {
        if (attempted++ >= maxItems) break;
        const before = await inspect(pid);
        if (before.status === 'inactive') throw Error('inactive target; create a new reviewed manifest');
        const row = await api(`/${encodeURIComponent(pid)}/rebuild`, { agentCode: target.agentCode, expectedSpace: target.expectedSpace, reason: target.reason });
        if (row.pid !== pid || row.agentCode !== target.agentCode || row.expectedSpace !== target.expectedSpace || !['rebuilt', 'already_current'].includes(row.outcome)) throw Error('unexpected rebuild acknowledgement');
        if ((await inspect(pid)).status !== 'current') throw Error('rebuilt item is not current');
        state.completed.push({ pid, outcome: row.outcome, ...(row.auditSequence == null ? {} : { auditSequence: row.auditSequence }) });
        state.remaining = state.remaining.filter(value => value !== pid);
        save();
      }
    } catch (error) {
      // Do not retry ambiguous POSTs. Resume inspects and lets the server skip already-current rows.
      state.failure = error.message; save(); throw error;
    }
    return { ...state, status: state.remaining.length ? 'partial' : 'complete' };
  } finally { closeSync(lock); unlinkSync(lockPath); }
}

export async function main(args) {
  const options = {};
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if (!['--manifest', '--checkpoint', '--token-env', '--max-items', '--resume'].includes(key) || key in options) throw Error('unknown or duplicate option');
    options[key] = key === '--resume' ? true : args[++i];
  }
  if (!options['--manifest'] || !options['--checkpoint'] || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(options['--token-env'] ?? '')) throw Error('--manifest, --checkpoint and --token-env required');
  const result = await run(JSON.parse(readFileSync(resolve(options['--manifest']), 'utf8')), resolve(options['--checkpoint']), {
    token: process.env[options['--token-env']], resume: options['--resume'], maxItems: Number(options['--max-items'] ?? 100),
  });
  process.stdout.write(JSON.stringify(result) + '\n');
  return result.status === 'complete' ? 0 : 3;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
}
