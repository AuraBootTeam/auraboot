import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

const helper = new URL('./lib/web-admin-node-modules.sh', import.meta.url).pathname;

function write(root, relative, content, mode = 0o644) {
  const target = path.join(root, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, { mode });
}

function fixture({ brokenRouter = false, brokenNative = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aura-node-modules-'));
  const modules = path.join(root, 'web-admin', 'node_modules');
  write(modules, 'react/index.js', 'module.exports = {};\n');
  write(modules, 'react-dom/client.js', 'module.exports = {};\n');
  write(modules, '@tailwindcss/vite/dist/index.mjs', 'export {};\n');
  write(modules, 'tailwindcss/index.css', '');
  write(modules, '.bin/react-router', '#!/bin/sh\nexit 0\n', 0o755);
  if (!brokenRouter) write(modules, '@react-router/dev/bin.js', '#!/usr/bin/env node\n');
  write(
    modules,
    '@tailwindcss/vite/node_modules/lightningcss/package.json',
    JSON.stringify({ name: 'lightningcss', main: 'index.js' }),
  );
  write(
    modules,
    '@tailwindcss/vite/node_modules/lightningcss/index.js',
    brokenNative
      ? "throw Object.assign(new Error('wrong ELF class'), { code: 'ERR_DLOPEN_FAILED' });\n"
      : "exports.transform = () => ({ code: Buffer.from('.a{color:red}') });\n",
  );
  return { root, modules };
}

function usable(modules) {
  return spawnSync('bash', ['-c', 'source "$1"; web_admin_node_modules_usable "$2"', 'bash', helper, modules]);
}

test('accepts a complete dependency view whose native probe executes', () => {
  const value = fixture();
  try {
    assert.equal(usable(value.modules).status, 0);
  } finally {
    fs.rmSync(value.root, { recursive: true, force: true });
  }
});

test('rejects a missing @react-router/dev executable target', () => {
  const value = fixture({ brokenRouter: true });
  try {
    assert.notEqual(usable(value.modules).status, 0);
  } finally {
    fs.rmSync(value.root, { recursive: true, force: true });
  }
});

test('rejects a dependency view whose lightningcss native load fails', () => {
  const value = fixture({ brokenNative: true });
  try {
    assert.notEqual(usable(value.modules).status, 0);
  } finally {
    fs.rmSync(value.root, { recursive: true, force: true });
  }
});

function capsuleFixture() {
  const value = fixture();
  const capsule = path.join(fs.realpathSync(value.root), 'f'.repeat(64));
  const store = path.join(capsule, '.pnpm');
  const packages = path.join(store, 'packages', 'node_modules');
  fs.mkdirSync(path.dirname(packages), { recursive: true });
  fs.renameSync(value.modules, packages);
  const view = path.join(capsule, 'node_modules');
  fs.mkdirSync(view);
  for (const entry of fs.readdirSync(packages)) {
    fs.symlinkSync(path.join(packages, entry), path.join(view, entry));
  }
  fs.symlinkSync(view, value.modules);
  const receipt = {
    schemaVersion: 1, dependencyKey: `sha256:${path.basename(capsule)}`,
    dependenciesDir: view, virtualStoreDir: store,
    platform: process.platform, arch: process.arch, nodeAbi: process.versions.modules,
  };
  const marker = path.join(capsule, '.aura-dependency-capsule.json');
  fs.writeFileSync(marker, JSON.stringify(receipt));
  return { ...value, marker, receipt };
}

test('reuses a published capsule with owner-verified virtual-store links', () => {
  const value = capsuleFixture();
  try { assert.equal(usable(value.modules).status, 0); }
  finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

test('rejects a capsule receipt pointing at a different dependency view or store', () => {
  const value = capsuleFixture();
  try {
    fs.writeFileSync(value.marker, JSON.stringify({ ...value.receipt, dependenciesDir: '/another/view' }));
    assert.notEqual(usable(value.modules).status, 0);
    fs.writeFileSync(value.marker, JSON.stringify({ ...value.receipt, virtualStoreDir: '/another/store' }));
    assert.notEqual(usable(value.modules).status, 0);
  } finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

function rendererFixture({ missingTsx = false, brokenTsx = false, missingCli = false, missingPlaywright = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aura-renderer-receipt-'));
  const webAdmin = path.join(root, 'web-admin');
  if (!missingTsx) {
    write(webAdmin, 'node_modules/.bin/tsx', brokenTsx ? '#!/bin/sh\nexit 3\n' : '#!/bin/sh\necho tsx 4.22.4\n', 0o755);
  }
  if (!missingCli) write(webAdmin, 'app/framework/smart/report-export/cli.ts', 'export {};\n');
  if (!missingPlaywright) {
    write(webAdmin, 'node_modules/@playwright/test/package.json', JSON.stringify({ name: '@playwright/test', version: '1.0.0', main: 'index.js' }));
    write(webAdmin, 'node_modules/@playwright/test/index.js', 'module.exports = {};\n');
  }
  return webAdmin;
}
function receipt(webAdmin) {
  return spawnSync('bash', ['-c', 'source "$1"; web_admin_report_renderer_receipt "$2"', 'bash', helper, webAdmin], { encoding: 'utf8' });
}
test('emits the indexed renderer receipt when prerequisites are co-located', () => {
  const webAdmin = rendererFixture();
  try {
    const r = receipt(webAdmin);
    assert.equal(r.status, 0, r.stderr);
    const lines = r.stdout.trim().split('\n');
    assert.deepEqual(lines.map(l => l.split('=')[0]), [
      'AURABOOT_REPORT_EXPORT_RENDERER_ENABLED',
      'AURABOOT_REPORT_EXPORT_RENDERER_COMMAND_0',
      'AURABOOT_REPORT_EXPORT_RENDERER_COMMAND_1',
      'AURABOOT_REPORT_EXPORT_RENDERER_TIMEOUT_SECONDS',
    ]);
    assert.match(lines[1], /node_modules\/\.bin\/tsx$/);
    assert.match(lines[2], /report-export\/cli\.ts$/);
  } finally { fs.rmSync(webAdmin, { recursive: true, force: true }); }
});
for (const [name, flag] of [
  ['missing tsx', { missingTsx: true }],
  ['non-runnable tsx', { brokenTsx: true }],
  ['missing cli.ts', { missingCli: true }],
  ['missing @playwright/test', { missingPlaywright: true }],
]) test(`refuses the renderer receipt on ${name}`, () => {
  const webAdmin = rendererFixture(flag);
  try { assert.notEqual(receipt(webAdmin).status, 0); }
  finally { fs.rmSync(webAdmin, { recursive: true, force: true }); }
});

// ---- a view linked from another checkout must stay consistent through that link ----------------

function seedable(modules) {
  return spawnSync('bash', ['-c', 'source "$1"; web_admin_node_modules_seedable "$2"', 'bash', helper, modules]);
}
// Two real shim forms: one reaches the package through the view, the other from the path the shim was
// invoked by. Which form an install gets is not tied to a pnpm version, so the rule reads the text.
const shimThroughView = '#!/bin/sh\nbasedir=$(dirname "$0")\nexec node "$basedir/../@playwright/test/cli.js" "$@"\n';
const shimThroughInvoker = '#!/bin/sh\nbasedir=$(dirname "$0")\nexec node "$basedir/../../../node_modules/.pnpm/@playwright+test@1.60.0/node_modules/@playwright/test/cli.js" "$@"\n';

/** A dependency view under <root>/web-admin/node_modules with the given .bin entries. */
function view(shims, { link = false } = {}) {
  // The real path: `git worktree list` prints real paths, and the selection compares strings.
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'aura-node-modules-seed-')));
  const modules = path.join(root, 'web-admin', 'node_modules');
  write(modules, 'react/index.js', 'module.exports = {};\n');
  write(modules, '@playwright/test/cli.js', '#!/usr/bin/env node\n');
  write(modules, '@playwright/test/package.json', JSON.stringify({ name: '@playwright/test', bin: { playwright: 'cli.js' } }));
  // Only link entries need the directory up front; a view without shims must really have no .bin.
  if (link && Object.keys(shims).length) fs.mkdirSync(path.join(modules, '.bin'), { recursive: true });
  for (const [name, target] of Object.entries(shims)) {
    if (link) fs.symlinkSync(target, path.join(modules, '.bin', name));
    else write(modules, `.bin/${name}`, target, 0o755);
  }
  return { root, modules };
}

test('a view whose shims reach their package through the view may be linked from another checkout', () => {
  const value = view({ playwright: shimThroughView, 'react-router': shimThroughView });
  try { assert.equal(seedable(value.modules).status, 0); }
  finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

test('a published capsule, whose shims are links into its packages, may be linked from another checkout', () => {
  const value = view({ playwright: '../@playwright/test/cli.js' }, { link: true });
  try { assert.equal(seedable(value.modules).status, 0); }
  finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

test('a view without a .bin directory may be linked from another checkout', () => {
  const value = view({});
  try {
    assert.ok(!fs.existsSync(path.join(value.modules, '.bin')), 'the fixture must not carry a .bin directory');
    assert.equal(seedable(value.modules).status, 0);
  } finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

test('a view with one shim that reaches its package from the invoking path is not linked from another checkout', () => {
  const value = view({ playwright: shimThroughInvoker, 'react-router': shimThroughView });
  try {
    const result = seedable(value.modules);
    assert.notEqual(result.status, 0);
    assert.equal(String(result.stderr), '', 'the predicate refuses silently; an error means it did not run');
  }
  finally { fs.rmSync(value.root, { recursive: true, force: true }); }
});

// The selection lives in the stack script, which cannot be sourced as a whole: cut its two shell
// functions out as written and run them against the real library. Only the lock contract is
// replaced, because it needs real lockfiles; what is under test is the order and the filter.
const stackScript = new URL('./oss-golden-stack.sh', import.meta.url).pathname;
function shellFunction(name) {
  const lines = fs.readFileSync(stackScript, 'utf8').split('\n');
  const start = lines.findIndex(line => line.startsWith(`${name}() {`));
  assert.ok(start >= 0, `oss-golden-stack.sh must define ${name}()`);
  const end = lines.findIndex((line, index) => index > start && line === '}');
  assert.ok(end > start, `${name}() must be closed`);
  return lines.slice(start, end + 1).join('\n');
}

/** A git checkout: `git worktree list` is what the selection walks. */
function checkout(shims, options) {
  const value = view(shims, options);
  const git = args => spawnSync('git', ['-C', value.root, '-c', 'user.name=test', '-c', 'user.email=test@example.com', ...args]);
  git(['init', '-q']);
  git(['commit', '-q', '--allow-empty', '-m', 'init']);
  return value;
}

/** Runs the real selection for `repo`. Views in `matching` pass the lock contract; nothing else does. */
function selectSeed({ repo, canonical, matching }) {
  const functions = path.join(repo, '.seed-functions.sh');
  fs.writeFileSync(functions, ['web_admin_node_modules_seed_candidate', 'web_admin_node_modules_seed'].map(shellFunction).join('\n'));
  return spawnSync('bash', ['-c', [
    'source "$1"',
    'web_admin_node_modules_matches_checkout() { case " $SEED_TEST_MATCHING " in *" $1 "*) return 0;; *) return 1;; esac; }',
    'source "$2"',
    'web_admin_node_modules_seed',
  ].join('\n'), 'bash', helper, functions], {
    encoding: 'utf8', env: { ...process.env, REPO_ROOT: repo, CANONICAL: canonical, SEED_TEST_MATCHING: matching.join(' ') },
  });
}

test('the selection links the canonical view when it stays consistent through the link', () => {
  const canonical = view({ playwright: '../@playwright/test/cli.js' }, { link: true });
  const repo = checkout({});
  try {
    const result = selectSeed({ repo: repo.root, canonical: canonical.root, matching: [canonical.modules] });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), canonical.modules);
  } finally {
    for (const value of [canonical, repo]) fs.rmSync(value.root, { recursive: true, force: true });
  }
});

test('the selection skips a matching view that breaks through the link and takes the next consistent one', () => {
  const canonical = view({ playwright: shimThroughInvoker });
  const repo = checkout({});
  const other = path.join(repo.root, '..', `${path.basename(repo.root)}-other`);
  const added = spawnSync('git', ['-C', repo.root, 'worktree', 'add', '-q', '-b', 'seed-other', other]);
  assert.equal(added.status, 0, `git worktree add failed: ${added.stderr}`);
  const otherView = path.join(other, 'web-admin', 'node_modules');
  write(otherView, 'react/index.js', 'module.exports = {};\n');
  write(otherView, '.bin/playwright', shimThroughView, 0o755);
  try {
    const result = selectSeed({ repo: repo.root, canonical: canonical.root, matching: [canonical.modules, otherView] });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), otherView);
  } finally {
    spawnSync('git', ['-C', repo.root, 'worktree', 'remove', '--force', other]);
    for (const value of [canonical, repo]) fs.rmSync(value.root, { recursive: true, force: true });
  }
});

test('the selection finds no seed when every matching view breaks through the link', () => {
  const canonical = view({ playwright: shimThroughInvoker });
  const repo = checkout({});
  try {
    const result = selectSeed({ repo: repo.root, canonical: canonical.root, matching: [canonical.modules] });
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '', 'the selection reports nothing when it finds no seed');
  } finally {
    for (const value of [canonical, repo]) fs.rmSync(value.root, { recursive: true, force: true });
  }
});

test('a missing seed makes step 7/9 install from the lockfile in this checkout', () => {
  const script = fs.readFileSync(stackScript, 'utf8');
  const install = script.indexOf('node_modules_seed="$(web_admin_node_modules_seed || true)"');
  assert.ok(install >= 0, 'step 7/9 must ask the selection for a seed');
  const fallback = script.indexOf('pnpm --filter \'auraboot-app...\' install --frozen-lockfile', install);
  assert.ok(fallback > install, 'step 7/9 must install from the lockfile when the selection finds nothing');
});
