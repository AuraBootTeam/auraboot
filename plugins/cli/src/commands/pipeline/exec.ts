import chalk from 'chalk';
import { readFileSync } from 'node:fs';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { readStdin } from './stdin.js';
import { resolveOutputOptions } from '../../output/formatter.js';

interface ExecOptions {
  token?: string;
  env?: string;
  set?: string[];
  target?: string;
  operation?: string;
  from?: string;
  stdin?: boolean;
  dryRun?: boolean;
  format?: string;
  agentMode?: boolean;
}

/**
 * aura exec <commandCode> — Execute a DSL Command.
 *
 * Examples:
 *   aura exec sc:create_showcase --set sc_name="Test" --set sc_code="SC-001" --set sc_quantity:int=100
 *   aura exec sc:activate_showcase --target 01ABCDEF
 *   aura exec sc:create_showcase --from seed-data.json
 *   echo '{"sc_name":"X","sc_code":"Y"}' | aura exec sc:create_showcase --stdin
 */
export async function execCommand(commandCode: string, options: ExecOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const outputOpts = resolveOutputOptions(options);

  // 1. Build payload(s) — async to support stdin
  let payloads: Record<string, unknown>[];
  try {
    payloads = await buildPayloads(options);
  } catch (e) {
    console.error(chalk.red(`Payload error: ${(e as Error).message}`));
    process.exit(EXIT.FAILURE);
  }

  // 2. Build request bodies
  const bodies = payloads.map((payload) => {
    const body: Record<string, unknown> = { payload };
    if (options.target) body.targetRecordId = options.target;
    if (options.operation) body.operationType = options.operation;
    return body;
  });

  // 3. Dry-run
  if (options.dryRun) {
    for (const body of bodies) {
      console.error(chalk.yellow(`[dry-run] POST /api/meta/commands/execute/${commandCode}`));
      console.log(JSON.stringify(body, null, 2));
    }
    return;
  }

  // 4. Execute
  if (bodies.length === 1) {
    await executeSingle(client, commandCode, bodies[0], outputOpts);
  } else {
    await executeBatch(client, commandCode, bodies, outputOpts);
  }
}

async function executeSingle(
  client: ApiClient,
  commandCode: string,
  body: Record<string, unknown>,
  outputOpts: { format: string; agentMode: boolean },
): Promise<void> {
  const resp = await client.post(`/api/meta/commands/execute/${commandCode}`, body);
  if (resp.ok) {
    const resultData = (resp.data as any)?.data ?? {};
    const recordId = resultData?.recordId ?? resultData?.pid ?? resultData?.id ?? '';
    if (outputOpts.format === 'json') {
      console.log(JSON.stringify(resp.data, null, outputOpts.agentMode ? 0 : 2));
    } else {
      console.log(chalk.green('✓'), `${commandCode} executed`);
      if (recordId) console.log(`  Record: ${recordId}`);
    }
  } else {
    if (outputOpts.format === 'json') {
      console.log(JSON.stringify(resp.data ?? { error: resp.message }));
    } else {
      console.error(chalk.red('✗'), `${commandCode} failed`);
      console.error(`  Error: ${resp.message}`);
    }
    process.exit(EXIT.FAILURE);
  }
}

async function executeBatch(
  client: ApiClient,
  commandCode: string,
  bodies: Record<string, unknown>[],
  outputOpts: { format: string; agentMode: boolean },
): Promise<void> {
  let successCount = 0;
  let errorCount = 0;
  const results: any[] = [];

  for (let i = 0; i < bodies.length; i++) {
    const resp = await client.post(`/api/meta/commands/execute/${commandCode}`, bodies[i]);
    if (resp.ok) {
      successCount++;
      const resultData = (resp.data as any)?.data ?? {};
      const recordId = resultData?.recordId ?? resultData?.pid ?? resultData?.id ?? '';
      if (outputOpts.format !== 'json') {
        console.log(`  [${i + 1}/${bodies.length}] ${chalk.green('✓')} Executed${recordId ? ` (${recordId})` : ''}`);
      }
      results.push(resp.data);
    } else {
      errorCount++;
      if (outputOpts.format !== 'json') {
        console.error(`  [${i + 1}/${bodies.length}] ${chalk.red('✗')} ${resp.message}`);
      }
      results.push({ error: resp.message, input: bodies[i].payload });
    }
  }

  if (outputOpts.format === 'json') {
    console.log(JSON.stringify(results, null, outputOpts.agentMode ? 0 : 2));
  } else {
    console.log(chalk.dim(`Done: ${successCount} succeeded, ${errorCount} failed`));
  }

  if (errorCount > 0) process.exit(EXIT.FAILURE);
}

/**
 * Build payload array from --set, --from, --stdin.
 * Priority: --from/--stdin as base, --set overrides on top.
 */
async function buildPayloads(options: ExecOptions): Promise<Record<string, unknown>[]> {
  let basePayloads: Record<string, unknown>[] | null = null;

  // Read from file
  if (options.from) {
    const content = readFileSync(options.from, 'utf-8');
    const parsed = JSON.parse(content);
    basePayloads = Array.isArray(parsed) ? parsed : [parsed];
  }

  // Read from stdin
  if (options.stdin && !options.from) {
    const stdinData = await readStdin();
    if (stdinData && stdinData.length > 0) {
      basePayloads = stdinData;
    }
  }

  // Parse --set flags
  const setOverrides = parseSetFlags(options.set || []);

  if (!basePayloads) {
    return [setOverrides]; // --set only, or empty payload for target-only commands
  }

  // Merge: base + set overrides
  return basePayloads.map((base) => ({ ...base, ...setOverrides }));
}

/**
 * Parse --set flag values.
 *
 *   key=value        → string
 *   key:int=100      → number (parseInt)
 *   key:float=99.99  → number (parseFloat)
 *   key:bool=true    → boolean
 *   key:json=[1,2]   → JSON.parse
 *   key:null=        → null
 */
function parseSetFlags(sets: string[]): Record<string, unknown> {
  const result: Record<string, unknown> = {};

  for (const expr of sets) {
    const eqIdx = expr.indexOf('=');
    if (eqIdx <= 0) {
      throw new Error(`Invalid --set format: "${expr}" (expected key=value)`);
    }

    const keyPart = expr.slice(0, eqIdx);
    const rawValue = expr.slice(eqIdx + 1);

    const colonIdx = keyPart.indexOf(':');
    let key: string;
    let typeName: string;

    if (colonIdx > 0) {
      key = keyPart.slice(0, colonIdx);
      typeName = keyPart.slice(colonIdx + 1);
    } else {
      key = keyPart;
      typeName = 'string';
    }

    result[key] = coerceValue(rawValue, typeName, key);
  }

  return result;
}

function coerceValue(rawValue: string, typeName: string, key: string): unknown {
  switch (typeName) {
    case 'string':
      return rawValue;
    case 'int':
    case 'integer': {
      const n = parseInt(rawValue, 10);
      if (isNaN(n)) throw new Error(`Invalid integer for "${key}": "${rawValue}"`);
      return n;
    }
    case 'float':
    case 'number':
    case 'decimal': {
      const n = parseFloat(rawValue);
      if (isNaN(n)) throw new Error(`Invalid number for "${key}": "${rawValue}"`);
      return n;
    }
    case 'bool':
    case 'boolean':
      return rawValue === 'true' || rawValue === '1' || rawValue === 'yes';
    case 'json':
      try {
        return JSON.parse(rawValue);
      } catch {
        throw new Error(`Invalid JSON for "${key}": "${rawValue}"`);
      }
    case 'null':
      return null;
    default:
      throw new Error(`Unknown type "${typeName}" for "${key}". Use: string, int, float, bool, json, null`);
  }
}
