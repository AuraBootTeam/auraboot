import { describe, expect, it } from 'vitest';
import { resolveCommandErrorMessage } from '../commandResponseErrors';

describe('resolveCommandErrorMessage — string context reason key', () => {
  it('translates a bare context reason key through the locale catalog', () => {
    const result = resolveCommandErrorMessage(
      {
        code: '40000',
        message: 'Business error',
        context: 'annual_balance_not_found',
      },
      'wd:create_and_submit_leave_request',
      (key) => (key === 'annual_balance_not_found' ? '未找到该员工的年假余额记录' : key),
    );
    expect(result).toBe('未找到该员工的年假余额记录');
  });

  it('returns the raw context key when no catalog entry exists, never the generic envelope', () => {
    const result = resolveCommandErrorMessage(
      { code: '40000', message: 'Business error', context: 'unknown_reason_key' },
      'wd:some_command',
    );
    expect(result).toBe('unknown_reason_key');
  });

  it('keeps the legacy {messageKey, detail} object shape working', () => {
    const result = resolveCommandErrorMessage(
      {
        code: '40000',
        message: 'Business error',
        context: { messageKey: 'annual_balance_not_found', detail: '年假余额未登记' },
      },
      'wd:create_and_submit_leave_request',
    );
    expect(result).toBe('年假余额未登记');
  });
});
