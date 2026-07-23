import chalk from 'chalk';
import { resolveBaseUrl, resolveToken } from '../client/auth.js';
import { evaluateDoctor, type DoctorFacts } from '../doctor/evaluate.js';
import { resolveTenantContext } from '../mcp/tenant-pin.js';
import { SKILL_CLIENTS, checkSkills, resolveBundleDir } from '../skills/install.js';

interface DoctorOpts {
  token?: string;
  env?: string;
  root?: string;
  agentMode?: boolean;
  format?: string;
}

/** Skills fact = the best-provisioned client (a user typically sets up one). */
function bestSkillsFact(root: string): DoctorFacts['skills'] {
  const bundleDir = resolveBundleDir();
  const perClient = SKILL_CLIENTS.map((client) => {
    const report = checkSkills({ bundleDir, root, clients: [client] });
    return {
      installed: report.filter((r) => r.installed).length,
      stale: report.filter((r) => r.installed && !r.upToDate).length,
      total: report.length,
    };
  });
  return perClient.reduce((a, b) => (b.installed > a.installed ? b : a));
}

async function probeBackend(
  baseUrl: string,
  token: string | null,
): Promise<{ reachable: boolean; detail: string }> {
  try {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    const resp = await fetch(`${baseUrl}/actuator/health`, {
      headers,
      signal: AbortSignal.timeout(5000),
    });
    return { reachable: resp.ok, detail: `${baseUrl} → HTTP ${resp.status}` };
  } catch (e) {
    return { reachable: false, detail: `${baseUrl} unreachable: ${(e as Error).message.split('\n')[0]}` };
  }
}

export async function doctorCommand(opts: DoctorOpts): Promise<void> {
  const root = opts.root ?? process.cwd();
  const token = resolveToken({ token: opts.token, env: opts.env });
  const baseUrl = resolveBaseUrl(opts.env);
  const tenant = resolveTenantContext(token);
  const backend = await probeBackend(baseUrl, token);

  const facts: DoctorFacts = {
    skills: bestSkillsFact(root),
    hasToken: Boolean(token),
    tenant:
      tenant.kind === 'ok'
        ? { ok: true, detail: `tenant=${tenant.ctx.tenantName ?? tenant.ctx.tenantId}` }
        : { ok: false, detail: tenant.reason },
    backend,
  };

  const report = evaluateDoctor(facts);

  if (opts.agentMode || opts.format === 'json') {
    console.log(JSON.stringify(report));
  } else {
    console.log(chalk.bold('aura doctor'));
    for (const c of report.checks) {
      const icon = c.ok ? chalk.green('✓') : chalk.red('✗');
      console.log(`  ${icon} ${c.name}: ${c.detail}`);
    }
    console.log(
      report.ok
        ? chalk.green('\nAll checks passed.')
        : chalk.yellow('\nSome checks need attention (see above).'),
    );
  }

  process.exit(report.ok ? 0 : 1);
}
