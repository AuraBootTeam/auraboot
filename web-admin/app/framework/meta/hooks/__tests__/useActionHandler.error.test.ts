import { describe, expect, it } from 'vitest';

import { resolveCommandErrorMessage } from '../useActionHandler';

describe('resolveCommandErrorMessage', () => {
  it('maps a stable backend timeout code through i18n instead of displaying the server message', () => {
    expect(
      resolveCommandErrorMessage(
        {
          code: 'BACKEND_REQUEST_TIMEOUT',
          message: 'The backend request timed out.',
        },
        'bom:start_conversion',
        (key) =>
          key === 'common.error.backendRequestTimeout' ? '后端请求超时，操作可能仍在处理中' : key,
      ),
    ).toBe('后端请求超时，操作可能仍在处理中');
  });

  it('keeps the actionable business reason and removes plugin transport wording', () => {
    expect(
      resolveCommandErrorMessage(
        {
          context: {
            detail:
              'Plugin handler execution failed: 量产基线校验未通过：制造定义包尚未达到量产就绪',
          },
        },
        'mfg_work_order_pcba_execution:release',
      ),
    ).toBe('量产基线校验未通过：制造定义包尚未达到量产就绪');
  });

  it('maps platform CAS version conflicts to customer-safe guidance', () => {
    expect(
      resolveCommandErrorMessage(
        {
          context: {
            errorCode: 'CAS_VERSION_CONFLICT',
            detail: 'Command target version conflict (expected 7, current 8)',
          },
        },
        'inv:update_material_return',
      ),
    ).toBe(
      'This record was updated by someone else. Refresh to review the latest data before saving.',
    );
  });

  it('maps platform request intent conflicts to customer-safe guidance', () => {
    expect(
      resolveCommandErrorMessage(
        {
          context: {
            errorCode: 'REQUEST_INTENT_CONFLICT',
            detail: 'Idempotency key was already used with a different request',
          },
        },
        'inv:submit_material_return',
      ),
    ).toBe(
      'This request does not match the original request. Refresh and start the operation again.',
    );
  });

  it('does not rewrite ordinary business validation messages', () => {
    expect(
      resolveCommandErrorMessage(
        { context: { detail: '拣货数量不能超过待拣数量' } },
        'inv:record_issue_pick',
      ),
    ).toBe('拣货数量不能超过待拣数量');
  });
});
