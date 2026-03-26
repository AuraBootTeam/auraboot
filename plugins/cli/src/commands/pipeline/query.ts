import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { queryDynamicList, queryNamedQuery, type FilterItem } from '../../client/dynamic-query.js';

interface QueryOptions {
  token?: string;
  env?: string;
  filter?: string[];
  limit?: string;
  sort?: string;
  nq?: string;
}

/**
 * aura query <entity> — Structured data retrieval for pipeline composition.
 *
 * Always outputs JSON to stdout (pipeline-friendly).
 * Errors go to stderr.
 *
 * Examples:
 *   aura query crm_lead --filter "crm_lead_status=NEW"
 *   aura query crm_lead --filter "crm_lead_score>80" --sort crm_lead_score:desc
 *   aura query --nq crm_dashboard_kpi
 *   aura query crm_lead | aura analyze churn-risk
 */
export async function queryCommand(entity: string | undefined, options: QueryOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  try {
    let records: any[];

    // Named query mode
    if (options.nq) {
      records = await queryNamedQuery(client, options.nq, {
        maxItems: options.limit || '200',
      });
    } else if (!entity) {
      console.error(chalk.red('Entity code required. Usage: aura query <entity> or aura query --nq <code>'));
      process.exit(EXIT.FAILURE);
      return;
    } else {
      // Dynamic CRUD mode
      const filters = parseFilters(options.filter || []);

      const sortParts = options.sort?.split(':') || [];
      const sortField = sortParts[0];
      const sortOrder = (sortParts[1] as 'asc' | 'desc') || 'desc';

      records = await queryDynamicList(client, entity, {
        pageSize: Number(options.limit) || 50,
        filters,
        sortField,
        sortOrder,
      });
    }

    // Always output JSON for pipeline
    console.log(JSON.stringify(records));
  } catch (e) {
    console.error(chalk.red(`Query failed: ${(e as Error).message}`));
    process.exit(EXIT.FAILURE);
  }
}

/**
 * Parse filter strings like "field=value", "field>value", "field~value"
 */
function parseFilters(filterStrings: string[]): FilterItem[] {
  return filterStrings.map(f => {
    // Try operators in order of specificity
    for (const [op, apiOp] of [
      ['>=', 'gte'], ['<=', 'lte'], ['!=', 'neq'],
      ['>', 'GT'], ['<', 'LT'], ['~', 'like'], ['=', 'EQ'],
    ] as const) {
      const idx = f.indexOf(op);
      if (idx > 0) {
        return {
          fieldName: f.slice(0, idx),
          operator: apiOp as FilterItem['operator'],
          value: f.slice(idx + op.length),
        };
      }
    }
    // Default: treat as keyword
    return { fieldName: f, operator: 'like' as const, value: f };
  });
}
