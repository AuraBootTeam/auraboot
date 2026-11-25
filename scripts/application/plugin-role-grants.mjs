#!/usr/bin/env node

/**
 * Grant a plugin's role templates to roles that already exist, without taking anything away.
 *
 * A plugin's roles.json is applied only when the import creates the role. An existing role is
 * skipped, so a later plugin version that adds permissions to a role never reaches a tenant that
 * installed an earlier one. This script tops those roles up from the same file.
 *
 * - Read-only by default. It prints what each existing role is missing; --apply grants it.
 * - Additive only. Grants go through PUT /api/permissions/matrix/{rolePid}/batch, and every entry
 *   sent has granted: true. POST /api/roles/{pid}/permissions replaces a role's whole grant list
 *   with the request and is never written here.
 * - After a write each role is read back. Every added grant must be present and every grant the
 *   role had before must still be present; anything else fails the run.
 *
 * A permission the template names that does not exist in the tenant is reported and never sent.
 * A template role the tenant does not have is reported and never created.
 */

import { closeSync, constants, existsSync, fstatSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const scriptPath = fileURLToPath(import.meta.url);

const need = (value, message) => {
  if (!value) throw new Error(message);
};

/** The roles file of a plugin: an array of { code, permissions: [permission code, ...] }. */
export function readRoleTemplates(raw) {
  need(Array.isArray(raw), 'roles file must be an array of role templates');
  return raw.map((template, index) => {
    need(template && typeof template === 'object' && typeof template.code === 'string' && template.code.trim(),
      `roles[${index}].code must be a role code`);
    need(Array.isArray(template.permissions)
      && template.permissions.every((code) => typeof code === 'string' && code.trim()),
      `roles[${index}].permissions must list permission codes`);
    return { code: template.code, permissions: [...new Set(template.permissions)] };
  });
}

/** Permission code -> pid, and pid -> numeric id, over a permission tree. */
export function indexPermissionTree(nodes) {
  const pidByCode = new Map();
  const idByPid = new Map();
  const walk = (list) => {
    for (const node of Array.isArray(list) ? list : []) {
      if (node && typeof node.code === 'string' && node.pid != null && node.id != null) {
        pidByCode.set(node.code, String(node.pid));
        idByPid.set(String(node.pid), node.id);
      }
      walk(node?.children);
    }
  };
  walk(nodes);
  return { pidByCode, idByPid };
}

/** What a role is missing from its template. A code the tenant does not have is unknown, never sent. */
export function roleGrantPlan(template, grantedPids, { pidByCode, idByPid }) {
  const granted = new Set(grantedPids.map(String));
  const missing = [];
  const unknown = [];
  for (const code of template.permissions) {
    const pid = pidByCode.get(code);
    if (pid === undefined) unknown.push(code);
    else if (!granted.has(pid)) missing.push({ code, pid, permissionId: idByPid.get(pid) });
  }
  return { missing, unknown };
}

/** Only grants: a revocation is an entry with granted false, and nothing here builds one. */
export function batchGrantBody(missing) {
  return missing.map(({ permissionId }) => ({ permissionId, granted: true }));
}

/** After a write: every added grant is present, and so is every grant the role had before. */
export function verifyGrants(before, after, missing) {
  const now = new Set(after.map(String));
  return {
    notAdded: missing.filter(({ pid }) => !now.has(pid)).map(({ code }) => code),
    lost: [...new Set(before.map(String))].filter((pid) => !now.has(pid)),
  };
}

/**
 * @param request (method, path, body?) => envelope data; it must refuse any non-2xx or non-zero envelope.
 */
export async function grantPluginRoles({ templates, request, apply = false }) {
  const roles = await request('GET', '/api/roles/all');
  need(Array.isArray(roles), 'the role inventory is not a list');
  const roleByCode = new Map(roles.filter((role) => role && typeof role.code === 'string').map((role) => [role.code, role]));
  const index = indexPermissionTree(await request('GET', '/api/permissions/tree'));

  const result = { applied: apply, roles: [], absentRoles: [], failed: false, incomplete: false };
  for (const template of templates) {
    const role = roleByCode.get(template.code);
    if (!role || role.pid == null) {
      result.absentRoles.push(template.code);
      continue;
    }
    const rolePid = String(role.pid);
    const grantsPath = `/api/roles/${encodeURIComponent(rolePid)}/permissions`;
    const before = await request('GET', grantsPath);
    need(Array.isArray(before), `the grants of role ${template.code} are not a list`);
    const { missing, unknown } = roleGrantPlan(template, before, index);
    const entry = {
      roleCode: template.code,
      rolePid,
      grantedBefore: before.length,
      missing: missing.map(({ code }) => code),
      unknown,
    };
    if (apply && missing.length) {
      await request('PUT', `/api/permissions/matrix/${encodeURIComponent(rolePid)}/batch`, batchGrantBody(missing));
      const check = verifyGrants(before, await request('GET', grantsPath), missing);
      entry.granted = missing.length - check.notAdded.length;
      Object.assign(entry, check);
      if (check.notAdded.length || check.lost.length) result.failed = true;
    }
    if (unknown.length) result.incomplete = true;
    result.roles.push(entry);
  }
  return result;
}

export function normalizeBaseUrl(value) {
  const url = new URL(value);
  need(['http:', 'https:'].includes(url.protocol), 'base URL must use HTTP or HTTPS');
  need(!url.username && !url.password, 'base URL must not contain credentials');
  need(!url.search && !url.hash, 'base URL must not contain a query or fragment');
  return url.toString().replace(/\/$/, '');
}

export function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = () => {
      const next = argv[++index];
      need(next !== undefined && !next.startsWith('--'), `${argument} requires a value`);
      return next;
    };
    if (argument === '--roles') options.roles = resolve(value());
    else if (argument === '--plugin') options.plugin = resolve(value());
    else if (argument === '--base-url') options.baseUrl = value();
    else if (argument === '--token-file') options.tokenFile = resolve(value());
    else if (argument === '--receipt') options.receipt = resolve(value());
    else if (argument === '--apply') options.apply = true;
    else if (argument === '--json') options.json = true;
    else if (argument === '--help' || argument === '-h') options.help = true;
    else throw new Error(`unknown argument ${argument}`);
  }
  if (!options.roles && options.plugin) options.roles = resolve(options.plugin, 'config', 'roles.json');
  return options;
}

function usage() {
  return `Usage:
  scripts/application/plugin-role-grants.mjs --roles <roles.json> --base-url <url> [--token-file <file>] [options]

Options:
  --roles <file>        The plugin's roles.json (--plugin <root> resolves <root>/config/roles.json)
  --base-url <url>      Backend base URL
  --token-file <file>   Session token file, readable by its owner only; otherwise
                        AURA_PLUGIN_ROLE_GRANTS_TOKEN
  --apply               Grant what is missing. Without it nothing is written
  --receipt <file>      Write the plan or result to a new file
  --json                Print machine-readable JSON only
  --help                Show this help

Read-only unless --apply is passed. Grants are additive: nothing is revoked.
`;
}

function readToken(options) {
  let token = process.env.AURA_PLUGIN_ROLE_GRANTS_TOKEN?.trim();
  if (options.tokenFile) {
    const descriptor = openSync(options.tokenFile, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const stat = fstatSync(descriptor);
      need(stat.isFile(), '--token-file must be a regular file');
      need((stat.mode & 0o077) === 0, '--token-file must not grant group or other permissions');
      token = readFileSync(descriptor, 'utf8').trim();
    } finally {
      closeSync(descriptor);
    }
  }
  need(typeof token === 'string' && token, 'a session token is required: --token-file or AURA_PLUGIN_ROLE_GRANTS_TOKEN');
  return token;
}

function requestJson({ fetchImpl, baseUrl, token, timeoutMs }) {
  return async (method, path, body) => {
    const response = await fetchImpl(`${baseUrl}${path}`, {
      method,
      redirect: 'error',
      signal: AbortSignal.timeout(timeoutMs),
      headers: { accept: 'application/json', authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    let envelope;
    try {
      envelope = JSON.parse(text);
    } catch {
      throw new Error(`the API returned non-JSON content with HTTP ${response.status} for ${method} ${path}`);
    }
    if (!response.ok || String(envelope?.code) !== '0') {
      throw new Error(`the API rejected ${method} ${path}: HTTP ${response.status}, code=${envelope?.code ?? 'missing'}, message=${envelope?.message ?? 'missing'}`);
    }
    return envelope.data;
  };
}

function writeReceipt(path, receipt) {
  need(!existsSync(path) && !existsSync(`${path}.tmp`), '--receipt must name a new file: an earlier receipt is not overwritten');
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  renameSync(temporary, path);
}

async function main() {
  const argv = process.argv.slice(2);
  const options = parseArgs(argv);
  if (options.help) {
    process.stdout.write(usage());
    return 0;
  }
  if (argv.length === 0) {
    process.stdout.write(usage());
    return 2;
  }
  need(options.roles, '--roles or --plugin is required');
  need(options.baseUrl, '--base-url is required');
  const baseUrl = normalizeBaseUrl(options.baseUrl);
  const source = readFileSync(options.roles);
  const templates = readRoleTemplates(JSON.parse(source.toString('utf8')));
  const result = await grantPluginRoles({
    templates,
    apply: options.apply,
    request: requestJson({ fetchImpl: fetch, baseUrl, token: readToken(options), timeoutMs: 60_000 }),
  });
  if (options.receipt) {
    writeReceipt(resolve(options.receipt), {
      schemaVersion: 1,
      kind: 'plugin-role-grants',
      input: { baseUrl, rolesFile: options.roles, rolesFileSha256: createHash('sha256').update(source).digest('hex') },
      result,
    });
  }
  if (options.json) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } else {
    for (const code of result.absentRoles) console.log(`${code}: the tenant has no such role; not created`);
    for (const role of result.roles) {
      console.log(`${role.roleCode}: holds ${role.grantedBefore}; missing ${role.missing.length}${role.missing.length ? ` (${role.missing.join(', ')})` : ''}`
        + `${role.unknown.length ? `; not in this tenant: ${role.unknown.join(', ')}` : ''}`
        + (role.granted === undefined ? '' : `; granted ${role.granted}`));
      if (role.lost?.length) console.log(`  !! grants lost: ${role.lost.join(', ')}`);
      if (role.notAdded?.length) console.log(`  !! not granted: ${role.notAdded.join(', ')}`);
    }
    console.log(result.applied
      ? (result.failed ? 'Grants were written, but a role did not read back as expected.' : 'Every missing grant was added and read back; no earlier grant was lost.')
      : 'Dry run: nothing was written. Pass --apply to grant.');
    if (result.incomplete) console.log('Some permissions the templates name do not exist in this tenant; import the plugin definitions first.');
  }
  if (result.failed) return 1;
  return result.incomplete ? 3 : 0;
}

if (process.argv[1] === scriptPath) {
  main().then(
    (code) => {
      process.exitCode = code;
    },
    (error) => {
      console.error(`plugin-role-grants: ${error.message}`);
      process.exitCode = 1;
    },
  );
}
