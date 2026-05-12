import chalk from 'chalk';
import { log } from '../utils/logger.js';
import { loadPlugin, countResources } from '../utils/plugin-loader.js';

interface DiffOptions {
  target: string;
  user?: string;
  password?: string;
}

interface RemoteResource {
  code: string;
  [key: string]: any;
}

/**
 * Compare local plugin config vs remote platform state.
 */
export async function diffCommand(dir: string, options: DiffOptions): Promise<void> {
  try {
    const plugin = loadPlugin(dir);

    log.header(`Comparing: ${plugin.manifest.pluginId} v${plugin.manifest.version}`);
    log.dim(`Target: ${options.target}`);
    log.blank();

    // Step 1: Authenticate
    const email = options.user || process.env.AURA_USER || 'admin@auraboot.com';
    const password = options.password || process.env.AURA_PASSWORD || '';

    log.info(`Authenticating as ${email}...`);
    const loginResp = await fetch(`${options.target}/api/auth/login`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!loginResp.ok) {
      log.error(`Authentication failed: ${loginResp.status}`);
      process.exit(1);
    }

    const loginData = await loginResp.json() as any;
    const token = loginData.data?.jwt;
    if (!token) {
      log.error('Failed to get auth token');
      process.exit(1);
    }
    log.success('Authenticated');

    // Step 2: Fetch remote resources
    const authHeaders = {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    };

    let totalDiffs = 0;

    // Compare models
    const localModels = plugin.resourceFiles.get('models') || [];
    if (localModels.length > 0) {
      const remoteModels = await fetchRemoteResources(options.target, '/api/meta/models', authHeaders);
      totalDiffs += compareResources('Models', localModels, remoteModels, 'code');
    }

    // Compare fields
    const localFields = plugin.resourceFiles.get('fields') || [];
    if (localFields.length > 0) {
      const remoteFields = await fetchRemoteResources(options.target, '/api/meta/fields', authHeaders);
      totalDiffs += compareResources('Fields', localFields, remoteFields, 'code');
    }

    // Compare commands
    const localCommands = plugin.resourceFiles.get('commands') || [];
    if (localCommands.length > 0) {
      const remoteCommands = await fetchRemoteResources(options.target, '/api/meta/commands', authHeaders);
      totalDiffs += compareResources('Commands', localCommands, remoteCommands, 'code');
    }

    // Compare permissions
    const localPerms = plugin.resourceFiles.get('permissions') || [];
    if (localPerms.length > 0) {
      const remotePerms = await fetchRemoteResources(options.target, '/api/admin/permissions', authHeaders);
      totalDiffs += compareResources('Permissions', localPerms, remotePerms, 'code');
    }

    // Compare menus
    const localMenus = plugin.resourceFiles.get('menus') || [];
    if (localMenus.length > 0) {
      const remoteMenus = await fetchRemoteResources(options.target, '/api/admin/menus', authHeaders);
      totalDiffs += compareResources('Menus', localMenus, remoteMenus, 'code');
    }

    // Compare pages
    const localPages = plugin.resourceFiles.get('pages') || [];
    if (localPages.length > 0) {
      const remotePages = await fetchRemoteResources(options.target, '/api/pages', authHeaders);
      totalDiffs += compareResources('Pages', localPages, remotePages, 'pageKey');
    }

    // Summary
    log.blank();
    if (totalDiffs === 0) {
      log.success('Local and remote are in sync. No differences found.');
    } else {
      console.log(chalk.bold(`Total: ${totalDiffs} difference(s) found`));
    }

  } catch (e) {
    log.error((e as Error).message);
    process.exit(1);
  }
}

async function fetchRemoteResources(
  target: string,
  path: string,
  headers: Record<string, string>,
): Promise<RemoteResource[]> {
  try {
    const resp = await fetch(`${target}${path}?size=1000`, { headers });
    if (!resp.ok) return [];
    const json = await resp.json() as any;
    // Handle paginated responses (data.records or data)
    const data = json.data;
    if (Array.isArray(data)) return data;
    if (data?.records && Array.isArray(data.records)) return data.records;
    return [];
  } catch {
    return [];
  }
}

function compareResources(
  label: string,
  local: any[],
  remote: RemoteResource[],
  keyField: string,
): number {
  const remoteMap = new Map<string, RemoteResource>();
  for (const r of remote) {
    if (r[keyField]) remoteMap.set(r[keyField], r);
  }

  let diffs = 0;
  const results: string[] = [];

  for (const item of local) {
    const code = item[keyField];
    if (!code) continue;

    const remoteItem = remoteMap.get(code);
    if (!remoteItem) {
      results.push(`  ${chalk.green('+')} ${code}: ${chalk.green('new (local only)')}`);
      diffs++;
    } else {
      // Simple shallow comparison of key fields
      const changes = detectChanges(item, remoteItem);
      if (changes.length > 0) {
        results.push(`  ${chalk.yellow('~')} ${code}: ${chalk.yellow(changes.join(', '))}`);
        diffs++;
      } else {
        results.push(`  ${chalk.dim('=')} ${code}: ${chalk.dim('identical')}`);
      }
    }
  }

  // Check for remote-only resources (not in local but in remote with matching namespace)
  for (const [code] of remoteMap) {
    const localItem = local.find((l) => l[keyField] === code);
    if (!localItem) {
      // Only show resources from the same plugin namespace
      // Skip showing remote-only resources since they might belong to other plugins
    }
  }

  if (results.length > 0) {
    console.log(chalk.bold(`\n${label}:`));
    for (const r of results) {
      console.log(r);
    }
  }

  return diffs;
}

function detectChanges(local: any, remote: any): string[] {
  const changes: string[] = [];
  const compareFields = ['displayName', 'modelType', 'dataType', 'type', 'fieldType',
    'description', 'modelCode', 'pageName', 'schemaType'];

  for (const field of compareFields) {
    if (local[field] !== undefined && remote[field] !== undefined) {
      if (String(local[field]) !== String(remote[field])) {
        changes.push(`${field} changed`);
      }
    }
  }

  return changes;
}
