import chalk from 'chalk';
import { log } from '../utils/logger.js';
import { loadPlugin, countResources } from '../utils/plugin-loader.js';
import { validateStructural } from '../validation/structural.js';
import { validateSemantic } from '../validation/semantic.js';

interface PublishOptions {
  target: string;
  user?: string;
  password?: string;
  yes?: boolean;
}

/**
 * Publish a plugin to the AuraBoot platform.
 */
export async function publishCommand(dir: string, options: PublishOptions): Promise<void> {
  try {
    const plugin = loadPlugin(dir);
    const resourceCount = countResources(plugin);

    // AURA_API_URL env var overrides the default --target so non-interactive
    // callers (CI, reset-and-init.sh) can retarget without editing the script.
    const envApiUrl = process.env.AURA_API_URL?.trim();
    const target = envApiUrl && envApiUrl.length > 0
      ? envApiUrl.replace(/\/$/, '')
      : options.target;
    options = { ...options, target };

    log.header(`Publishing: ${plugin.manifest.pluginId} v${plugin.manifest.version}`);
    log.dim(`Target: ${options.target}`);
    log.dim(`${resourceCount} resources`);
    log.blank();

    // Step 0: Validate before publish
    log.info('Running pre-publish validation...');
    const structural = validateStructural(plugin);
    if (structural.errorCount > 0) {
      log.error('Structural validation failed:');
      for (const msg of structural.messages.filter(m => m.severity === 'error')) {
        log.error(`  ${msg.message}`);
      }
      log.blank();
      log.error('Fix errors before publishing. Run "aura plugin validate" for full report.');
      process.exit(1);
    }

    const semantic = validateSemantic(plugin);
    if (semantic.errorCount > 0) {
      log.error('Semantic validation failed:');
      for (const msg of semantic.messages.filter(m => m.severity === 'error')) {
        log.error(`  ${msg.message}`);
      }
      log.blank();
      log.error('Fix errors before publishing. Run "aura plugin validate" for full report.');
      process.exit(1);
    }
    log.success('Validation passed');
    log.blank();

    // Step 1: Authenticate (prefer AURA_TOKEN env var to avoid lockouts on
    // repeated publish loops; fall back to email+password login).
    let token = process.env.AURA_TOKEN || '';
    if (token) {
      log.info('Using AURA_TOKEN from environment');
    } else {
      const email = options.user || process.env.AURA_USER || 'admin@example.com';
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
      token = loginData.data?.jwt;
      if (!token) {
        log.error('Failed to get auth token');
        process.exit(1);
      }
      log.success('Authenticated');
    }

    // Step 2: Import using directory-sync API
    // Build the manifest in the extended format
    const manifest = buildExtendedManifest(plugin);

    log.info('Uploading manifest...');
    const importParams = new URLSearchParams({
      conflictStrategy: 'overwrite',
      autoPublishModels: 'true',
      autoPublishFields: 'true',
      autoPublishCommands: 'true',
      autoPublishPages: 'true',
    });
    const importResp = await fetch(
      `${options.target}/api/plugins/import/execute-direct?${importParams}`,
      {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
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
    const data = importResult.data || importResult;

    if (data.success === false) {
      log.error(`Import failed: ${data.errorMessage || 'Unknown error'}`);
      if (data.errors) {
        for (const err of data.errors) {
          log.error(`  ${err}`);
        }
      }
      process.exit(1);
    }

    // Show results
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

    // Step 3: Register in Marketplace
    log.blank();
    log.info('Registering in Marketplace...');
    const publishResp = await fetch(`${options.target}/api/marketplace/publish`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        pluginDir: dir,
        category: inferCategory(plugin.manifest.namespace),
      }),
    });

    if (publishResp.ok) {
      const publishData = await publishResp.json() as any;
      const mpId = publishData.data?.pluginId || plugin.manifest.pluginId;
      log.success('Marketplace registration complete!');
      console.log(chalk.dim(`Marketplace: ${options.target}/marketplace/${encodeURIComponent(mpId)}`));
    } else {
      log.warn(`Marketplace registration failed (${publishResp.status}) — plugin was still imported successfully`);
    }

    log.blank();
    const ns = plugin.manifest.namespace;
    console.log(chalk.dim(`Visit: ${options.target}/dynamic/${ns}-sample`));

  } catch (e) {
    log.error((e as Error).message);
    process.exit(1);
  }
}

function inferCategory(namespace: string): string {
  const mapping: Record<string, string> = {
    crm: 'crm', pe_crm: 'crm',
    sl: 'erp', sales: 'erp', pr: 'erp', procurement: 'erp', inv: 'erp', inventory: 'erp', prod: 'erp',
    fin: 'erp', finance: 'finance',
    pm: 'project-management',
    org: 'hr',
    acp: 'ai',
    pcba: 'industry', pcba_sol: 'industry', qo: 'industry', quarry_sol: 'industry',
  };
  return mapping[namespace] || 'utility';
}

function buildExtendedManifest(plugin: ReturnType<typeof loadPlugin>): any {
  return {
    ...plugin.manifest,
    models: plugin.resourceFiles.get('models') || [],
    fields: plugin.resourceFiles.get('fields') || [],
    modelFieldBindings: plugin.resourceFiles.get('bindings') || [],
    commands: plugin.resourceFiles.get('commands') || [],
    pages: plugin.resourceFiles.get('pages') || [],
    permissions: plugin.resourceFiles.get('permissions') || [],
    roles: plugin.resourceFiles.get('roles') || [],
    menus: plugin.resourceFiles.get('menus') || [],
    dicts: plugin.resourceFiles.get('dicts') || [],
    i18nResources: plugin.resourceFiles.get('i18n') || [],
    namedQueries: plugin.resourceFiles.get('named-queries') || [],
    savedViews: plugin.resourceFiles.get('saved-views') || [],
    bindingRules: plugin.resourceFiles.get('bindingRules') || [],
    dashboards: plugin.resourceFiles.get('dashboards') || [],
  };
}
