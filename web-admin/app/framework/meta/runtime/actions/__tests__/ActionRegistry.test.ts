import { describe, expect, it, vi } from 'vitest';

import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';

describe('ActionRegistry record navigation', () => {
  it('prefers pid over id for edit routes', async () => {
    const navigate = vi.fn();

    await actionRegistry.execute('edit', {
      navigate,
      tableName: 'thr_leave_request',
      record: { id: 5, pid: '01HPID123' },
    });

    expect(navigate).toHaveBeenCalledWith('/p/thr_leave_request/edit/01HPID123');
  });

  it('prefers pid over id for detail routes', async () => {
    const navigate = vi.fn();

    await actionRegistry.execute('view', {
      navigate,
      tableName: 'thr_leave_request',
      record: { id: 5, pid: '01HPID123' },
    });

    expect(navigate).toHaveBeenCalledWith('/p/thr_leave_request/view/01HPID123');
  });

  it('executes command actions with evaluated pid target and payload', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: { status: 'approved' },
    });
    const expressionContext = {
      record: { pid: 'PUB-APP-001' },
      form: { reviewNotes: 'Looks ready' },
    };
    const expressionEvaluator = {
      evaluateTemplate: vi.fn((template: string) =>
        template
          .replace('${record.pid}', expressionContext.record.pid)
          .replace('${form.reviewNotes}', expressionContext.form.reviewNotes),
      ),
      evaluateObject: vi.fn((value: Record<string, any>) =>
        Object.fromEntries(
          Object.entries(value).map(([key, item]) => [
            key,
            typeof item === 'string'
              ? item
                  .replace('${record.pid}', expressionContext.record.pid)
                  .replace('${form.reviewNotes}', expressionContext.form.reviewNotes)
              : item,
          ]),
        ),
      ),
    };

    await actionRegistry.execute('command.execute', {
      fetchResult,
      expressionContext,
      expressionEvaluator,
      args: {
        command: 'mkt:approve_publisher_application',
        targetRecordPid: '${record.pid}',
        operationType: 'approve',
        payload: {
          mkt_pa_review_notes: '${form.reviewNotes}',
        },
      },
    });

    expect(fetchResult).toHaveBeenCalledWith(
      '/api/meta/commands/execute/mkt:approve_publisher_application',
      {
        method: 'post',
        params: {
          targetRecordPid: 'PUB-APP-001',
          targetRecordId: 'PUB-APP-001',
          operationType: 'APPROVE',
          payload: {
            mkt_pa_review_notes: 'Looks ready',
          },
        },
        token: undefined,
      },
    );
  });
});
