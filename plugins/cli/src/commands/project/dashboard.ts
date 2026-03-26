import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryNamedQuery } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printDetail } from '../../output/formatter.js';

interface DashboardOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

export async function projectDashboardCommand(options: DashboardOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const kpi = await queryNamedQuery(client, 'pm_dashboard_kpi');
  const statusDist = await queryNamedQuery(client, 'pm_project_status_distribution');

  if (opts.format === 'json' || opts.agentMode) {
    const data = { kpi: kpi[0] || {}, statusDistribution: statusDist };
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  if (kpi.length > 0) {
    console.log(chalk.bold('  Project Dashboard'));
    console.log();
    printDetail(kpi[0], opts);
    console.log();
  }

  if (statusDist.length > 0) {
    console.log(chalk.bold('  Projects by Status'));
    console.log();
    for (const row of statusDist) {
      const status = String(row.label || row.status || row.pm_project_status || '');
      const count = Number(row.value || row.count || row.total || 0);
      const bar = '█'.repeat(Math.min(count, 30));
      console.log(`  ${status.padEnd(14)} ${chalk.cyan(bar)} ${count}`);
    }
  }
}
