import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const AR_COLUMNS: ColumnDef[] = [
  { key: 'fin_art_invoice_no', header: 'INVOICE NO' },
  { key: 'fin_art_customer_id', header: 'customer' },
  { key: 'fin_art_amount_base', header: 'amount' },
  { key: 'fin_art_balance_base', header: 'balance' },
  {
    key: 'fin_art_status', header: 'status',
    color: (v) => {
      if (v === 'paid') return chalk.green(v);
      if (v === 'overdue') return chalk.red(v);
      if (v === 'partial') return chalk.yellow(v);
      return chalk.dim(v);
    },
  },
  { key: 'fin_art_due_date', header: 'DUE DATE' },
];

const AP_COLUMNS: ColumnDef[] = [
  { key: 'fin_apt_invoice_no', header: 'INVOICE NO' },
  { key: 'fin_apt_supplier_id', header: 'supplier' },
  { key: 'fin_apt_amount_base', header: 'amount' },
  { key: 'fin_apt_balance_base', header: 'balance' },
  {
    key: 'fin_apt_status', header: 'status',
    color: (v) => {
      if (v === 'paid') return chalk.green(v);
      if (v === 'overdue') return chalk.red(v);
      if (v === 'partial') return chalk.yellow(v);
      return chalk.dim(v);
    },
  },
  { key: 'fin_apt_due_date', header: 'DUE DATE' },
];

export interface InvoiceOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  direction?: string;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function financeInvoicesCommand(options: InvoiceOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const direction = (options.direction || 'ar').toLowerCase();
  const isAR = direction !== 'ap';
  const pageKey = isAR ? 'fin_ar_transaction' : 'fin_ap_transaction';
  const statusField = isAR ? 'fin_art_status' : 'fin_apt_status';
  const columns = isAR ? AR_COLUMNS : AP_COLUMNS;
  const label = isAR ? 'AR invoices' : 'AP invoices';

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: statusField, operator: 'EQ', value: options.status.toUpperCase() });
  }

  const records = await queryDynamicList(client, pageKey, {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} ${label}`));
    console.log();
  }

  printOutput(records, columns, opts);
}
