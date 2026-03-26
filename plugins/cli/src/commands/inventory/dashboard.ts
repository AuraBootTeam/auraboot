import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryNamedQuery } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printDetail, printOutput, type ColumnDef } from '../../output/formatter.js';

const VALUE_BY_WH_COLUMNS: ColumnDef[] = [
  { key: 'warehouse_name', header: 'warehouse' },
  { key: 'sku_count', header: 'SKUs' },
  { key: 'total_value', header: 'value' },
];

interface DashboardOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

export async function inventoryDashboardCommand(options: DashboardOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const kpi = await queryNamedQuery(client, 'inv_dashboard_kpi');
  const valueByWh = await queryNamedQuery(client, 'inv_stock_value_by_warehouse');
  const docStats = await queryNamedQuery(client, 'inv_inbound_outbound_stats');

  if (opts.format === 'json' || opts.agentMode) {
    const data = { kpi: kpi[0] || {}, valueByWarehouse: valueByWh, documentStats: docStats[0] || {} };
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  // KPI summary
  if (kpi.length > 0) {
    console.log(chalk.bold('  Inventory Dashboard'));
    console.log();
    printDetail(kpi[0], opts);
    console.log();
  }

  // Document stats
  if (docStats.length > 0) {
    const s = docStats[0];
    console.log(chalk.bold('  Document Stats'));
    console.log();
    console.log(`  Inbound:  ${chalk.yellow(String(s.inbound_draft || 0))} draft  ${chalk.green(String(s.inbound_confirmed || 0))} confirmed  ${chalk.dim(String(s.inbound_total || 0) + ' total')}`);
    console.log(`  Outbound: ${chalk.yellow(String(s.outbound_draft || 0))} draft  ${chalk.green(String(s.outbound_confirmed || 0))} confirmed  ${chalk.dim(String(s.outbound_total || 0) + ' total')}`);
    console.log();
  }

  // Value by warehouse
  if (valueByWh.length > 0) {
    console.log(chalk.bold('  Stock Value by Warehouse'));
    console.log();
    printOutput(valueByWh, VALUE_BY_WH_COLUMNS, opts);
  }
}
