import { createHash, randomUUID } from 'node:crypto';
import { constants, closeSync, fstatSync, lstatSync, mkdirSync, openSync, readFileSync,
  readdirSync, readlinkSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { pathToFileURL } from 'node:url';
import { goldenStackState } from './golden-stack-state.mjs';

const need = (condition, message) => { if (!condition) throw new Error(message); };
function stat(path) {
  try { return lstatSync(path); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
function owned(info) {
  need(typeof process.getuid === 'function' && info.uid === process.getuid() && (info.mode & 0o022) === 0,
    'File storage entry has unsafe ownership or permissions');
}
function directory(path, create = false) {
  if (!stat(path) && create) mkdirSync(path, { mode: 0o700 });
  const info = stat(path);
  need(info?.isDirectory() && !info.isSymbolicLink(), `File storage requires a real directory: ${path}`);
  owned(info);
}
function regularTree(path) {
  directory(path);
  for (const entry of readdirSync(path)) {
    const child = join(path, entry), info = lstatSync(child);
    need(!info.isSymbolicLink(), 'File storage payload may not contain symbolic links');
    if (info.isDirectory()) regularTree(child);
    else {
      need(info.isFile() && info.nlink === 1, 'File storage payload contains an unsupported or shared entry');
      owned(info);
    }
  }
}

export function fileStorageIdentity(manager, name, repo, { requireContainers = true } = {}) {
  need(/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name), 'Invalid file storage runtime');
  const allocation = manager.requireAllocation(name), meta = manager.requireMeta(name);
  need(meta.AURA_RUNTIME_NAME === name && meta.AURA_RUNTIME_REPO === allocation.repo &&
    Number(meta.AURA_RUNTIME_SLOT) === allocation.slot && meta.AURA_RUNTIME_WORKSPACE === manager.root &&
    meta.AURA_RUNTIME_SOURCE_ROOT === repo && Boolean(meta.AURA_RUNTIME_CREATED_AT),
  'File storage runtime lease mismatch');
  const state = resolve(manager.state);
  directory(state); directory(join(state, 'runtimes'));
  if (requireContainers) directory(manager.runtimeDir(name));
  const stack = goldenStackState(state, name);
  if (requireContainers) directory(stack);
  return { schemaVersion: 1, runtime: name, repo, workspace: manager.root,
    allocationRepo: allocation.repo, slot: allocation.slot, createdAt: meta.AURA_RUNTIME_CREATED_AT,
    alias: join(stack, 'file-storage'), directory: join(state, 'runtime-data', name, 'files') };
}

function privateBytes(path) {
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const info = fstatSync(fd); owned(info);
    need(info.isFile() && info.nlink === 1 && (info.mode & 0o077) === 0, 'Invalid or nonprivate file storage ownership marker');
    return readFileSync(fd);
  }
  finally { closeSync(fd); }
}

function readOwner(path) { return JSON.parse(privateBytes(path)); }

// Call under the existing Workspace lock. The alias remains the storage base path:
// existing FileEntity.localPath values are absolute and must keep their lexical prefix.
export function retainFileStorage(manager, name, repo, { prepare = false } = {}) {
  const identity = fileStorageIdentity(manager, name, repo);
  if (prepare) {
    manager.assertPortsStopped(name);
    manager.assertNoGradleClient(name);
  }
  const parent = dirname(identity.directory), root = dirname(parent), marker = join(parent, 'owner.json');
  directory(root, prepare);
  if (!stat(parent) && prepare) mkdirSync(parent, { mode: 0o700 });
  directory(parent);
  if (!stat(marker) && prepare) {
    // A pre-existing unowned nonempty directory is never adopted.
    need(readdirSync(parent).length === 0, 'Unowned persistent file storage requires review');
    writeFileSync(marker, JSON.stringify(identity, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  }
  need(isDeepStrictEqual(readOwner(marker), identity), 'Persistent file storage belongs to another lease');
  const alias = stat(identity.alias), target = stat(identity.directory);
  if (alias?.isSymbolicLink()) {
    need(readlinkSync(identity.alias) === identity.directory, 'File storage alias points to another directory');
  } else if (alias) {
    need(prepare && alias.isDirectory() && !target, 'Ambiguous disposable and persistent file storage');
    regularTree(identity.alias);
    renameSync(identity.alias, identity.directory);
  } else if (!target && prepare) {
    mkdirSync(identity.directory, { mode: 0o700 });
  }
  regularTree(identity.directory);
  if (prepare && !stat(identity.alias)) symlinkSync(identity.directory, identity.alias);
  need(stat(identity.alias)?.isSymbolicLink() && readlinkSync(identity.alias) === identity.directory &&
    realpathSync(identity.alias) === realpathSync(identity.directory), 'Persistent file storage alias is missing');
  return identity;
}

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function transitionRows(text) {
  need(typeof text === 'string', 'Invalid source transition snapshot');
  const rows = text.split('\n').filter(Boolean).map(line => {
    const fields = line.split('\t'); need(fields.length === 5, 'Invalid source transition snapshot');
    const [key, root, branch, commit, fingerprint] = fields;
    return { key, root, branch, commit, fingerprint };
  });
  need(rows.length > 0 && new Set(rows.map(row => row.key)).size === rows.length, 'Invalid source transition snapshot');
  return rows;
}
function matchesMetadata(manager, content, values) {
  need(typeof content === 'string' && typeof manager.metadataContent === 'function', 'Controller metadata serialization is required');
  const actual = new Map(content.split('\n').filter(Boolean).map(line => [line.split('=', 1)[0], line]));
  return manager.metadataContent(values).split('\n').filter(Boolean).every(line => actual.get(line.split('=', 1)[0]) === line);
}

/** Explicit stopped-store CAS after a completed governed primary migration; never startup repair. */
export function rebindFileStorageSource(manager, name, repo, { generation, expected, dryRun = false } = {}) {
  need(/^[0-9a-f-]{36}$/.test(generation || ''), 'An exact completed primary generation is required');
  manager.assertSourceRefreshStopped(name);
  need(manager.verifySources(name, false), 'Current runtime sources have drifted');
  const identity = fileStorageIdentity(manager, name, repo, { requireContainers: false });
  const parent = dirname(identity.directory), marker = join(parent, 'owner.json');
  directory(dirname(parent)); directory(parent); regularTree(identity.directory);
  const markerBytes = privateBytes(marker), owner = JSON.parse(markerBytes);
  const history = JSON.parse(privateBytes(join(manager.evidenceDir(name), 'source-history.json')));
  need(history.schemaVersion === 2 && history.runtime === name && Array.isArray(history.generations), 'Invalid completed source history');
  const entries = history.generations.filter(entry => entry.id === generation);
  need(entries.length === 1, 'The completed primary generation is unavailable');
  const entry = entries[0], primary = entry.primaryTransition;
  need(entry.version === 4 && entry.runtime === name && primary && entry.runtimeRetention === 'metadata-only'
    && typeof entry.completedAt === 'string' && Number.isFinite(Date.parse(entry.completedAt)), 'A completed primary transition is required');
  need(typeof entry.before === 'string' && typeof entry.after === 'string'
    && digest(entry.before) === entry.beforeHash && digest(entry.after) === entry.afterHash
    && digest(privateBytes(manager.sourceFile(name))) === entry.afterHash, 'Source transition digest mismatch');
  const before = transitionRows(entry.before).find(row => row.key === primary.previous?.key);
  const after = transitionRows(entry.after).find(row => row.key === primary.previous?.key);
  need(before && after && isDeepStrictEqual(before, primary.previous)
    && isDeepStrictEqual(primary.target, { root: after.root, branch: after.branch, commit: after.commit })
    && before.root !== after.root && after.root === repo && after.fingerprint === 'clean'
    && /^codex\//.test(after.branch) && /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(after.commit)
    && primary.specification === `${after.key}=${after.root}`, 'Primary source history identity mismatch');
  need(primary.proof && typeof primary.proof.task === 'string' && primary.proof.task
    && typeof primary.proof.owner === 'string' && primary.proof.owner
    && /^[0-9a-f]{64}$/.test(primary.proof.registryDigest || ''), 'Primary task proof is unavailable');
  need(isDeepStrictEqual(primary.allocation, { name, repo: identity.allocationRepo, slot: identity.slot }), 'Source transition allocation mismatch');
  const lease = { AURA_RUNTIME_NAME: name, AURA_RUNTIME_REPO: identity.allocationRepo, AURA_RUNTIME_SLOT: identity.slot,
    AURA_RUNTIME_CREATED_AT: identity.createdAt, AURA_RUNTIME_WORKSPACE: identity.workspace };
  need(matchesMetadata(manager, primary.beforeMeta, { ...lease, AURA_RUNTIME_SOURCE_ROOT: before.root, AURA_RUNTIME_SOURCE_BRANCH: before.branch })
    && matchesMetadata(manager, primary.afterMeta, { ...lease, AURA_RUNTIME_SOURCE_ROOT: after.root,
      AURA_RUNTIME_SOURCE_BRANCH: after.branch, AURA_RUNTIME_SOURCE_COMMIT: after.commit }), 'Source transition lease mismatch');
  need(isDeepStrictEqual(owner, identity) || isDeepStrictEqual(owner, { ...identity, repo: before.root }), 'Persistent file storage belongs to another lease');
  const markerDigest = digest(markerBytes), changed = !isDeepStrictEqual(owner, identity);
  const result = { runtime: name, generation, expected: markerDigest, changed, beforeSource: owner.repo, afterSource: repo };
  if (dryRun) return result;
  need(/^[0-9a-f]{64}$/.test(expected || '') && expected === markerDigest, 'File storage owner compare-and-swap mismatch');
  if (changed) {
    // The runtime registry lock protects cooperative writers; re-read before replacing the private marker.
    need(digest(privateBytes(marker)) === markerDigest, 'File storage owner changed during rebind');
    const temporary = join(parent, `owner.${randomUUID()}.tmp`);
    try {
      writeFileSync(temporary, JSON.stringify(identity, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
      renameSync(temporary, marker);
    } finally { if (stat(temporary)) rmSync(temporary); }
  }
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action, name, repo, cli, generation, mode, expected] = process.argv.slice(2);
    const rebind = action === 'rebind-source';
    need(rebind ? ((process.argv.length === 8 && mode === '--dry-run') || (process.argv.length === 9 && mode === '--expect'))
      : (process.argv.length === 6 && ['prepare', 'verify'].includes(action)), 'Invalid file storage arguments');
    const workspace = dirname(realpathSync(cli));
    const { RuntimeManager } = await import(pathToFileURL(join(workspace, 'tools/workspace-runtime/lib/runtime-manager.mjs')));
    const manager = new RuntimeManager({ workspaceRoot: workspace,
      stateDir: process.env.AURA_WORKSPACE_STATE_DIR || join(workspace, '.workspace') });
    console.log(JSON.stringify(manager.withLock(() => rebind
      ? rebindFileStorageSource(manager, name, realpathSync(repo), { generation, dryRun: mode === '--dry-run', expected })
      : retainFileStorage(manager, name, realpathSync(repo), { prepare: action === 'prepare' }))));
  } catch (error) {
    console.error(`File storage retention refused: ${error.message}`);
    process.exitCode = 1;
  }
}
