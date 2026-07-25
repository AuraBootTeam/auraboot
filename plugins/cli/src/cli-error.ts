import chalk from 'chalk';
import { EXIT } from './client/api-client.js';
import { QueryFailedError } from './client/dynamic-query.js';

/**
 * Errors the CLI raises on purpose to report a refused operation. These carry a
 * message written for a human, so printing a stack trace alongside it would be
 * noise.
 */
const EXPECTED_ERRORS = [QueryFailedError];

export interface CliErrorReport {
  message: string;
  exitCode: number;
}

/**
 * Turn a thrown value into what the CLI should print and exit with.
 *
 * <p>Split out from the entry point so it can be tested directly: the query
 * helpers used to print and `process.exit` inline, which is why a single failed
 * lookup could kill the long-lived `aura mcp serve` process. They now throw, and
 * presentation lives here.
 */
export function describeCliError(err: unknown): CliErrorReport {
  if (EXPECTED_ERRORS.some((type) => err instanceof type)) {
    return { message: (err as Error).message, exitCode: EXIT.FAILURE };
  }

  if (err instanceof Error) {
    // Unexpected — keep the stack so the bug stays diagnosable.
    return { message: err.stack ?? err.message, exitCode: EXIT.FAILURE };
  }

  return { message: String(err), exitCode: EXIT.FAILURE };
}

/**
 * Print a thrown value and terminate. Installed as the CLI's last-resort
 * handler; MCP tool handlers catch their own errors long before this.
 */
export function handleCliError(err: unknown): never {
  const { message, exitCode } = describeCliError(err);
  console.error(chalk.red(message));
  process.exit(exitCode);
}
