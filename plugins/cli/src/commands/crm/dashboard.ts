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

export async function crmDashboardCommand(options: DashboardOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const kpi = await queryNamedQuery(client, 'crm_dashboard_kpi');
  const pipeline = await queryNamedQuery(client, 'crm_opportunity_pipeline_stats');

  if (opts.format === 'json' || opts.agentMode) {
    const data = { kpi: kpi[0] || {}, pipeline };
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  // KPI summary
  if (kpi.length > 0) {
    console.log(chalk.bold('  CRM Dashboard'));
    console.log();
    printDetail(kpi[0], opts);
    console.log();
  }

  // Pipeline
  if (pipeline.length > 0) {
    console.log(chalk.bold('  Pipeline by Stage'));
    console.log();
    for (const row of pipeline) {
      const stage = String(row.stage || row.crm_opp_stage || '');
      const count = Number(row.count || row.total || 0);
      const amount = Number(row.total_amount || row.amount || 0);
      const bar = '█'.repeat(Math.min(count, 30));
      console.log(`  ${stage.padEnd(16)} ${chalk.cyan(bar)} ${count} deals, ${chalk.green('$' + amount.toLocaleString())}`);
    }
  }
}
