import chalk from 'chalk';
import { join } from 'path';
import { computePlan, type DslPlan } from '../../dsl/plan.js';
import { fingerprint, readState, writeState } from '../../dsl/state.js';
import { loadPlugin } from '../../utils/plugin-loader.js';
import { publishCommand } from '../publish.js';

interface ReconcileOpts {
  agentMode?: boolean;
  format?: string;
  yes?: boolean;
  dryRun?: boolean;
  target?: string;
  user?: string;
  password?: string;
}

function statePath(pluginDir: string): string {
  return join(pluginDir, '.aura', 'dsl-state.json');
}

function isJson(opts: ReconcileOpts): boolean {
  return Boolean(opts.agentMode) || opts.format === 'json';
}

function printPlan(plan: DslPlan, hasBaseline: boolean): void {
  if (!hasBaseline) {
    console.log(chalk.dim('No prior state — treating every resource as new (first apply).'));
  }
  console.log(chalk.bold(`Plan: risk ${plan.riskLevel}${plan.changed ? '' : ' (no changes)'}`));
  const line = (label: string, items: string[], color: (s: string) => string) => {
    if (items.length) console.log(`  ${color(label)} ${items.length}: ${items.join(', ')}`);
  };
  line('+ create', plan.create, chalk.green);
  line('~ update', plan.update, chalk.yellow);
  line('- destroy', plan.destroy, chalk.red);
}

function loadPlan(dir: string): { pluginId: string; hasBaseline: boolean; plan: DslPlan; desired: ReturnType<typeof fingerprint>; sp: string } {
  const files = loadPlugin(dir);
  const desired = fingerprint(files);
  const sp = statePath(files.dir);
  const prior = readState(sp);
  return { pluginId: desired.pluginId, hasBaseline: prior !== null, plan: computePlan(desired, prior), desired, sp };
}

export async function dslPlanCommand(dir: string, opts: ReconcileOpts): Promise<void> {
  const { pluginId, hasBaseline, plan } = loadPlan(dir);
  if (isJson(opts)) {
    console.log(JSON.stringify({ pluginId, hasBaseline, ...plan }));
    return;
  }
  printPlan(plan, hasBaseline);
}

export async function dslDriftCommand(dir: string, opts: ReconcileOpts): Promise<void> {
  const { pluginId, hasBaseline, plan } = loadPlan(dir);
  const drifted = plan.changed;
  if (isJson(opts)) {
    console.log(JSON.stringify({ pluginId, hasBaseline, drifted, ...plan }));
  } else if (!hasBaseline) {
    console.log(chalk.yellow('No baseline state — run `aura dsl apply` (or `plan`) first.'));
  } else if (drifted) {
    console.log(chalk.yellow(`Local DSL has drifted from last apply (risk ${plan.riskLevel}):`));
    printPlan(plan, true);
  } else {
    console.log(chalk.green('No drift — local DSL matches the last applied state.'));
  }
  process.exit(drifted ? 1 : 0);
}

export async function dslApplyCommand(dir: string, opts: ReconcileOpts): Promise<void> {
  const { hasBaseline, plan, desired, sp } = loadPlan(dir);

  if (!plan.changed) {
    console.log(chalk.green('Nothing to apply (L0 — already up to date).'));
    return;
  }

  if (opts.dryRun) {
    printPlan(plan, hasBaseline);
    console.log(chalk.dim('(dry-run — nothing published)'));
    return;
  }

  // Destructive plans require explicit approval, aligning with the platform
  // approval gate (§3A-G3). L3 = destroys / drops.
  if (plan.riskLevel === 'L3' && !opts.yes) {
    console.error(
      chalk.red(
        `Plan is L3 (destroys ${plan.destroy.length}: ${plan.destroy.join(', ')}). Re-run with --yes to approve.`,
      ),
    );
    process.exit(1);
  }

  printPlan(plan, hasBaseline);
  // Publish to the instance (requires a reachable backend). On success, record
  // the new desired state as the baseline for the next plan/drift.
  await publishCommand(dir, {
    target: opts.target ?? 'http://localhost:6443',
    user: opts.user,
    password: opts.password,
    yes: true,
  });
  writeState(sp, desired);
  console.log(chalk.green(`✓ Applied (risk ${plan.riskLevel}); state baseline updated at ${sp}`));
}
