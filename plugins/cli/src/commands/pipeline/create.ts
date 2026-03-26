import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { readStdin } from './stdin.js';

interface CreateOptions {
  token?: string;
  env?: string;
  fromStdin?: boolean;
  dryRun?: boolean;
}

/**
 * aura create <entity> — Batch entity creation from stdin pipeline.
 *
 * Reads JSON array from stdin, creates each record via Dynamic CRUD.
 * Outputs created records as JSON to stdout.
 *
 * Examples:
 *   aura query crm_lead --filter "crm_lead_status=QUALIFIED" | aura create crm_opportunity
 *   echo '[{"crm_lead_code":"LD-001","crm_lead_company":"Test"}]' | aura create crm_lead
 *   aura query crm_lead | aura create crm_lead --dry-run
 */
export async function createCommand(entity: string, options: CreateOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const inputData = await readStdin();
  if (!inputData || inputData.length === 0) {
    console.error(chalk.red('No data from stdin. Pipe JSON data to this command.'));
    console.error(chalk.dim('  Example: aura query crm_lead | aura create crm_opportunity'));
    process.exit(EXIT.FAILURE);
  }

  if (options.dryRun) {
    console.error(chalk.yellow(`[dry-run] Would create ${inputData.length} ${entity} records`));
    console.log(JSON.stringify(inputData));
    return;
  }

  console.error(chalk.dim(`Creating ${inputData.length} ${entity} records...`));

  const results: any[] = [];
  let successCount = 0;
  let errorCount = 0;

  for (const record of inputData) {
    const resp = await client.post(`/api/dynamic/${entity}/create`, record);
    if (resp.ok) {
      successCount++;
      results.push(resp.data);
    } else {
      errorCount++;
      console.error(chalk.red(`  Failed: ${resp.message}`));
      results.push({ error: resp.message, input: record });
    }
  }

  console.error(chalk.dim(`Done: ${successCount} created, ${errorCount} failed`));
  console.log(JSON.stringify(results));
}
