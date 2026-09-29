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
