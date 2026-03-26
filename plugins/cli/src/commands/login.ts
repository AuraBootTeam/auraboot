import chalk from 'chalk';
import inquirer from 'inquirer';
import { login, resolveBaseUrl, loadConfig, type UserSpace } from '../client/auth.js';

interface LoginOptions {
  env?: string;
  user?: string;
  password?: string;
  tenant?: string;
}

export async function loginCommand(options: LoginOptions): Promise<void> {
  const env = options.env || loadConfig().defaultEnv;
  const baseUrl = resolveBaseUrl(env);

  console.log(chalk.dim(`Environment: ${env} (${baseUrl})`));
  console.log();

  // Resolve email and password
  let email = options.user || process.env.AURA_USER;
  let password = options.password || process.env.AURA_PASSWORD;

  if (!email || !password) {
    const answers = await inquirer.prompt([
      ...(!email ? [{
        type: 'input' as const,
        name: 'email',
        message: 'Email:',
        validate: (v: string) => v.includes('@') || 'Enter a valid email',
      }] : []),
      ...(!password ? [{
        type: 'password' as const,
        name: 'password',
        message: 'Password:',
        mask: '*',
        validate: (v: string) => v.length > 0 || 'Password required',
      }] : []),
    ]);
    email = email || answers.email;
    password = password || answers.password;
  }

  try {
    let tenantName = options.tenant;

    // First login attempt (may need space selection)
    const result = await login(baseUrl, email!, password!, env, tenantName);

    // If user has multiple spaces and didn't specify --tenant, prompt interactively
    if (!tenantName && result.spaces.length > 1 && !result.selectedSpace) {
      console.log();
      console.log(chalk.yellow('Multiple workspaces available:'));
      const { chosen } = await inquirer.prompt([{
        type: 'list',
        name: 'chosen',
        message: 'Select workspace:',
        choices: result.spaces.map((s: UserSpace) => ({
          name: `${s.spaceType === 'platform' ? '⚙️' : '🏢'} ${s.tenantDisplayName || s.tenantName} (${s.spaceType})`,
          value: s.tenantName,
        })),
      }]);
      // Re-login with selected tenant
      await login(baseUrl, email!, password!, env, chosen);
      tenantName = chosen;
    }

    console.log(chalk.green('✓'), `Authenticated as ${chalk.bold(email)}`);
    if (result.selectedSpace) {
      const s = result.selectedSpace;
      const icon = s.spaceType === 'platform' ? '⚙️' : '🏢';
      console.log(chalk.green('✓'), `Space: ${icon} ${chalk.bold(s.tenantDisplayName || s.tenantName)} (${s.spaceType})`);
    } else if (result.spaces.length > 0) {
      const biz = result.spaces.find((s: UserSpace) => s.spaceType === 'business');
      if (biz) {
        console.log(chalk.dim(`  Space: ${biz.tenantDisplayName || biz.tenantName}`));
      }
    }
    console.log(chalk.dim(`  Token saved to ~/.aura/credentials.json`));

    // Show available spaces hint
    if (result.spaces.length > 1) {
      console.log(chalk.dim(`  Switch space: aura login --tenant "<name>"`));
    }
  } catch (err) {
    console.error(chalk.red('✗'), (err as Error).message);
    process.exit(1);
  }
}
