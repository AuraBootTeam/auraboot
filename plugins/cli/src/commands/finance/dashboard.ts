import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryNamedQuery } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printDetail, printOutput, type ColumnDef } from '../../output/formatter.js';

interface DashboardOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const AGING_COLUMNS: ColumnDef[] = [
  { key: 'aging_bucket', header: 'bucket' },
  { key: 'total_balance', header: 'balance' },
  { key: 'count', header: 'count' },
];

export async function financeDashboardCommand(options: DashboardOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const kpi = await queryNamedQuery(client, 'fin_dashboard_kpi');
  const arAging = await queryNamedQuery(client, 'fin_ar_aging');
  const revenueKpi = await queryNamedQuery(client, 'fin_revenue_expense_kpi');

  if (opts.format === 'json' || opts.agentMode) {
    const data = {
      kpi: kpi[0] || {},
      revenueKpi: revenueKpi[0] || {},
      arAging,
    };
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  // KPI summary
  if (kpi.length > 0) {
    console.log(chalk.bold('  Finance Dashboard'));
    console.log();
    printDetail(kpi[0], opts);
    console.log();
  }

  // Revenue & Expense KPI
  if (revenueKpi.length > 0) {
    console.log(chalk.bold('  Revenue & Expense'));
    console.log();
    const r = revenueKpi[0];
    const revenue = Number(r.total_revenue || 0);
    const expenses = Number(r.total_expenses || 0);
    const netIncome = Number(r.net_income || 0);
    const cashFlow = Number(r.net_cash_flow || 0);
    console.log(`  Revenue:    ${chalk.green('$' + revenue.toLocaleString())}`);
    console.log(`  Expenses:   ${chalk.red('$' + expenses.toLocaleString())}`);
    console.log(`  Net Income: ${netIncome >= 0 ? chalk.green('$' + netIncome.toLocaleString()) : chalk.red('-$' + Math.abs(netIncome).toLocaleString())}`);
    console.log(`  Cash Flow:  ${cashFlow >= 0 ? chalk.green('$' + cashFlow.toLocaleString()) : chalk.red('-$' + Math.abs(cashFlow).toLocaleString())}`);
    console.log();
  }

  // AR Aging
  if (arAging.length > 0) {
    console.log(chalk.bold('  AR Aging'));
    console.log();
    for (const row of arAging) {
      const bucket = String(row.aging_bucket || '');
      const balance = Number(row.total_balance || 0);
      const count = Number(row.count || 0);
      const bar = '█'.repeat(Math.min(count, 30));
      console.log(`  ${bucket.padEnd(8)} ${chalk.cyan(bar)} ${count} invoices, ${chalk.green('$' + balance.toLocaleString())}`);
    }
  }
}
