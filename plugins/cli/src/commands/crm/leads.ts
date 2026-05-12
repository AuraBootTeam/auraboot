import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'crm_lead_contact_name', header: 'name' },
  { key: 'crm_lead_company', header: 'company' },
  {
    key: 'crm_lead_status', header: 'status',
    color: (v) => {
      if (v === 'qualified') return chalk.green(v);
      if (v === 'converted') return chalk.cyan(v);
      if (v === 'lost') return chalk.red(v);
      return chalk.yellow(v);
    },
  },
  { key: 'crm_lead_source', header: 'source' },
  { key: 'crm_lead_score', header: 'score' },
  { key: 'crm_lead_contact_email', header: 'email' },
];

interface LeadOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  source?: string;
  keyword?: string;
  limit?: string;
}

export async function crmLeadsCommand(options: LeadOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'crm_lead_status', operator: 'EQ', value: options.status.toLowerCase() });
  }
  if (options.source) {
    filters.push({ fieldName: 'crm_lead_source', operator: 'EQ', value: options.source.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'crm_lead', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} leads`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
