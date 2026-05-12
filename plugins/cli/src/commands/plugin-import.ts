import chalk from 'chalk';
import { log } from '../utils/logger.js';
import { loadPlugin, countResources } from '../utils/plugin-loader.js';
import { ApiClient } from '../client/api-client.js';
import { resolveBaseUrl, resolveToken } from '../client/auth.js';

interface ImportOptions {
  target?: string;
  user?: string;
  password?: string;
  yes?: boolean;
  dryRun?: boolean;
  token?: string;
  env?: string;
  conflictStrategy?: 'overwrite' | 'skip' | 'error';
}

interface DryRunResult {
  valid: boolean;
  wouldCreate: {
    models: number;
    fields: number;
    commands: number;
    pages: number;
    dicts: number;
    namedQueries: number;
    menus: number;
    permissions: number;
    roles: number;
  };
  conflicts: Array<{
    resourceType: string;
    code: string;
    action: string;
    detail?: string;
  }>;
  validationErrors: string[];
}

/**
 * Import a plugin to the AuraBoot platform.
 *
 * With --dry-run: validates the manifest and shows what WOULD be created without
 * actually importing anything. Calls the /validate endpoint and combines with
 * local resource counts to produce a dry-run report.
 *
 * Without --dry-run: imports the plugin (calls execute-direct).
 */
export async function pluginImportCommand(dir: string, options: ImportOptions): Promise<void> {
  try {
    const plugin = loadPlugin(dir);
    const resourceCount = countResources(plugin);
    const baseUrl = options.target ?? resolveBaseUrl(options.env);
    const token = options.token ?? resolveToken(options);

    log.header(
      `${options.dryRun ? '[DRY RUN] ' : ''}Importing: ${plugin.manifest.pluginId} v${plugin.manifest.version}`
    );
    log.dim(`Target: ${baseUrl}`);
    log.dim(`${resourceCount} total resources`);
    log.blank();

    // Authenticate if no token
    let authToken = token;
    if (!authToken) {
      const email = options.user ?? process.env.AURA_USER ?? 'admin@auraboot.com';
      const password = options.password ?? process.env.AURA_PASSWORD ?? '';

      log.info(`Authenticating as ${email}...`);
      const loginResp = await fetch(`${baseUrl}/api/auth/login`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });

      if (!loginResp.ok) {
        log.error(`Authentication failed: ${loginResp.status}`);
        process.exit(1);
      }

      const loginData = await loginResp.json() as any;
      authToken = loginData.data?.jwt;
      if (!authToken) {
        log.error('Failed to get auth token');
        process.exit(1);
      }
      log.success('Authenticated');
    }

    // Build the extended manifest
    const manifest = buildExtendedManifest(plugin);

    // ── DRY RUN ──────────────────────────────────────────────────────────────
    if (options.dryRun) {
      log.info('Running dry-run validation against platform...');
      log.blank();

      const validateResp = await fetch(`${baseUrl}/api/plugins/import/validate`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`,
        },
        body: JSON.stringify(manifest),
      });

      if (!validateResp.ok) {
        log.error(`Validation request failed: ${validateResp.status}`);
        const errText = await validateResp.text();
        log.error(errText);
        process.exit(1);
      }

      const validateData = await validateResp.json() as any;

      // Build the dry-run result report
      const resources = plugin.resourceFiles;
      const dryRun: DryRunResult = {
        valid: validateData.valid === true,
        wouldCreate: {
          models:       (resources.get('models')        ?? []).length,
          fields:       (resources.get('fields')        ?? []).length,
          commands:     (resources.get('commands')      ?? []).length,
          pages:        (resources.get('pages')         ?? []).length,
          dicts:        (resources.get('dicts')         ?? []).length,
          namedQueries: (resources.get('named-queries') ?? []).length,
          menus:        (resources.get('menus')         ?? []).length,
          permissions:  (resources.get('permissions')   ?? []).length,
          roles:        (resources.get('roles')         ?? []).length,
        },
        conflicts: (validateData.conflicts ?? []).map((c: any) => ({
          resourceType: c.resourceType,
          code: c.resourceCode ?? c.code ?? '—',
          action: c.action ?? 'conflict',
          detail: c.detail,
        })),
        validationErrors: validateData.errors ?? [],
      };

      printDryRunReport(plugin.manifest.pluginId, dryRun);

      process.exit(dryRun.valid ? 0 : 1);
    }

    // ── REAL IMPORT ──────────────────────────────────────────────────────────

    // Confirm unless --yes
    if (!options.yes) {
      const resourceList = Object.entries({
        models: (plugin.resourceFiles.get('models') ?? []).length,
        fields: (plugin.resourceFiles.get('fields') ?? []).length,
        commands: (plugin.resourceFiles.get('commands') ?? []).length,
        pages: (plugin.resourceFiles.get('pages') ?? []).length,
      })
        .filter(([, n]) => n > 0)
        .map(([t, n]) => `${n} ${t}`)
        .join(', ');

      console.log(chalk.yellow(`This will import ${resourceList} to ${baseUrl}.`));
      console.log(chalk.dim('Use --yes to skip this prompt, or --dry-run to preview first.'));

      // Simple TTY confirm
      const readline = await import('readline');
      const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
      const answer = await new Promise<string>((resolve) =>
        rl.question(chalk.bold('Proceed? [y/N] '), resolve)
      );
      rl.close();

      if (answer.toLowerCase() !== 'y' && answer.toLowerCase() !== 'yes') {
        log.info('Cancelled.');
        process.exit(0);
      }
    }

    const conflictStrategy = options.conflictStrategy ?? 'overwrite';
    const importParams = new URLSearchParams({
      conflictStrategy,
      autoPublishModels: 'true',
      autoPublishFields: 'true',
      autoPublishCommands: 'true',
      autoPublishPages: 'true',
    });

    log.info('Uploading manifest...');
    const importResp = await fetch(
      `${baseUrl}/api/plugins/import/execute-direct?${importParams}`,
      {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`,
        },
        body: JSON.stringify(manifest),
      },
    );

    if (!importResp.ok) {
      const errText = await importResp.text();
      log.error(`Import failed: ${importResp.status} — ${errText}`);
      process.exit(1);
    }

    const importResult = await importResp.json() as any;
    const data = importResult.data ?? importResult;

    if (data.success === false) {
      log.error(`Import failed: ${data.errorMessage ?? 'Unknown error'}`);
      if (data.errors) {
        for (const err of data.errors) log.error(`  ${err}`);
      }
      process.exit(1);
    }

    log.success('Import complete!');
    if (data.resourceCounts) {
      for (const [type, val] of Object.entries(data.resourceCounts)) {
        if (typeof val === 'object' && val !== null) {
          const parts = Object.entries(val as Record<string, number>)
            .filter(([, n]) => n > 0)
            .map(([action, n]) => `${n} ${action.toLowerCase()}`);
          if (parts.length > 0) log.dim(`${type}: ${parts.join(', ')}`);
        } else {
          log.dim(`${type}: ${val}`);
        }
      }
    }

  } catch (e) {
    log.error((e as Error).message);
    process.exit(1);
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function printDryRunReport(pluginId: string, result: DryRunResult): void {
  console.log(chalk.bold('Dry-Run Report') + chalk.dim(` — ${pluginId}`));
  log.blank();

  // Validation status
  if (result.valid) {
    console.log(chalk.green('  ✓ Manifest is valid'));
  } else {
    console.log(chalk.red('  ✗ Manifest has validation errors'));
  }
  log.blank();

  // Would-create table
  console.log(chalk.bold('  Resources that WOULD be created:'));
  const rows = Object.entries(result.wouldCreate).filter(([, n]) => n > 0);
  if (rows.length === 0) {
    console.log(chalk.dim('  (none)'));
  } else {
    const maxLen = Math.max(...rows.map(([k]) => k.length));
    for (const [type, count] of rows) {
      console.log(`  ${chalk.cyan(type.padEnd(maxLen + 2))} ${chalk.white(String(count))}`);
    }
  }
  log.blank();

  // Conflicts
  if (result.conflicts.length > 0) {
    console.log(chalk.bold('  Conflicts detected:'));
    for (const c of result.conflicts) {
      const line = `  ${chalk.yellow('⚠')}  [${c.resourceType}] ${chalk.bold(c.code)} — ${c.action}`;
      console.log(line);
      if (c.detail) console.log(chalk.dim(`     ${c.detail}`));
    }
    log.blank();
  }

  // Validation errors
  if (result.validationErrors.length > 0) {
    console.log(chalk.bold('  Validation errors:'));
    for (const err of result.validationErrors) {
      console.log(`  ${chalk.red('✗')}  ${chalk.red(err)}`);
    }
    log.blank();
  }

  // Footer
  if (result.valid) {
    console.log(chalk.dim('  Re-run without --dry-run to apply changes.'));
  } else {
    console.log(chalk.dim('  Fix the errors above before importing.'));
  }
}

function buildExtendedManifest(plugin: ReturnType<typeof loadPlugin>): any {
  return {
    ...plugin.manifest,
    models:              plugin.resourceFiles.get('models')        ?? [],
    fields:              plugin.resourceFiles.get('fields')        ?? [],
    modelFieldBindings:  plugin.resourceFiles.get('bindings')      ?? [],
    commands:            plugin.resourceFiles.get('commands')      ?? [],
    pages:               plugin.resourceFiles.get('pages')         ?? [],
    permissions:         plugin.resourceFiles.get('permissions')   ?? [],
    roles:               plugin.resourceFiles.get('roles')         ?? [],
    menus:               plugin.resourceFiles.get('menus')         ?? [],
    dicts:               plugin.resourceFiles.get('dicts')         ?? [],
    i18nResources:       plugin.resourceFiles.get('i18n')          ?? [],
    namedQueries:        plugin.resourceFiles.get('named-queries') ?? [],
    savedViews:          plugin.resourceFiles.get('saved-views')   ?? [],
    dashboards:          plugin.resourceFiles.get('dashboards')    ?? [],
  };
}
