import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex } from '../../utils/resource-index.js';
import { successOutput, formatOutput, printStats, FormatOptions } from '../../utils/output-formatter.js';
import chalk from 'chalk';

interface StatusData {
  pluginId: string;
  namespace: string;
  version: string;
  counts: Record<string, number>;
  healthScore: number;
  issues: StatusIssue[];
  orphans: OrphanInfo[];
}

interface StatusIssue {
  severity: 'error' | 'warning' | 'info';
  message: string;
}

interface OrphanInfo {
  type: string;
  code: string;
  reason: string;
}

export async function statusCommand(options: { dir: string; pretty: boolean; quiet: boolean }): Promise<void> {
  const files = loadPlugin(options.dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  // Resource counts
  const counts: Record<string, number> = {
    models: idx.raw.models.length,
    fields: idx.raw.fields.length,
    bindings: idx.raw.bindings.length,
    commands: idx.raw.commands.length,
    pages: idx.raw.pages.length,
    permissions: idx.raw.permissions.length,
    menus: idx.raw.menus.length,
    dicts: idx.raw.dicts.length,
    i18n: idx.raw.i18n.length,
  };

  const issues: StatusIssue[] = [];
  const orphans: OrphanInfo[] = [];

  // Find orphan fields (not bound to any model)
  for (const f of idx.raw.fields) {
    if (!(idx.bindingsByField.get(f.code) || []).length) {
      orphans.push({ type: 'field', code: f.code, reason: 'Not bound to any model' });
    }
  }

  // Find commands with missing models
  for (const c of idx.raw.commands) {
    if (c.modelCode && !idx.models.has(c.modelCode)) {
      issues.push({ severity: 'error', message: `Command '${c.code}' references missing model '${c.modelCode}'` });
    }
  }

  // Find pages with missing models
  for (const p of idx.raw.pages) {
    const mc = p.modelCode || p.dslSchema?.modelCode;
    if (mc && !idx.models.has(mc)) {
      issues.push({ severity: 'error', message: `Page '${p.pageKey}' references missing model '${mc}'` });
    }
  }

  // Missing i18n
  if (idx.missingI18nKeys.length > 0) {
    issues.push({
      severity: 'warning',
      message: `${idx.missingI18nKeys.length} missing i18n keys (of ${idx.expectedI18nKeys.length} expected)`,
    });
  }

  // Models without commands
  for (const m of idx.raw.models) {
    if (m.modelType === 'entity' && !(idx.commandsByModel.get(m.code) || []).length) {
      issues.push({ severity: 'warning', message: `ENTITY model '${m.code}' has no commands` });
    }
  }

  // Models without pages
  for (const m of idx.raw.models) {
    if (m.modelType === 'entity' && !(idx.pagesByModel.get(m.code) || []).length) {
      issues.push({ severity: 'info', message: `Model '${m.code}' has no pages` });
    }
  }

  // Health score: 100 - deductions
  let score = 100;
  const errors = issues.filter(i => i.severity === 'error').length;
  const warnings = issues.filter(i => i.severity === 'warning').length;
  score -= errors * 15;
  score -= warnings * 5;
  score -= Math.min(orphans.length * 2, 20);
  if (idx.expectedI18nKeys.length > 0) {
    const i18nCoverage = 1 - idx.missingI18nKeys.length / idx.expectedI18nKeys.length;
    score -= Math.round((1 - i18nCoverage) * 15);
  }
  const healthScore = Math.max(0, Math.min(100, score));

  const data: StatusData = {
    pluginId: files.manifest.pluginId,
    namespace: files.manifest.namespace,
    version: files.manifest.version,
    counts,
    healthScore,
    issues,
    orphans,
  };

  const output = successOutput('dsl.status', data, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);

    // Health score with color
    const scoreColor = healthScore >= 80 ? chalk.green : healthScore >= 60 ? chalk.yellow : chalk.red;
    console.log(chalk.bold(`Health Score: ${scoreColor(String(healthScore) + '/100')}`));
    console.log();

    console.log(chalk.bold('Resource Counts:'));
    printStats(counts);

    if (issues.length > 0) {
      console.log(chalk.bold('\nIssues:'));
      for (const issue of issues) {
        const icon = issue.severity === 'error' ? chalk.red('✗') : issue.severity === 'warning' ? chalk.yellow('⚠') : chalk.blue('ℹ');
        console.log(`  ${icon} ${issue.message}`);
      }
    }

    if (orphans.length > 0) {
      console.log(chalk.bold(`\nOrphan Resources (${orphans.length}):`));
      for (const o of orphans) {
        console.log(chalk.dim(`  ${o.type}: ${o.code} — ${o.reason}`));
      }
    }

    if (issues.length === 0 && orphans.length === 0) {
      console.log(chalk.green('\n✓ No issues found.'));
    }
  } else {
    formatOutput(output, fmt);
  }
}
