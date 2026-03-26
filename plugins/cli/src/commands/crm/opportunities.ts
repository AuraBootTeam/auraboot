import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'crm_opp_name', header: 'name' },
  {
    key: 'crm_opp_stage', header: 'stage',
    color: (v) => {
      if (v === 'closed_won') return chalk.green(v);
      if (v === 'closed_lost') return chalk.red(v);
      if (v === 'negotiation') return chalk.cyan(v);
      return chalk.yellow(v);
    },
  },
  { key: 'crm_opp_expected_amount', header: 'amount' },
  { key: 'crm_opp_probability', header: 'PROB%' },
  { key: 'crm_opp_owner', header: 'owner' },
  { key: 'crm_opp_expected_close_date', header: 'CLOSE DATE' },
];

interface OppOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  stage?: string;
  keyword?: string;
  limit?: string;
}

export async function crmOpportunitiesCommand(options: OppOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.stage) {
    filters.push({ fieldName: 'crm_opp_stage', operator: 'EQ', value: options.stage.toUpperCase() });
  }

  const records = await queryDynamicList(client, 'crm_opportunity', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} opportunities`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
