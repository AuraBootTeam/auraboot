import { existsSync, readFileSync, writeFileSync } from 'fs';
import { join, resolve } from 'path';
import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex } from '../../utils/resource-index.js';
import { successOutput, formatOutput, FormatOptions } from '../../utils/output-formatter.js';
import chalk from 'chalk';

interface SyncResult {
  totalExpected: number;
  existing: number;
  missing: number;
  generated: string[];
  bilingual: { total: number; zhOnly: number; enOnly: number; both: number };
}

export async function syncI18nCommand(options: { dir: string; pretty: boolean; quiet: boolean; dryRun?: boolean }): Promise<void> {
  const dir = resolve(options.dir);
  const files = loadPlugin(dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  const existing = new Map(idx.raw.i18n.map((e: any) => [e.key, e]));
  const missingKeys: string[] = [];
  const newEntries: any[] = [];

  // Expected model labels
  for (const m of idx.raw.models) {
    const key = `model.${m.code}._meta.label`;
    if (!existing.has(key)) {
      missingKeys.push(key);
      newEntries.push({
        key,
        'zh-CN': m['displayName:zh-CN'] || m.displayName || m.code,
        'en-US': m['displayName:en'] || m.displayName || m.code,
        source: 'import',
        refType: 'model',
      });
    }
  }

  // Expected field labels (only for bound fields)
  for (const b of idx.raw.bindings) {
    const key = `model.${b.modelCode}.${b.fieldCode}.label`;
    if (!existing.has(key)) {
      const field = idx.fields.get(b.fieldCode);
      missingKeys.push(key);
      newEntries.push({
        key,
        'zh-CN': field?.['displayName:zh-CN'] || field?.displayName || b.fieldCode,
        'en-US': field?.['displayName:en'] || field?.displayName || b.fieldCode,
        source: 'import',
        refType: 'field',
      });
    }
  }

  // Expected command labels
  for (const c of idx.raw.commands) {
    const key = `command.${c.code}.label`;
    if (!existing.has(key)) {
      missingKeys.push(key);
      newEntries.push({
        key,
        'zh-CN': c['displayName:zh-CN'] || c.displayName || c.code,
        'en-US': c['displayName:en'] || c.displayName || c.code,
        source: 'import',
        refType: 'command',
      });
    }
  }

  // Bilingual check
  let zhOnly = 0, enOnly = 0, both = 0;
  const allEntries = [...idx.raw.i18n, ...newEntries];
  for (const e of idx.raw.i18n) {
    const hasZh = !!e['zh-CN'];
    const hasEn = !!e['en-US'];
    if (hasZh && hasEn) both++;
    else if (hasZh) zhOnly++;
    else if (hasEn) enOnly++;
  }

  const result: SyncResult = {
    totalExpected: idx.expectedI18nKeys.length + idx.raw.commands.length,
    existing: existing.size,
    missing: missingKeys.length,
    generated: missingKeys,
    bilingual: { total: idx.raw.i18n.length, zhOnly, enOnly, both },
  };

  if (!options.dryRun && missingKeys.length > 0) {
    // Write merged i18n
    const i18nPath = join(dir, 'config', 'i18n.json');
    const merged = [...idx.raw.i18n, ...newEntries];
    writeFileSync(i18nPath, JSON.stringify(merged, null, 2) + '\n', 'utf-8');
  }

  const output = successOutput('dsl.sync-i18n', { ...result, dryRun: options.dryRun ?? false }, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);

    if (options.dryRun) {
      console.log(chalk.bold.cyan('[DRY RUN] i18n Sync Report'));
    } else {
      console.log(chalk.bold.cyan('i18n Sync Report'));
    }
    console.log();
    console.log(`  Existing keys: ${chalk.bold(String(existing.size))}`);
    console.log(`  Missing keys:  ${missingKeys.length > 0 ? chalk.yellow(String(missingKeys.length)) : chalk.green('0')}`);
    console.log();

    console.log(chalk.bold('Bilingual Coverage:'));
    console.log(`  Both zh-CN & en-US: ${chalk.green(String(both))}`);
    console.log(`  zh-CN only:         ${zhOnly > 0 ? chalk.yellow(String(zhOnly)) : '0'}`);
    console.log(`  en-US only:         ${enOnly > 0 ? chalk.yellow(String(enOnly)) : '0'}`);

    if (missingKeys.length > 0) {
      console.log(chalk.bold(`\nGenerated ${missingKeys.length} keys:`));
      for (const k of missingKeys.slice(0, 20)) {
        console.log(`  ${chalk.green('+')} ${k}`);
      }
      if (missingKeys.length > 20) {
        console.log(chalk.dim(`  ... and ${missingKeys.length - 20} more`));
      }

      if (options.dryRun) {
        console.log(chalk.yellow('\nNo files modified (--dry-run).'));
      } else {
        console.log(chalk.green(`\n✓ Updated config/i18n.json`));
      }
    } else {
      console.log(chalk.green('\n✓ All i18n keys present.'));
    }
  } else {
    formatOutput(output, fmt);
  }
}
