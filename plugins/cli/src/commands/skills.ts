import chalk from 'chalk';
import {
  checkSkills,
  installSkills,
  listBundledSkills,
  removeSkills,
  resolveBundleDir,
  resolveClients,
  type SkillClient,
} from '../skills/install.js';

interface SkillsOpts {
  client?: string;
  root?: string;
  agentMode?: boolean;
  format?: string;
}

function isJson(opts: SkillsOpts): boolean {
  return Boolean(opts.agentMode) || opts.format === 'json';
}

function resolveContext(opts: SkillsOpts): {
  bundleDir: string;
  root: string;
  clients: SkillClient[];
} {
  return {
    bundleDir: resolveBundleDir(),
    root: opts.root ?? process.cwd(),
    clients: resolveClients(opts.client),
  };
}

function fail(e: unknown): never {
  console.error(chalk.red((e as Error).message));
  process.exit(1);
}

export async function skillsListCommand(opts: SkillsOpts): Promise<void> {
  const skills = listBundledSkills(resolveBundleDir());
  if (isJson(opts)) {
    console.log(JSON.stringify({ skills: skills.map((s) => s.name) }));
    return;
  }
  console.log(chalk.bold(`Bundled AuraBoot skills (${skills.length}):`));
  for (const s of skills) console.log(`  • ${s.name}`);
  console.log(chalk.dim('\nInstall with: aura skills install [--client claude|cursor|codex|all]'));
}

export async function skillsCheckCommand(opts: SkillsOpts): Promise<void> {
  try {
    const ctx = resolveContext(opts);
    const report = checkSkills(ctx);
    if (isJson(opts)) {
      console.log(JSON.stringify({ root: ctx.root, report }));
      return;
    }
    console.log(chalk.bold(`Skills status (root: ${ctx.root})`));
    for (const r of report) {
      const mark = !r.installed
        ? chalk.yellow('not installed')
        : r.upToDate
          ? chalk.green('up to date')
          : chalk.yellow('stale');
      console.log(`  ${r.client}/${r.skill}: ${mark}`);
    }
  } catch (e) {
    fail(e);
  }
}

export async function skillsInstallCommand(opts: SkillsOpts): Promise<void> {
  try {
    const ctx = resolveContext(opts);
    const written = installSkills(ctx);
    if (isJson(opts)) {
      console.log(JSON.stringify({ installed: written }));
      return;
    }
    console.log(
      chalk.green(
        `✓ Installed ${written.length} skill file(s) for [${ctx.clients.join(', ')}] under ${ctx.root}`,
      ),
    );
    console.log(chalk.dim('Restart the agent client so it picks up the new skills.'));
  } catch (e) {
    fail(e);
  }
}

export async function skillsRemoveCommand(opts: SkillsOpts): Promise<void> {
  try {
    const ctx = resolveContext(opts);
    const removed = removeSkills(ctx);
    if (isJson(opts)) {
      console.log(JSON.stringify({ removed }));
      return;
    }
    console.log(chalk.green(`✓ Removed ${removed.length} skill dir(s) for [${ctx.clients.join(', ')}]`));
  } catch (e) {
    fail(e);
  }
}
