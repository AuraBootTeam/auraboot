#!/usr/bin/env node
/**
 * Guard a hybrid/config plugin import against the classic source/config/runtime/schema drift:
 * - verifies plugin.json and local backend jar
 * - inspects META-INF/extensions.idx when present
 * - authenticates once and proves the token against /tenant-selection/my-spaces
 * - reports the currently hot-loaded PF4J jar path/state/SHA
 * - optionally hot-loads the jar and/or imports config via import-directory-sync {path}
 * - optionally checks a runtime page schema after import
 *
 * Defaults are read-only. Use --hotload-upload and/or --import for mutations.
 */

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { basename, isAbsolute, resolve } from 'node:path';

const DEFAULT_BACKEND = process.env.AURA_API_URL || process.env.AURA_BE_BASE || 'http://127.0.0.1:6443';
const DEFAULT_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const DEFAULT_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const raw = argv[i];
    if (!raw.startsWith('--')) {
      args._.push(raw);
      continue;
    }
    const eq = raw.indexOf('=');
    const key = eq >= 0 ? raw.slice(2, eq) : raw.slice(2);
    const value =
      eq >= 0 ? raw.slice(eq + 1) : argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
    if (args[key] === undefined) args[key] = value;
    else if (Array.isArray(args[key])) args[key].push(value);
    else args[key] = [args[key], value];
  }
  return args;
}

function values(args, key) {
  const raw = args[key];
  if (raw === undefined || raw === false) return [];
  return Array.isArray(raw) ? raw.map(String) : [String(raw)];
}

function usage() {
  console.log(`Usage:
  scripts/dev/plugin-runtime-import-guard.mjs --plugin /abs/plugin-root [options]

Options:
  --backend <url>             Backend base URL (default: AURA_API_URL or ${DEFAULT_BACKEND})
  --token <jwt>               JWT; otherwise AURA_TOKEN or login email/password is used
  --email <email>             Login email (default: ADMIN_EMAIL or ${DEFAULT_EMAIL})
  --password <password>       Login password (default: ADMIN_PASSWORD or Test2026x)
  --tenant <name>             Optional business tenant name/displayName selector
  --expect-handler <code>     Required runtime command type; repeatable
  --expect-extension <text>   Required text in META-INF/extensions.idx; repeatable
  --page-key <key>            Runtime page schema key to verify
  --page-field <field>        Required field in runtime page schema; repeatable
  --hotload-upload            Upload and hot-load the local backend jar before import
  --import                    Run import-directory-sync using JSON {path}
  --offline-metadata-only      Validate plugin.json/local artifact metadata without contacting backend
  --conflict-strategy <name>  Import conflict strategy (default: OVERWRITE)
  --defer-reference-validation  Pass deferReferenceValidation=true to import
  --json                      Print machine-readable JSON only
  --help                      Show this help

Defaults are read-only unless --hotload-upload or --import is passed.
`);
}

function normalizeBackend(value) {
  return String(value || DEFAULT_BACKEND).replace(/\/+$/, '');
}

function readJsonFile(path, label) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    throw new Error(`Failed to read ${label} at ${path}: ${error.message}`);
  }
}

function sha256(path) {
  const hash = createHash('sha256');
  hash.update(readFileSync(path));
  return hash.digest('hex');
}

function readZipEntry(path, entry) {
  try {
    return execFileSync('unzip', ['-p', path, entry], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    return '';
  }
}

async function fetchJson(baseUrl, path, { method = 'GET', token, body, headers = {} } = {}) {
  let response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    });
  } catch (error) {
    const cause = error?.cause;
    const detail = cause?.code
      ? `${cause.code}${cause.address ? ` ${cause.address}:${cause.port || ''}` : ''}`
      : error.message;
    throw new Error(`${method} ${path} failed: ${detail}`);
  }
  const text = await response.text();
  let json = null;
  if (text.trim()) {
    try {
      json = JSON.parse(text);
    } catch {
      json = { raw: text };
    }
  }
  if (!response.ok) {
    const detail = json?.message || json?.errorMessage || json?.error || text || response.statusText;
    throw new Error(`${method} ${path} failed (${response.status}): ${detail}`);
  }
  return json;
}

function isOkEnvelope(json) {
  return json?.code === '0' || json?.success === true;
}

async function login(baseUrl, args) {
  const explicit = String(args.token || process.env.AURA_TOKEN || '').trim();
  if (explicit) return explicit;

  const email = String(args.email || DEFAULT_EMAIL);
  const password = String(args.password || DEFAULT_PASSWORD);
  const loginJson = await fetchJson(baseUrl, '/api/auth/login', {
    method: 'POST',
    body: { email, password },
  });
  let jwt = loginJson?.data?.jwt;
  if (!jwt) throw new Error('Login response missing data.jwt');

  const spaces = await fetchJson(baseUrl, '/api/tenant-selection/my-spaces', { token: jwt });
  const tenantName = args.tenant ? String(args.tenant) : '';
  if (tenantName || !loginJson?.data?.tenantId) {
    const candidates = Array.isArray(spaces?.data) ? spaces.data : [];
    const selected =
      candidates.find(
        (item) =>
          tenantName &&
          (item.tenantName === tenantName ||
            item.tenantDisplayName === tenantName ||
            String(item.tenantId) === tenantName),
      ) ||
      candidates.find((item) => item.spaceType === 'business') ||
      candidates[0];
    if (!selected?.tenantId) throw new Error(`No selectable tenant in /tenant-selection/my-spaces`);
    const selectJson = await fetchJson(baseUrl, '/api/tenant-selection/process', {
      method: 'POST',
      token: jwt,
      body: { action: 'select', tenantId: Number(selected.tenantId) },
    });
    jwt = selectJson?.data?.jwt || jwt;
  }

  return jwt;
}

function findRuntimePlugin(hotloadJson, pluginId) {
  const plugins = Array.isArray(hotloadJson?.plugins) ? hotloadJson.plugins : [];
  return plugins.find((plugin) => plugin.pluginId === pluginId) || null;
}

function walk(value, visitor) {
  if (!value || typeof value !== 'object') return;
  visitor(value);
  if (Array.isArray(value)) {
    for (const item of value) walk(item, visitor);
    return;
  }
  for (const item of Object.values(value)) walk(item, visitor);
}

function findFieldsInSchema(schema, fieldNames) {
  const found = new Map();
  walk(schema, (node) => {
    if (node && typeof node.field === 'string' && fieldNames.includes(node.field)) {
      if (!found.has(node.field)) found.set(node.field, node);
    }
  });
  return found;
}

async function hotloadUpload(baseUrl, token, jarPath) {
  const form = new FormData();
  const blob = new Blob([readFileSync(jarPath)], { type: 'application/java-archive' });
  form.append('file', blob, basename(jarPath));
  return fetchJson(baseUrl, '/api/plugins/hotload/upload', {
    method: 'POST',
    token,
    body: form,
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.plugin) {
    usage();
    process.exit(args.help ? 0 : 2);
  }

  const jsonOnly = Boolean(args.json);
  const evidence = {
    ok: false,
    pluginRoot: '',
    pluginId: '',
    pluginType: '',
    localJar: null,
    auth: { tokenValidated: false },
    hotload: null,
    import: null,
    pageSchema: null,
    failures: [],
    skippedChecks: [],
  };

  const fail = (message) => {
    evidence.failures.push(message);
  };

  const pluginRoot = isAbsolute(String(args.plugin)) ? String(args.plugin) : resolve(String(args.plugin));
  evidence.pluginRoot = pluginRoot;
  const manifestPath = resolve(pluginRoot, 'plugin.json');
  if (!existsSync(manifestPath)) throw new Error(`plugin.json not found: ${manifestPath}`);

  const manifest = readJsonFile(manifestPath, 'plugin manifest');
  const pluginId = manifest.pluginId || manifest.id || manifest.code;
  if (!pluginId) throw new Error('plugin.json is missing pluginId');
  evidence.pluginId = pluginId;
  evidence.pluginType = manifest.pluginType || 'config';
  const backendJarRequired =
    evidence.pluginType === 'hybrid' ||
    Boolean(args['hotload-upload']) ||
    values(args, 'expect-extension').length > 0;

  const jarPath = manifest.backend?.jarPath ? resolve(pluginRoot, manifest.backend.jarPath) : '';
  if (jarPath) {
    if (!existsSync(jarPath)) {
      if (backendJarRequired) {
        fail(`backend jar not found: ${jarPath}`);
      } else {
        evidence.skippedChecks.push('config plugin backend jar not required');
      }
    } else {
      const extensionIndex = readZipEntry(jarPath, 'META-INF/extensions.idx');
      const expectedExtensions = values(args, 'expect-extension');
      for (const needle of expectedExtensions) {
        if (!extensionIndex.includes(needle)) fail(`extensions.idx missing expected text: ${needle}`);
      }
      evidence.localJar = {
        path: jarPath,
        sha256: sha256(jarPath),
        extensionsIdxPresent: extensionIndex.trim().length > 0,
        extensionsIdxLineCount: extensionIndex.trim() ? extensionIndex.trim().split(/\r?\n/).length : 0,
        expectedExtensions,
      };
    }
  } else if (evidence.pluginType === 'hybrid') {
    fail('hybrid plugin has no backend.jarPath in plugin.json');
  }

  if (args['offline-metadata-only']) {
    evidence.ok = evidence.failures.length === 0;
    console.log(JSON.stringify(evidence, null, 2));
    process.exit(evidence.ok ? 0 : 1);
  }

  const baseUrl = normalizeBackend(args.backend);
  const health = await fetchJson(baseUrl, '/actuator/health').catch((error) => {
    fail(error.message);
    return null;
  });
  evidence.backend = { baseUrl, health: health?.status || null };

  const token = await login(baseUrl, args);
  const spaces = await fetchJson(baseUrl, '/api/tenant-selection/my-spaces', { token });
  evidence.auth.tokenValidated = isOkEnvelope(spaces);
  evidence.auth.spaceCount = Array.isArray(spaces?.data) ? spaces.data.length : 0;
  if (!evidence.auth.tokenValidated) fail('/api/tenant-selection/my-spaces did not return code=0');

  if (args['hotload-upload']) {
    if (!jarPath || !existsSync(jarPath)) fail('--hotload-upload requested but local jar is unavailable');
    else evidence.hotloadUpload = await hotloadUpload(baseUrl, token, jarPath);
  }

  const hotload = await fetchJson(baseUrl, '/api/plugins/hotload', { token });
  const runtimePlugin = findRuntimePlugin(hotload, pluginId);
  const runtimeSha =
    runtimePlugin?.path && existsSync(runtimePlugin.path) ? sha256(runtimePlugin.path) : null;
  evidence.hotload = {
    total: hotload?.total,
    pluginFound: Boolean(runtimePlugin),
    pluginId,
    state: runtimePlugin?.state || null,
    path: runtimePlugin?.path || null,
    runtimeJarSha256: runtimeSha,
    localJarMatchesRuntime:
      Boolean(runtimeSha && evidence.localJar?.sha256) && runtimeSha === evidence.localJar.sha256,
  };
  if (!runtimePlugin && evidence.pluginType === 'hybrid') fail(`plugin is not hot-loaded: ${pluginId}`);
  if (runtimePlugin && runtimePlugin.state !== 'STARTED') fail(`plugin state is not STARTED: ${runtimePlugin.state}`);

  const extensionStats = await fetchJson(baseUrl, '/api/plugins/hotload/extensions', { token }).catch((error) => {
    fail(error.message);
    return null;
  });
  const commandTypes = extensionStats?.registeredKeys?.commandTypes || [];
  evidence.runtimeRegistry = {
    commandTypeCount: commandTypes.length,
    eventPatternCount: extensionStats?.registeredKeys?.eventPatterns?.length || 0,
    dataProviderKeyCount: extensionStats?.registeredKeys?.dataProviderKeys?.length || 0,
    validatorKeyCount: extensionStats?.registeredKeys?.validatorKeys?.length || 0,
  };
  const expectedHandlers = values(args, 'expect-handler');
  evidence.expectedHandlers = expectedHandlers;
  for (const handler of expectedHandlers) {
    if (!commandTypes.includes(handler)) fail(`runtime command handler not registered: ${handler}`);
  }

  if (args.import) {
    const importBody = {
      path: pluginRoot,
      conflictStrategy: String(args['conflict-strategy'] || 'OVERWRITE'),
      validateReferences: true,
      createResourcePermissions: true,
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
      autoPublishMenus: true,
      deferReferenceValidation: Boolean(args['defer-reference-validation']),
    };
    evidence.import = await fetchJson(baseUrl, '/api/plugins/import/import-directory-sync', {
      method: 'POST',
      token,
      body: importBody,
    });
    if (evidence.import?.success !== true && evidence.import?.code !== '0') {
      fail(`import-directory-sync did not return success=true: ${evidence.import?.errorMessage || evidence.import?.message || 'unknown'}`);
    }
  }

  if (args['page-key']) {
    const pageKey = String(args['page-key']);
    const pageJson = await fetchJson(baseUrl, `/api/pages/key/${encodeURIComponent(pageKey)}`, { token });
    const schema = pageJson?.data?.schema || pageJson?.data || pageJson;
    const requiredFields = values(args, 'page-field');
    const found = findFieldsInSchema(schema, requiredFields);
    for (const field of requiredFields) {
      if (!found.has(field)) fail(`runtime page schema ${pageKey} missing field: ${field}`);
    }
    evidence.pageSchema = {
      pageKey,
      loaded: Boolean(schema),
      requiredFields,
      foundFields: Object.fromEntries(found.entries()),
    };
  }

  evidence.ok = evidence.failures.length === 0;
  if (jsonOnly) {
    console.log(JSON.stringify(evidence, null, 2));
  } else {
    console.log(JSON.stringify(evidence, null, 2));
    if (!evidence.ok) {
      console.error(`plugin-runtime-import-guard failed: ${evidence.failures.join('; ')}`);
    }
  }
  process.exit(evidence.ok ? 0 : 1);
}

main().catch((error) => {
  console.error(
    `plugin-runtime-import-guard error: ${process.env.AURA_DEBUG ? error.stack || error.message : error.message}`,
  );
  process.exit(1);
});
