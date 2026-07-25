import { describe, it, expect } from 'vitest';
import { describeCliError } from '../src/cli-error.js';
import { QueryFailedError } from '../src/client/dynamic-query.js';
import { EXIT } from '../src/client/api-client.js';

describe('describeCliError', () => {
  // Query helpers used to print + process.exit themselves. Now they throw, so
  // the CLI entry point owns the presentation — an expected backend refusal
  // must still read as a one-line message, not a Node stack trace.
  it('renders an expected query failure as a bare message', () => {
    const result = describeCliError(new QueryFailedError('Query failed: Model not found'));

    expect(result.exitCode).toBe(EXIT.FAILURE);
    expect(result.message).toContain('Query failed: Model not found');
    expect(result.message).not.toMatch(/\n\s+at /);
  });

  it('keeps the stack for unexpected errors so bugs stay debuggable', () => {
    const bug = new TypeError("Cannot read properties of undefined (reading 'x')");

    const result = describeCliError(bug);

    expect(result.exitCode).toBe(EXIT.FAILURE);
    expect(result.message).toContain('Cannot read properties of undefined');
    expect(result.message).toMatch(/\n\s+at /);
  });

  it('handles non-Error throws without crashing the handler', () => {
    const result = describeCliError('something odd');

    expect(result.exitCode).toBe(EXIT.FAILURE);
    expect(result.message).toContain('something odd');
  });
});
