import { resolveCommandErrorMessage } from './commandResponseErrors';

/** Preserve classification for UI recovery without changing request dispatch. */
export class CommandExecutionError extends Error {
  readonly httpStatus?: number;
  readonly code?: string;
  readonly context: { errorCode?: string };
  constructor(message: string, result: unknown) {
    super(message);
    this.name = 'CommandExecutionError';
    const body = result && typeof result === 'object' ? (result as Record<string, unknown>) : {};
    const context =
      body.context && typeof body.context === 'object' && !Array.isArray(body.context)
        ? (body.context as Record<string, unknown>)
        : {};
    this.httpStatus = typeof body.httpStatus === 'number' ? body.httpStatus : undefined;
    this.code =
      typeof body.code === 'string' || typeof body.code === 'number'
        ? String(body.code)
        : undefined;
    this.context = {
      ...(typeof context.errorCode === 'string' ? { errorCode: context.errorCode } : {}),
    };
  }
}
export function commandExecutionError(
  result: unknown,
  command: string,
  t?: (key: string) => string,
  locale?: string,
): CommandExecutionError {
  return new CommandExecutionError(resolveCommandErrorMessage(result, command, t, locale), result);
}
