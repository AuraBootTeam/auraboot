import { describe, expect, it } from 'vitest';
import { resolveCommandErrorMessage } from '../commandResponseErrors';

describe('resolveCommandErrorMessage — string context reason key', () => {
  it('translates a handler reason key after removing the platform wrapper', () => {
    expect(resolveCommandErrorMessage({code:'35000', context:{
      detail:'Plugin handler execution failed: qc.error.rework_source_pair_required',
    }}, 'qc:create_rework_order', key => key === 'qc.error.rework_source_pair_required'
      ? '请同时选择来源类型和来源单据，或同时留空' : key, 'zh-CN'))
      .toBe('请同时选择来源类型和来源单据，或同时留空');
  });

  it('does not expose an untranslated dotted handler error key', () => {
    expect(resolveCommandErrorMessage({code:'35000', context:{
      detail:'Plugin handler execution failed: qc.error.unavailable_translation',
    }}, 'qc:create_rework_order', key => key, 'zh-CN'))
      .toBe('操作未完成，请检查输入后重试。');
  });
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

describe('warehouse shortage feedback', () => {
  it('localizes the real plugin rejection and removes technical prefix and identifiers', () => {
    expect(resolveCommandErrorMessage({ context: { detail:
      'Plugin handler execution failed: Insufficient stock for product [01SECRET-PID] at the line location: required 1000000.00, available 0.00',
    } }, 'inv:confirm_other_out', undefined, 'zh-CN')).toBe(
      '所选库位库存不足：需要 1000000.00，可用 0.00。请调整数量或选择其他库位。');
  });
  it('preserves the real quantities when translating through the catalog', () => {
    let params: Record<string, string> | undefined;
    const result = resolveCommandErrorMessage({ context: { detail:
      'Insufficient stock for product [Bearing] in source warehouse: required 5.00, available 2.00',
    } }, 'inv:confirm_stock_transfer', (_key, values) => { params = values; return 'Localized shortage'; }, 'en-US');
    expect(result).toBe('Localized shortage');
    expect(params).toEqual({ required: '5.00', available: '2.00' });
  });
});
