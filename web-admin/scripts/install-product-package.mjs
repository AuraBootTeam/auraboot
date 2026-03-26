#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execSync } from 'node:child_process';
import YAML from 'yaml';
import { request } from '@playwright/test';

const args = process.argv.slice(2);

function getArg(name, defaultValue) {
  const index = args.findIndex((arg) => arg === name || arg.startsWith(`${name}=`));
  if (index < 0) return defaultValue;

  const inline = args[index];
  if (inline.includes('=')) return inline.split('=').slice(1).join('=');

  const next = args[index + 1];
  if (!next || next.startsWith('--')) return 'true';
  return next;
}

function boolArg(name, defaultValue) {
  const value = getArg(name, undefined);
  if (value === undefined) return defaultValue;
  return String(value).toLowerCase() !== 'false';
}

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
const manifestPath = path.resolve(repoRoot, getArg('--manifest', 'docs/dog_fooding/quarry/product-package/quarry-management-suite.product-package.yaml'));
const mode = getArg('--mode', 'demo');
const baseURL = getArg('--base-url', 'http://localhost:5173');
const storageState = path.resolve(repoRoot, getArg('--storage-state', 'web-admin/tests/storage/admin.json'));
const autoSeed = boolArg('--auto-seed', true);

function readManifest(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Manifest not found: ${filePath}`);
  }
  const content = fs.readFileSync(filePath, 'utf-8');
  const manifest = YAML.parse(content);
  if (!manifest || typeof manifest !== 'object') {
    throw new Error(`Invalid manifest content: ${filePath}`);
  }
  return manifest;
}

function validateManifest(manifest) {
  const requiredTopLevel = ['id', 'name', 'version', 'plugins'];
  for (const key of requiredTopLevel) {
    if (!manifest[key]) {
      throw new Error(`Manifest missing required field: ${key}`);
    }
  }
  if (!Array.isArray(manifest.plugins) || manifest.plugins.length === 0) {
    throw new Error('Manifest plugins must be a non-empty array');
  }
  for (const plugin of manifest.plugins) {
    if (!plugin?.name) {
      throw new Error('Each plugin entry must include a name');
    }
  }
}

function resolveInstallOrder(manifest) {
  const pluginNames = manifest.plugins.map((p) => p.name);
  const declaredOrder = manifest?.operations?.installOrder;
  if (!Array.isArray(declaredOrder) || declaredOrder.length === 0) {
    return pluginNames;
  }

  const missing = pluginNames.filter((name) => !declaredOrder.includes(name));
  return [...declaredOrder, ...missing];
}

function resolveSeedCommand(manifest, installMode) {
  const enabled = manifest?.seed?.runOnInstall?.[installMode] === true;
  if (!enabled) return null;
  const command = manifest?.seed?.runCommand?.[installMode];
  if (!command || typeof command !== 'string') return null;
  return command;
}

async function importPlugin(api, pluginName) {
  const pluginPath = path.resolve(repoRoot, 'plugins', pluginName);
  if (!fs.existsSync(pluginPath)) {
    throw new Error(`Plugin directory not found: ${pluginPath}`);
  }

  const payload = {
    path: pluginPath,
    conflictStrategy: 'OVERWRITE',
    autoPublishModels: true,
    autoPublishFields: true,
    autoPublishCommands: true,
    autoPublishPages: true,
  };

  const response = await api.post('/api/plugins/import/import-directory-sync', {
    data: payload,
    headers: { 'Content-Type': 'application/json' },
    timeout: 600000,
  });

  const body = await response.json().catch(() => ({}));
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);

  if (!success) {
    const raw = JSON.stringify(body).slice(0, 1000);
    throw new Error(`Import failed for ${pluginName}: HTTP ${response.status()} ${raw}`);
  }

  return data;
}

async function runPostChecks(api, manifest) {
  const checks = manifest?.operations?.postChecks;
  if (!Array.isArray(checks) || checks.length === 0) return;

  for (const check of checks) {
    if (typeof check !== 'string') continue;
    if (check.includes('/actuator/health')) {
      const health = await api.get('http://localhost:6443/actuator/health');
      if (!health.ok()) {
        throw new Error(`Post check failed: ${check}`);
      }
    }
  }
}

async function main() {
  if (!['demo', 'production'].includes(mode)) {
    throw new Error(`Unsupported mode: ${mode}. Use demo or production.`);
  }
  if (!fs.existsSync(storageState)) {
    throw new Error(`Storage state not found: ${storageState}. Run ./scripts/reset-and-init.sh first.`);
  }

  const manifest = readManifest(manifestPath);
  validateManifest(manifest);

  const installOrder = resolveInstallOrder(manifest);

  console.log(`[package] id=${manifest.id} version=${manifest.version} mode=${mode}`);
  console.log(`[package] manifest=${manifestPath}`);
  console.log(`[package] install order: ${installOrder.join(', ')}`);

  const api = await request.newContext({
    baseURL,
    storageState,
    extraHTTPHeaders: {
      'Content-Type': 'application/json',
    },
  });

  const installed = [];
  try {
    for (const pluginName of installOrder) {
      console.log(`[package] importing plugin: ${pluginName}`);
      await importPlugin(api, pluginName);
      installed.push(pluginName);
      console.log(`[package] imported: ${pluginName}`);
    }

    await runPostChecks(api, manifest);

    const seedCommand = resolveSeedCommand(manifest, mode);
    if (autoSeed && seedCommand) {
      console.log(`[package] running seed command: ${seedCommand}`);
      execSync(seedCommand, {
        cwd: repoRoot,
        stdio: 'inherit',
        env: process.env,
      });
    } else {
      console.log('[package] seed skipped');
    }

    console.log('[package] installation completed');
    console.log(JSON.stringify({ packageId: manifest.id, mode, installedPlugins: installed }, null, 2));
  } finally {
    await api.dispose();
  }
}

main().catch((error) => {
  console.error('[package] installation failed:', error.message);
  process.exit(1);
});
