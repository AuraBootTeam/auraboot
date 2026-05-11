import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const PROJECT_COLUMNS: ColumnDef[] = [
  { key: 'pm_project_name', header: 'name' },
  { key: 'pm_project_code', header: 'code' },
  {
    key: 'pm_project_status', header: 'status',
    color: (v) => {
      if (v === 'active') return chalk.green(v);
      if (v === 'completed') return chalk.cyan(v);
      if (v === 'on_hold') return chalk.yellow(v);
      if (v === 'archived') return chalk.dim(v);
      return v;
    },
  },
  { key: 'pm_project_start_date', header: 'start' },
  { key: 'pm_project_end_date', header: 'end' },
  { key: 'pm_project_owner', header: 'owner' },
];

interface ProjectListOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function projectListCommand(options: ProjectListOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'pm_project_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'pm_project', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} projects`));
    console.log();
  }

  printOutput(records, PROJECT_COLUMNS, opts);
}
