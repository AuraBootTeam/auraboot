import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'org_dept_name', header: 'name' },
  { key: 'org_dept_code', header: 'code' },
  {
    key: 'org_dept_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : chalk.dim(v),
  },
  { key: 'org_dept_order', header: 'order' },
];

interface DepartmentOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function hrDepartmentsCommand(options: DepartmentOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'org_dept_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'org_department', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'org_dept_order',
    sortOrder: 'asc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} departments`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
