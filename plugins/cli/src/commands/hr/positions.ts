import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'org_pos_name', header: 'name' },
  { key: 'org_pos_code', header: 'code' },
  {
    key: 'org_pos_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : chalk.dim(v),
  },
  { key: 'org_pos_level', header: 'level' },
];

interface PositionOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function hrPositionsCommand(options: PositionOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'org_pos_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'org_position', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} positions`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
