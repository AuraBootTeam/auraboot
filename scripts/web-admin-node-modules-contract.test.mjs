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
