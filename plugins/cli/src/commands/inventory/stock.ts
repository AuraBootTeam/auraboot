import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, queryNamedQuery, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const BALANCE_COLUMNS: ColumnDef[] = [
  { key: 'inv_bal_product_name', header: 'product' },
  { key: 'inv_bal_spec', header: 'spec' },
  { key: 'inv_bal_unit', header: 'unit' },
  { key: 'inv_bal_qty', header: 'qty' },
  { key: 'inv_bal_available_qty', header: 'available' },
  { key: 'inv_bal_reserved_qty', header: 'reserved' },
  { key: 'inv_bal_safety_stock', header: 'safety' },
  { key: 'inv_bal_avg_cost', header: 'AVG COST' },
  { key: 'inv_bal_amount', header: 'amount' },
];

const LOW_STOCK_COLUMNS: ColumnDef[] = [
  { key: 'product_name', header: 'product' },
  { key: 'spec', header: 'spec' },
  { key: 'unit', header: 'unit' },
  {
    key: 'current_qty', header: 'current',
    color: (v) => chalk.red(v),
  },
  { key: 'safety_stock', header: 'safety' },
  {
    key: 'shortage', header: 'shortage',
    color: (v) => chalk.red(v),
  },
];

export interface StockOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  warehouse?: string;
  keyword?: string;
  limit?: string;
}

export async function inventoryStockCommand(options: StockOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.warehouse) {
    filters.push({ fieldName: 'inv_bal_warehouse_id', operator: 'EQ', value: options.warehouse });
  }

  const records = await queryDynamicList(client, 'inv_balance', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'inv_bal_product_name',
    sortOrder: 'asc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} stock items`));
    console.log();
  }

  printOutput(records, BALANCE_COLUMNS, opts);
}

export interface LowStockOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  limit?: string;
}

export async function inventoryLowStockCommand(options: LowStockOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const records = await queryNamedQuery(client, 'inv_low_stock_alerts', {
    maxItems: options.limit || '50',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} low-stock alerts`));
    console.log();
  }

  if (records.length === 0 && !opts.agentMode) {
    console.log(chalk.green('  No low-stock alerts. All items above safety stock.'));
    return;
  }

  printOutput(records, LOW_STOCK_COLUMNS, opts);
}
