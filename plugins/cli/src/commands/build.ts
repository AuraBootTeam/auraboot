import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { join, resolve } from 'path';
import chalk from 'chalk';
import { log } from '../utils/logger.js';
import { loadPlugin, countResources } from '../utils/plugin-loader.js';
import { validateStructural } from '../validation/structural.js';
import { validateSemantic } from '../validation/semantic.js';

interface BuildOptions {
  output: string;
}

/**
 * Build and package a plugin.
 */
export async function buildCommand(dir: string, options: BuildOptions): Promise<void> {
  try {
    const plugin = loadPlugin(dir);
    const resourceCount = countResources(plugin);

    log.header(`Building: ${plugin.manifest.pluginId} v${plugin.manifest.version}`);
    log.blank();

    // Step 1: Validate
    log.info('Running validation...');
    const structural = validateStructural(plugin);
    if (structural.errorCount > 0) {
      log.error('Structural validation failed. Run "aura plugin validate" for details.');
      process.exit(1);
    }

    const semantic = validateSemantic(plugin);
    if (semantic.errorCount > 0) {
      log.error('Semantic validation failed. Run "aura plugin validate" for details.');
      process.exit(1);
    }
    log.success('Validation passed');

    // Step 2: Build combined manifest
    log.info('Bundling config files...');
    const manifest = {
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
    };

    // Step 3: Write output
    const outputDir = resolve(dir, options.output);
    if (!existsSync(outputDir)) {
      mkdirSync(outputDir, { recursive: true });
    }

    const outputFile = join(outputDir, `${plugin.manifest.namespace}-${plugin.manifest.version}.json`);
    writeFileSync(outputFile, JSON.stringify(manifest, null, 2) + '\n');
    log.success(`Config files bundled`);

    const sizeKb = Math.round(readFileSync(outputFile).length / 1024);
    log.success(`Package created: ${chalk.cyan(outputFile)} (${sizeKb} KB)`);

    log.blank();
    console.log(chalk.bold('Contents:'));
    log.dim(`plugin.json + ${plugin.resourceFiles.size} resource types (${resourceCount} items)`);

  } catch (e) {
    log.error((e as Error).message);
    process.exit(1);
  }
}
