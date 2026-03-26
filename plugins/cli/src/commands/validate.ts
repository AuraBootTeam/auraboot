import chalk from 'chalk';
import { loadPlugin, countResources } from '../utils/plugin-loader.js';
import { log, formatSummary } from '../utils/logger.js';
import { validateStructural } from '../validation/structural.js';
import { validateSemantic } from '../validation/semantic.js';
import { validateGovernance } from '../validation/governance.js';
import { type ValidationResult, type ValidationMessage, createResult, mergeResults } from '../validation/types.js';

/**
 * Validate a plugin directory.
 */
export async function validateCommand(dir: string): Promise<void> {
  try {
    const plugin = loadPlugin(dir);
    const resourceCount = countResources(plugin);

    log.header(`Validating: ${plugin.manifest.pluginId} v${plugin.manifest.version}`);
    log.dim(`${resourceCount} resources in ${plugin.resourceFiles.size} files`);
    log.blank();

    const overall = createResult();

    // Layer 1: Structural
    console.log(chalk.bold('[Layer 1: Structural]'));
    const structural = validateStructural(plugin);
    mergeResults(overall, structural);
    printLayerMessages(structural);

    // Short-circuit: skip semantic/governance if structural errors
    if (structural.errorCount > 0) {
      log.blank();
      log.error('Structural errors found, skipping semantic and governance checks.');
      printSummary(overall);
      process.exit(1);
    }

    // Layer 2: Semantic
    console.log(chalk.bold('\n[Layer 2: Semantic]'));
    const semantic = validateSemantic(plugin);
    mergeResults(overall, semantic);
    printLayerMessages(semantic);

    // Short-circuit: skip governance if semantic errors
    if (semantic.errorCount > 0) {
      log.blank();
      log.error('Semantic errors found, skipping governance checks.');
      printSummary(overall);
      process.exit(1);
    }

    // Layer 3: Governance
    console.log(chalk.bold('\n[Layer 3: Governance]'));
    const governance = validateGovernance(plugin);
    mergeResults(overall, governance);
    printLayerMessages(governance);

    // Print summary
    printSummary(overall);

    if (overall.errorCount > 0) {
      process.exit(1);
    }
  } catch (e) {
    log.error((e as Error).message);
    process.exit(1);
  }
}

function printLayerMessages(result: ValidationResult): void {
  if (result.messages.length === 0) {
    log.success('All checks passed');
    return;
  }

  for (const msg of result.messages) {
    printMessage(msg);
  }

  // If no errors in this layer, show a success marker
  if (result.errorCount === 0) {
    const nonInfo = result.messages.filter((m) => m.severity !== 'info');
    if (nonInfo.length === 0) {
      log.success('All checks passed');
    }
  }
}

function printMessage(msg: ValidationMessage): void {
  const icon =
    msg.severity === 'error' ? chalk.red('✗') :
    msg.severity === 'warning' ? chalk.yellow('⚠') :
    chalk.blue('ℹ');

  const text =
    msg.severity === 'error' ? chalk.red(msg.message) :
    msg.severity === 'warning' ? chalk.yellow(msg.message) :
    chalk.dim(msg.message);

  console.log(`  ${icon} ${text}`);

  if (msg.path) {
    console.log(chalk.dim(`    at ${msg.path}`));
  }
  if (msg.suggestion) {
    console.log(chalk.dim(`    💡 ${msg.suggestion}`));
  }
}

function printSummary(result: ValidationResult): void {
  log.blank();
  console.log(
    chalk.bold('Summary: ') + formatSummary(result.errorCount, result.warningCount, result.infoCount)
  );
}
