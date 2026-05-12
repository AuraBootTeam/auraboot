import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'org_emp_name', header: 'name' },
  { key: 'org_emp_code', header: 'code' },
  {
    key: 'org_emp_status', header: 'status',
    color: (v) => {
      if (v === 'active') return chalk.green(v);
      if (v === 'probation') return chalk.yellow(v);
      if (v === 'resigned') return chalk.red(v);
      return chalk.dim(v);
    },
  },
  { key: 'org_emp_email', header: 'email' },
  { key: 'org_emp_phone', header: 'phone' },
  { key: 'org_emp_hire_date', header: 'HIRE DATE' },
];

interface EmployeeOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function hrEmployeesCommand(options: EmployeeOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'org_emp_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'org_employee', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} employees`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
