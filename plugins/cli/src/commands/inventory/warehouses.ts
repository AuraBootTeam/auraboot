import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'inv_warehouse_code', header: 'code' },
  { key: 'inv_warehouse_name', header: 'name' },
  { key: 'inv_warehouse_type', header: 'type' },
  {
    key: 'inv_warehouse_status', header: 'status',
    color: (v) => {
      if (v === 'enabled') return chalk.green(v);
      if (v === 'disabled') return chalk.red(v);
      return chalk.dim(v);
    },
  },
  { key: 'inv_warehouse_address', header: 'address' },
];

export interface WarehouseOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function inventoryWarehousesCommand(options: WarehouseOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'inv_warehouse_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'inv_warehouse', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} warehouses`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
