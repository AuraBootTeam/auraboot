import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex } from '../../utils/resource-index.js';
import { successOutput, formatOutput, printDiagnostics, printStats, FormatOptions } from '../../utils/output-formatter.js';
import { checkMenuRouteMatch, checkMenuPermExists, DiagnosticMessage } from '../../validation/checks/menu-route.js';
import { checkRefFieldTarget, checkCmdModelExists, checkCmdInputFields, checkBindingComplete, checkPageModelExists, checkPageKeyConvention, checkDictReferenced } from '../../validation/checks/field-reference.js';
import { checkPermCrudCoverage } from '../../validation/checks/permission-coverage.js';
import { checkI18nModelLabel, checkI18nFieldLabel, checkI18nBilingual } from '../../validation/checks/i18n-completeness.js';
import { checkNsConsistency } from '../../validation/checks/naming-convention.js';
import chalk from 'chalk';

interface DiagnoseData {
  totalChecks: number;
  messages: DiagnosticMessage[];
  summary: { errors: number; warnings: number; infos: number };
  checkResults: Record<string, { passed: boolean; count: number }>;
}

const ALL_CHECKS: Array<{ id: string; severity: string; description: string; fn: (idx: any) => DiagnosticMessage[] }> = [
  // Errors
  { id: 'menu_route_match', severity: 'error', description: 'Menu path matches page/model', fn: checkMenuRouteMatch },
  { id: 'ref_field_target', severity: 'error', description: 'REFERENCE field target model exists', fn: checkRefFieldTarget },
  { id: 'cmd_model_exists', severity: 'error', description: 'Command modelCode exists', fn: checkCmdModelExists },
  // Warnings
  { id: 'perm_crud_coverage', severity: 'warning', description: 'ENTITY models have view+manage permissions', fn: checkPermCrudCoverage },
  { id: 'i18n_model_label', severity: 'warning', description: 'Models have i18n label', fn: checkI18nModelLabel },
  { id: 'i18n_field_label', severity: 'warning', description: 'Bound fields have i18n label', fn: checkI18nFieldLabel },
  { id: 'page_model_exists', severity: 'warning', description: 'Page modelCode exists', fn: checkPageModelExists },
  { id: 'page_key_convention', severity: 'warning', description: 'Page key follows convention', fn: checkPageKeyConvention },
  { id: 'binding_complete', severity: 'warning', description: 'Fields bound to at least one model', fn: checkBindingComplete },
  { id: 'ns_consistency', severity: 'warning', description: 'Resource codes follow namespace prefix', fn: checkNsConsistency },
  { id: 'cmd_input_fields', severity: 'warning', description: 'Command inputFields reference bound fields', fn: checkCmdInputFields },
  { id: 'menu_perm_exists', severity: 'warning', description: 'Menu permissionCode exists', fn: checkMenuPermExists },
  // Info
  { id: 'i18n_bilingual', severity: 'info', description: 'i18n entries have both zh-CN and en-US', fn: checkI18nBilingual },
  { id: 'dict_referenced', severity: 'info', description: 'Dicts are referenced by fields', fn: checkDictReferenced },
];

export async function diagnoseCommand(options: { dir: string; pretty: boolean; quiet: boolean; severity?: string }): Promise<void> {
  const files = loadPlugin(options.dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  const severityFilter = options.severity?.toLowerCase();
  const checksToRun = severityFilter
    ? ALL_CHECKS.filter(c => c.severity === severityFilter)
    : ALL_CHECKS;

  const allMessages: DiagnosticMessage[] = [];
  const checkResults: Record<string, { passed: boolean; count: number }> = {};

  for (const check of checksToRun) {
    const messages = check.fn(idx);
    checkResults[check.id] = { passed: messages.length === 0, count: messages.length };
    allMessages.push(...messages);
  }

  const errors = allMessages.filter(m => m.severity === 'error').length;
  const warnings = allMessages.filter(m => m.severity === 'warning').length;
  const infos = allMessages.filter(m => m.severity === 'info').length;

  const data: DiagnoseData = {
    totalChecks: checksToRun.length,
    messages: allMessages,
    summary: { errors, warnings, infos },
    checkResults,
  };

  const output = successOutput('dsl.diagnose', data, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);

    console.log(chalk.bold.cyan('Diagnostic Report'));
    console.log();

    // Check summary table
    console.log(chalk.bold('Checks:'));
    for (const check of checksToRun) {
      const result = checkResults[check.id];
      const icon = result.passed ? chalk.green('✓') : (check.severity === 'error' ? chalk.red('✗') : chalk.yellow('⚠'));
      const count = result.count > 0 ? chalk.dim(` (${result.count})`) : '';
      console.log(`  ${icon} ${check.id}: ${check.description}${count}`);
    }

    console.log();
    printStats({ 'Errors': errors, 'Warnings': warnings, 'Info': infos });

    if (allMessages.length > 0) {
      printDiagnostics(allMessages);
    } else {
      console.log(chalk.green('\n✓ All checks passed.'));
    }
  } else {
    formatOutput(output, fmt);
  }

  // Exit with error code if errors found
  if (errors > 0) process.exit(1);
}
