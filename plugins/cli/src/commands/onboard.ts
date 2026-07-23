import chalk from 'chalk';
import { writeAuraMcpConfig } from '../mcp/mcp-config.js';
import { installSkills, resolveBundleDir, resolveClients } from '../skills/install.js';

interface InitOpts {
  client?: string;
  root?: string;
}

/**
 * One-shot onboarding (`aura init`): install the end-user Skills into each
 * agent client and wire up the AuraBoot MCP server config, then point the user
 * at `aura doctor` to verify reachability.
 */
export async function onboardCommand(opts: InitOpts): Promise<void> {
  try {
    const root = opts.root ?? process.cwd();
    const clients = resolveClients(opts.client);
    const bundleDir = resolveBundleDir();

    const written = installSkills({ bundleDir, root, clients });
    console.log(chalk.green(`✓ Installed ${written.length} skill file(s) for [${clients.join(', ')}]`));

    for (const client of clients) {
      const res = writeAuraMcpConfig(root, client);
      if (res.action === 'manual') {
        console.log(
          chalk.yellow(
            `• ${client}: add the MCP server manually — command "aura", args ["mcp","serve"] (see README)`,
          ),
        );
      } else {
        console.log(chalk.green(`✓ MCP config ${res.action}: ${res.path}`));
      }
    }

    console.log(chalk.dim('\nRestart your agent client, then verify with: aura doctor'));
  } catch (e) {
    console.error(chalk.red((e as Error).message));
    process.exit(1);
  }
}
