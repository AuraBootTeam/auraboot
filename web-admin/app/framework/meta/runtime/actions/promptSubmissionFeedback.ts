import { CommandExecutionError } from '~/framework/meta/utils/commandExecutionError';
import { resolveCommandErrorMessage } from '~/framework/meta/utils/commandResponseErrors';

export interface PromptSubmissionState {
  phase: 'idle' | 'submitting' | 'failed' | 'accepted' | 'succeeded' | 'disposed';
  message?: string;
  httpStatus?: number;
  code?: string;
  errorCode?: string;
}
export interface PromptSubmissionFeedback {
  readonly requestId: number | undefined;
  readonly refresh: (() => void | Promise<void>) | undefined;
  attach(requestId: number, signals: AbortSignal[], onDispose: () => void): void;
  submit(): boolean;
  getState(): Readonly<PromptSubmissionState>;
  subscribe(listener: (state: Readonly<PromptSubmissionState>) => void): () => void;
  completeResult(result?: unknown, terminalConfirmed?: boolean): void;
  fail(error: unknown, messageOverride?: string): void;
  finalize(): void;
  dispose(): void;
}

/** Detect an accepted task receipt only; this helper never polls or dispatches. */
export function hasAsyncCommandReceipt(value: unknown): boolean {
  const seen = new Set<object>();
  let current = value;
  for (let depth = 0; depth < 8 && current && typeof current === 'object'; depth++) {
    if (seen.has(current)) return false;
    seen.add(current);
    const body = current as Record<string, unknown>;
    if (body.async === true && typeof body.taskCode === 'string' && body.taskCode.trim())
      return true;
    current = body.data;
  }
  return false;
}

/** Only the existing Result/platform command wrappers are unwrapped. */
export function commandBusinessRejectionError(value: unknown): CommandExecutionError | undefined {
  const seen = new Set<object>();
  let current = value;
  for (let depth = 0; depth < 8 && current && typeof current === 'object'; depth++) {
    if (seen.has(current)) return undefined;
    seen.add(current);
    const body = current as Record<string, unknown>;
    if (body.success === false || body.applied === false) {
      const reason =
        typeof body.message === 'string' && body.message.trim()
          ? body.message
          : typeof body.reason === 'string' && body.reason.trim()
            ? body.reason
            : 'The command was not applied';
      return new CommandExecutionError(reason, value);
    }
    if (
      !('data' in body) ||
      !['code', 'commandCode', 'phaseReached', 'executionTimeMs'].some((key) => key in body)
    )
      return undefined;
    current = body.data;
  }
  return undefined;
}

/** A local, one-attempt UI feedback channel. It contains no command/payload logic. */
export function createPromptSubmissionFeedback(
  options: {
    t?: (key: string) => string;
    locale?: string;
    refresh?: () => void | Promise<void>;
  } = {},
): PromptSubmissionFeedback {
  let requestId: number | undefined;
  let attached = false,
    disposed = false;
  let state: PromptSubmissionState = { phase: 'idle' };
  let cleanup = () => {};
  let onDispose = () => {};
  const listeners = new Set<(state: Readonly<PromptSubmissionState>) => void>();
  const publish = (next: PromptSubmissionState) => {
    state = next;
    listeners.forEach((listener) => listener(state));
  };
  const fallback = (zh: string, en: string) =>
    options.locale?.toLowerCase().startsWith('zh') ? zh : en;
  const channel: PromptSubmissionFeedback = {
    get requestId() {
      return requestId;
    },
    refresh: options.refresh,
    attach(id, signals, close) {
      if (attached || disposed) throw new Error('Prompt submission feedback is single use');
      attached = true;
      requestId = id;
      onDispose = close;
      const abort = () => channel.dispose();
      signals.forEach((signal) => signal.addEventListener('abort', abort, { once: true }));
      cleanup = () => signals.forEach((signal) => signal.removeEventListener('abort', abort));
      if (signals.some((signal) => signal.aborted)) channel.dispose();
    },
    submit() {
      if (!attached || disposed || state.phase !== 'idle') return false;
      publish({ phase: 'submitting' });
      return true;
    },
    getState: () => state,
    subscribe(listener) {
      if (!disposed) {
        listeners.add(listener);
        listener(state);
      }
      return () => {
        listeners.delete(listener);
      };
    },
    completeResult(result, terminalConfirmed = false) {
      if (!attached || disposed || state.phase !== 'submitting') return;
      const value = result && typeof result === 'object' ? (result as Record<string, unknown>) : {};
      if (value.__asyncFailed) {
        channel.fail(
          new Error(
            typeof value.errorMessage === 'string' ? value.errorMessage : 'Async task failed',
          ),
        );
        return;
      }
      const rejection = commandBusinessRejectionError(result);
      if (rejection) {
        channel.fail(rejection);
        return;
      }
      if (!terminalConfirmed && hasAsyncCommandReceipt(result)) {
        publish({
          phase: 'accepted',
          message: fallback(
            '命令已受理，尚未确认执行结果。请关闭后从原页面刷新核对；输入仍保留。',
            'The command was accepted; its final result is not confirmed. Close and refresh from the original page to review. Your inputs are retained.',
          ),
        });
      } else publish({ phase: 'succeeded' });
    },
    fail(error, messageOverride) {
      if (!attached || disposed || state.phase !== 'submitting') return;
      const structured = error instanceof CommandExecutionError ? error : undefined;
      const message =
        messageOverride ||
        resolveCommandErrorMessage(
          { context: { detail: error instanceof Error ? error.message : undefined } },
          '',
          options.t,
          options.locale,
          fallback(
            '操作结果尚未确认。输入已保留，请先刷新核对。',
            'The operation result is not confirmed. Your inputs are retained; refresh to review.',
          ),
        );
      publish({
        phase: 'failed',
        message,
        ...(structured
          ? {
              httpStatus: structured.httpStatus,
              code: structured.code,
              errorCode: structured.context.errorCode,
            }
          : {}),
      });
    },
    finalize() {
      if (state.phase === 'submitting')
        channel.fail(
          new Error(
            fallback(
              '操作尚未确认结果。输入已保留，请先核对记录状态。',
              'The operation result is not confirmed. Your inputs are retained; review the record status.',
            ),
          ),
        );
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      cleanup();
      state = { phase: 'disposed' };
      listeners.clear();
      if (attached) onDispose();
    },
  };
  return channel;
}
