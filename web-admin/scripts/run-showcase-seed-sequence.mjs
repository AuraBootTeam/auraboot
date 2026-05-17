import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
export const WEB_ADMIN_ROOT = resolve(scriptDir, '..');

export const PHASES = [
  ['data', 'tests/api/setup/seed-showcase-data.spec.ts'],
  ['extended', 'tests/api/setup/seed-showcase-extended.spec.ts'],
  ['workflow', 'tests/api/setup/seed-showcase-workflow.spec.ts'],
  ['ai', 'tests/api/setup/seed-showcase-ai.spec.ts'],
  ['arsenal', 'tests/api/setup/seed-showcase-arsenal.spec.ts'],
  ['supplement', 'tests/api/setup/seed-showcase-supplement.spec.ts'],
  ['commercial', 'tests/api/setup/seed-showcase-commercial.spec.ts'],
  ['dashboard-default', 'tests/api/setup/seed-showcase-dashboard-default.spec.ts'],
  ['invariants', 'tests/api/setup/seed-showcase-invariants.spec.ts'],
].map(([name, spec]) => ({ name, spec }));

export const KNOWN_PHASE_NAMES = PHASES.map((phase) => phase.name);
export const DEFAULT_PHASE_ORDER = [
  'data',
  'extended',
  'workflow',
  'ai',
  'arsenal',
  'supplement',
  'dashboard-default',
  'invariants',
];

const phaseByName = new Map(PHASES.map((phase) => [phase.name, phase]));

export function resolvePhases(names) {
  const requested = names.length > 0 ? names : DEFAULT_PHASE_ORDER;
  return requested.map((name) => {
    const phase = phaseByName.get(name);
    if (!phase) {
      throw new Error(
        `Unknown showcase seed phase "${name}". Known phases: ${KNOWN_PHASE_NAMES.join(', ')}`,
      );
    }
    return phase;
  });
}

export function buildPlaywrightArgs(phase, options) {
  return [
    'playwright',
    'test',
    phase.spec,
    `--config=${options.config}`,
    `--reporter=${options.reporter}`,
    `--output=${options.outputPrefix}-${phase.name}`,
  ];
}

function parseArgs(argv) {
  const options = {
    config: 'playwright.seed.config.ts',
    reporter: 'line',
    outputPrefix: 'test-results/seed/showcase',
    dryRun: false,
    list: false,
  };
  const phaseNames = [];

  for (const arg of argv) {
    if (arg === '--dry-run') {
      options.dryRun = true;
    } else if (arg === '--list') {
      options.list = true;
    } else if (arg === '-h' || arg === '--help') {
      options.help = true;
    } else if (arg.startsWith('--config=')) {
      options.config = arg.slice('--config='.length);
    } else if (arg.startsWith('--reporter=')) {
      options.reporter = arg.slice('--reporter='.length);
    } else if (arg.startsWith('--output-prefix=')) {
      options.outputPrefix = arg.slice('--output-prefix='.length);
    } else if (arg.startsWith('-')) {
      throw new Error(`Unknown option "${arg}"`);
    } else {
      phaseNames.push(arg);
    }
  }

  return { options, phaseNames };
}

function printHelp() {
  console.log(`Usage: node scripts/run-showcase-seed-sequence.mjs [options] [phase...]

Runs showcase seed specs in a deterministic sequence, one Playwright process per
phase. This avoids Playwright file sorting, grep matching, and accidental
multi-spec parallelization changing seed dependencies.

Options:
  --config=<file>          Playwright config (default: playwright.seed.config.ts)
  --reporter=<name>        Playwright reporter (default: line)
  --output-prefix=<path>   Output path prefix (default: test-results/seed/showcase)
  --dry-run                Print commands without running them
  --list                   Print known phases
  -h, --help               Show this help

Default order:
  ${DEFAULT_PHASE_ORDER.join(' ')}
`);
}

function ensureSpecsExist(phases) {
  for (const phase of phases) {
    const absoluteSpec = resolve(WEB_ADMIN_ROOT, phase.spec);
    if (!existsSync(absoluteSpec)) {
      throw new Error(`Seed spec for phase "${phase.name}" does not exist: ${absoluteSpec}`);
    }
  }
}

function shellQuote(value) {
  if (/^[A-Za-z0-9_./:=+-]+$/.test(value)) {
    return value;
  }
  return `'${value.replaceAll("'", "'\\''")}'`;
}

function commandLine(args) {
  return ['npx', ...args].map(shellQuote).join(' ');
}

function runPhase(phase, options) {
  const args = buildPlaywrightArgs(phase, options);
  console.log(`[showcase-seed] ${phase.name}: ${commandLine(args)}`);

  if (options.dryRun) {
    return 0;
  }

  const result = spawnSync('npx', args, {
    cwd: WEB_ADMIN_ROOT,
    stdio: 'inherit',
    env: {
      ...process.env,
      NO_PROXY: process.env.NO_PROXY || 'localhost,127.0.0.1',
    },
  });

  if (result.error) {
    throw result.error;
  }
  return result.status ?? 1;
}

export function main(argv = process.argv.slice(2)) {
  const { options, phaseNames } = parseArgs(argv);

  if (options.help) {
    printHelp();
    return 0;
  }

  if (options.list) {
    console.log(KNOWN_PHASE_NAMES.join('\n'));
    return 0;
  }

  const phases = resolvePhases(phaseNames);
  ensureSpecsExist(phases);

  for (const phase of phases) {
    const status = runPhase(phase, options);
    if (status !== 0) {
      console.error(`[showcase-seed] ${phase.name} failed with exit code ${status}`);
      return status;
    }
  }

  console.log(`[showcase-seed] completed ${phases.length} phase(s)`);
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main();
  } catch (error) {
    console.error(`[showcase-seed] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
