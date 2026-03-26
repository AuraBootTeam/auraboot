import chalk from 'chalk';
import { resolveBaseUrl, loadCredentials, loadConfig } from '../client/auth.js';
import { resolveOutputOptions, type OutputOptions } from '../output/formatter.js';

interface StatusOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

interface ServiceStatus {
  name: string;
  url: string;
  status: 'up' | 'down' | 'unknown';
  latencyMs?: number;
  detail?: string;
}

export async function statusCommand(options: StatusOptions): Promise<void> {
  const config = loadConfig();
  const env = options.env || config.defaultEnv;
  const baseUrl = resolveBaseUrl(env);
  const creds = loadCredentials(env);
  const opts = resolveOutputOptions(options);

  const services: ServiceStatus[] = [];

  // Check backend health
  const backendStatus = await checkService('Backend', `${baseUrl}/actuator/health`);
  services.push(backendStatus);

  // Check auth status
  if (creds?.jwt) {
    const authStatus = await checkService('Auth', `${baseUrl}/api/user/profile`, creds.jwt);
    services.push({
      ...authStatus,
      name: 'Auth',
      detail: authStatus.status === 'up' ? `${creds.email}` : 'Token expired',
    });
  } else {
    services.push({ name: 'Auth', url: '', status: 'down', detail: 'Not logged in' });
  }

  // Check agent subsystem
  if (creds?.jwt) {
    const agentStatus = await checkService('Agent (ACP)', `${baseUrl}/api/agent/status`, creds.jwt);
    services.push(agentStatus);
  }

  if (opts.format === 'json' || opts.agentMode) {
    console.log(JSON.stringify({ env, baseUrl, services }, null, opts.agentMode ? 0 : 2));
    return;
  }

  // Pretty output
  console.log();
  console.log(chalk.bold(`  Aura Status`));
  console.log(chalk.dim(`  Environment: ${env} (${baseUrl})`));
  console.log();

  for (const svc of services) {
    const icon = svc.status === 'up' ? chalk.green('●') : svc.status === 'down' ? chalk.red('●') : chalk.yellow('●');
    const latency = svc.latencyMs ? chalk.dim(` ${svc.latencyMs}ms`) : '';
    const detail = svc.detail ? chalk.dim(` — ${svc.detail}`) : '';
    console.log(`  ${icon} ${svc.name.padEnd(16)}${latency}${detail}`);
  }

  console.log();

  const allUp = services.every(s => s.status === 'up');
  if (allUp) {
    console.log(chalk.green('  All services operational'));
  } else {
    const downCount = services.filter(s => s.status !== 'up').length;
    console.log(chalk.yellow(`  ${downCount} service(s) need attention`));
  }
  console.log();
}

async function checkService(name: string, url: string, token?: string): Promise<ServiceStatus> {
  try {
    const start = Date.now();
    const headers: Record<string, string> = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const resp = await fetch(url, {
      headers,
      signal: AbortSignal.timeout(5000),
    });
    const latencyMs = Date.now() - start;

    if (resp.ok || resp.status === 200) {
      return { name, url, status: 'up', latencyMs };
    }
    return { name, url, status: 'down', latencyMs, detail: `HTTP ${resp.status}` };
  } catch (e) {
    return { name, url, status: 'down', detail: (e as Error).message.split('\n')[0] };
  }
}
