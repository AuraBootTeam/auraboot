import { constants, closeSync, fstatSync, lstatSync, mkdirSync, openSync, readFileSync,
  readdirSync, readlinkSync, realpathSync, renameSync, symlinkSync, writeFileSync } from 'node:fs';
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

export function fileStorageIdentity(manager, name, repo) {
  need(/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name), 'Invalid file storage runtime');
  const allocation = manager.requireAllocation(name), meta = manager.requireMeta(name);
  need(meta.AURA_RUNTIME_NAME === name && meta.AURA_RUNTIME_REPO === allocation.repo &&
    Number(meta.AURA_RUNTIME_SLOT) === allocation.slot && meta.AURA_RUNTIME_WORKSPACE === manager.root &&
    meta.AURA_RUNTIME_SOURCE_ROOT === repo && Boolean(meta.AURA_RUNTIME_CREATED_AT),
  'File storage runtime lease mismatch');
  const state = resolve(manager.state);
  directory(state); directory(join(state, 'runtimes'));
  directory(manager.runtimeDir(name));
  const stack = goldenStackState(state, name);
  directory(stack);
  return { schemaVersion: 1, runtime: name, repo, workspace: manager.root,
    allocationRepo: allocation.repo, slot: allocation.slot, createdAt: meta.AURA_RUNTIME_CREATED_AT,
    alias: join(stack, 'file-storage'), directory: join(state, 'runtime-data', name, 'files') };
}

function readOwner(path) {
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const info = fstatSync(fd); owned(info);
    need(info.isFile() && info.nlink === 1 && (info.mode & 0o077) === 0, 'Invalid or nonprivate file storage ownership marker');
    return JSON.parse(readFileSync(fd, 'utf8'));
  }
  finally { closeSync(fd); }
}

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

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action, name, repo, cli] = process.argv.slice(2);
    need(process.argv.length === 6 && ['prepare', 'verify'].includes(action), 'Invalid file storage arguments');
    const workspace = dirname(realpathSync(cli));
    const { RuntimeManager } = await import(pathToFileURL(join(workspace, 'tools/workspace-runtime/lib/runtime-manager.mjs')));
    const manager = new RuntimeManager({ workspaceRoot: workspace,
      stateDir: process.env.AURA_WORKSPACE_STATE_DIR || join(workspace, '.workspace') });
    console.log(JSON.stringify(manager.withLock(() => retainFileStorage(manager, name, realpathSync(repo),
      { prepare: action === 'prepare' }))));
  } catch (error) {
    console.error(`File storage retention refused: ${error.message}`);
    process.exitCode = 1;
  }
}
