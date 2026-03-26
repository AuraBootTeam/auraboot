import chalk from 'chalk';
import { createInterface } from 'readline';
import { resolveToken, resolveBaseUrl } from '../client/auth.js';

const COMMANDS: Record<string, string> = {
  'help':       'Show available commands',
  'crm leads':  'List CRM leads',
  'crm opps':   'List opportunities',
  'crm accounts': 'List accounts',
  'crm dashboard': 'CRM KPI summary',
  'project list': 'List projects',
  'project tasks': 'List tasks',
  'project dashboard': 'PM KPI summary',
  'ops agents':  'List agents',
  'ops tools':   'List tools',
  'ops runs':    'List runs',
  'ops audit':   'List audit traces',
  'exit':        'Exit shell',
  'quit':        'Exit shell',
};

interface ShellOptions {
  token?: string;
  env?: string;
}

export async function shellCommand(options: ShellOptions): Promise<void> {
  const token = resolveToken(options);
  const baseUrl = resolveBaseUrl(options.env);

  if (!token) {
    console.error(chalk.red('Not authenticated. Run: aura login'));
    process.exit(5);
  }

  console.log(chalk.bold.cyan('\n  Aura Shell'));
  console.log(chalk.dim(`  Connected to ${baseUrl}`));
  console.log(chalk.dim('  Type "help" for commands, "exit" to quit'));
  console.log();

  const rl = createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: chalk.cyan('aura> '),
    completer: (line: string) => {
      const completions = Object.keys(COMMANDS);
      const hits = completions.filter(c => c.startsWith(line));
      return [hits.length ? hits : completions, line];
    },
  });

  rl.prompt();

  rl.on('line', async (line) => {
    const input = line.trim();

    if (!input) {
      rl.prompt();
      return;
    }

    if (input === 'exit' || input === 'quit') {
      console.log(chalk.dim('  Goodbye'));
      rl.close();
      process.exit(0);
    }

    if (input === 'help') {
      console.log();
      for (const [cmd, desc] of Object.entries(COMMANDS)) {
        console.log(`  ${chalk.bold(cmd.padEnd(20))} ${chalk.dim(desc)}`);
      }
      console.log();
      console.log(chalk.dim('  Or type any question in natural language (routes to AuraBot)'));
      console.log();
      rl.prompt();
      return;
    }

    // Route to CLI commands by spawning self
    try {
      const { execFileSync } = await import('child_process');
      const args = input.split(/\s+/);

      // Pass auth context
      const execArgs = [...args, '--token', token!, '--format', 'table'];

      const result = execFileSync(
        process.argv[0],  // node
        [process.argv[1], ...execArgs],  // dist/index.js + args
        {
          encoding: 'utf-8',
          stdio: ['pipe', 'pipe', 'pipe'],
          timeout: 30000,
          env: { ...process.env, NO_PROXY: 'localhost' },
        },
      );
      if (result) console.log(result);
    } catch (err: any) {
      if (err.stdout) console.log(err.stdout);
      if (err.stderr) console.error(chalk.red(err.stderr.toString().trim()));
    }

    rl.prompt();
  });

  rl.on('close', () => {
    process.exit(0);
  });
}
