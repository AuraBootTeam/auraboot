import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, queryNamedQuery, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const TASK_COLUMNS: ColumnDef[] = [
  { key: 'pm_task_title', header: 'title' },
  {
    key: 'pm_task_status', header: 'status',
    color: (v) => {
      if (v === 'done') return chalk.green(v);
      if (v === 'in_progress') return chalk.cyan(v);
      if (v === 'blocked') return chalk.red(v);
      return chalk.yellow(v);
    },
  },
  { key: 'pm_task_priority', header: 'priority' },
  { key: 'pm_task_type', header: 'type' },
  { key: 'pm_task_assignee', header: 'assignee' },
  { key: 'pm_task_due_date', header: 'due' },
];

interface TaskOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  assignee?: string;
  mine?: boolean;
  keyword?: string;
  limit?: string;
}

export async function projectTasksCommand(options: TaskOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  // --mine shortcut uses NamedQuery for current user's tasks
  if (options.mine) {
    const records = await queryNamedQuery(client, 'pm_my_tasks', {
      maxItems: options.limit || '50',
    });

    if (!opts.agentMode && records.length > 0) {
      console.log(chalk.dim(`  ${records.length} tasks assigned to you`));
      console.log();
    }
    printOutput(records, TASK_COLUMNS, opts);
    return;
  }

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'pm_task_status', operator: 'EQ', value: options.status.toUpperCase() });
  }
  if (options.assignee) {
    filters.push({ fieldName: 'pm_task_assignee', operator: 'like', value: options.assignee });
  }

  const records = await queryDynamicList(client, 'pm_task', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} tasks`));
    console.log();
  }

  printOutput(records, TASK_COLUMNS, opts);
}
