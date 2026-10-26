import { describe, expect, it, vi } from 'vitest';
import { CommandExecutionError } from '~/framework/meta/utils/commandExecutionError';
import {
  createPromptSubmissionFeedback,
  hasAsyncCommandReceipt,
} from '../promptSubmissionFeedback';

describe('single-attempt command prompt feedback', () => {
  it('retains the actual conflict classification and never rearms a resolved attempt', () => {
    const feedback = createPromptSubmissionFeedback({ locale: 'en' });
    feedback.attach(17, [], vi.fn());
    expect(feedback.submit()).toBe(true);
    feedback.fail(
      new CommandExecutionError('Refresh to review', {
        httpStatus: 409,
        code: '41001',
        context: { errorCode: 'CAS_VERSION_CONFLICT' },
      }),
    );
    expect(feedback.getState()).toMatchObject({
      phase: 'failed',
      httpStatus: 409,
      code: '41001',
      errorCode: 'CAS_VERSION_CONFLICT',
    });
    expect(feedback.submit()).toBe(false);
    feedback.finalize();
    expect(feedback.getState().phase).toBe('failed');
  });
  it.each([
    { success: false, reason: 'Not applied' },
    { applied: false, reason: 'Not applied' },
    {
      code: '0',
      data: {
        commandCode: 'case:update',
        phaseReached: 'completed',
        data: { success: false, applied: false, reason: 'Not applied' },
      },
    },
  ])('keeps explicit flat/platform-wrapped business rejection as failed: %j', (result) => {
    const feedback = createPromptSubmissionFeedback();
    feedback.attach(30, [], vi.fn());
    feedback.submit();
    feedback.completeResult(result, true);
    expect(feedback.getState()).toMatchObject({ phase: 'failed', message: 'Not applied' });
    expect(feedback.submit()).toBe(false);
  });
  it('does not guess a business rejection from an unrelated nested data field', () => {
    const feedback = createPromptSubmissionFeedback();
    feedback.attach(31, [], vi.fn());
    feedback.submit();
    feedback.completeResult({ businessData: { applied: false }, reason: 'Informational' }, true);
    expect(feedback.getState().phase).toBe('succeeded');
  });
  it('cannot turn an authoritative success into failure after a later read fails', () => {
    const feedback = createPromptSubmissionFeedback();
    feedback.attach(18, [], vi.fn());
    feedback.submit();
    feedback.completeResult({}, true);
    feedback.fail(new Error('Read failed'));
    feedback.finalize();
    expect(feedback.getState().phase).toBe('succeeded');
  });
  it('ends the spinner without calling an accepted task receipt a completed write', () => {
    const feedback = createPromptSubmissionFeedback({ locale: 'en' });
    feedback.attach(19, [], vi.fn());
    feedback.submit();
    feedback.completeResult({
      phaseReached: 'completed',
      data: { async: true, taskCode: 'real-receipt' },
    });
    expect(feedback.getState().phase).toBe('accepted');
    expect(feedback.submit()).toBe(false);
    feedback.finalize();
    expect(feedback.getState().phase).toBe('accepted');
    expect(hasAsyncCommandReceipt({ data: { async: true, taskCode: 'real-receipt' } })).toBe(true);
  });
  it('terminates unacknowledged returns and ignores every late callback after disposal', () => {
    const close = vi.fn(),
      signal = new AbortController(),
      listener = vi.fn();
    const feedback = createPromptSubmissionFeedback();
    feedback.attach(20, [signal.signal], close);
    feedback.subscribe(listener);
    feedback.submit();
    feedback.finalize();
    expect(feedback.getState().phase).toBe('failed');
    signal.abort();
    const count = listener.mock.calls.length;
    feedback.completeResult({}, true);
    feedback.fail(new Error('Late A'));
    expect(feedback.getState().phase).toBe('disposed');
    expect(close).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledTimes(count);
  });
  it('leaves legacy, unbound collectors untouched', () => {
    const feedback = createPromptSubmissionFeedback();
    feedback.finalize();
    feedback.completeResult({});
    expect(feedback.getState()).toEqual({ phase: 'idle' });
  });
});
