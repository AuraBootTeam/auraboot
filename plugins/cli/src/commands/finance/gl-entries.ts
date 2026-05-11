import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'fin_je_entry_no', header: 'ENTRY NO' },
  { key: 'fin_je_entry_date', header: 'date' },
  { key: 'fin_je_source_type', header: 'source' },
  { key: 'fin_je_total_debit_base', header: 'debit' },
  { key: 'fin_je_total_credit_base', header: 'credit' },
  {
    key: 'fin_je_status', header: 'status',
    color: (v) => {
      if (v === 'posted') return chalk.green(v);
      if (v === 'draft') return chalk.dim(v);
      if (v === 'void') return chalk.red(v);
      return chalk.yellow(v);
    },
  },
  { key: 'fin_je_memo', header: 'memo' },
];

export interface GlEntryOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function financeGlEntriesCommand(options: GlEntryOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'fin_je_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'fin_journal_entry', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'fin_je_entry_date',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} journal entries`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
