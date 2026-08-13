import { describe, expect, it } from 'vitest';

import { resolveCommandErrorMessage } from '../useActionHandler';

describe('resolveCommandErrorMessage', () => {
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

  it('does not rewrite ordinary business validation messages', () => {
    expect(
      resolveCommandErrorMessage(
        { context: { detail: '拣货数量不能超过待拣数量' } },
        'inv:record_issue_pick',
      ),
    ).toBe('拣货数量不能超过待拣数量');
  });
});
