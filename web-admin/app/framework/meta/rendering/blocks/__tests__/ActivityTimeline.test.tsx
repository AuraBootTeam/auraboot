import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ActivityTimeline } from '../ActivityTimeline';

const fetchResultMock = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

describe('ActivityTimeline', () => {
  afterEach(() => {
    fetchResultMock.mockReset();
  });

  it('does not render internal pid values from activity actor names', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: [
        {
          id: 11,
          pid: '01KV2G36HAZXJVKVC8P1GTQFJ1',
          objectModel: 'qo_quote_common',
          objectRecord: '01KV2G6Y80PST72VASRDYV0S4A',
          activityType: 'update',
          subject: 'QO-20260614-001',
          content: null,
          actorType: 'system',
          actorId: null,
          actorName: '01KV2G36HAZXJVKVC8P1GTQFJ1',
          commandCode: 'qo_quote_common:generate_document',
          operationType: 'update',
          metadata: null,
          occurredAt: '2026-06-14T15:34:00+08:00',
          createdAt: '2026-06-14T15:34:00+08:00',
        },
      ],
    });

    const { container } = render(
      <ActivityTimeline
        modelCode="qo_quote_common"
        recordPid="01KV2G6Y80PST72VASRDYV0S4A"
        locale="zh-CN"
      />,
    );

    await waitFor(() => expect(screen.getByTestId('activity-timeline')).toBeVisible());

    expect(screen.getByText('QO-20260614-001')).toBeVisible();
    expect(screen.getByText('更新')).toBeVisible();
    expect(container).not.toHaveTextContent('01KV2G36HAZXJVKVC8P1GTQFJ1');
    expect(container).not.toHaveTextContent('01KV2G6Y80PST72VASRDYV0S4A');
    expect(container).not.toHaveTextContent('qo_quote_common:generate_document');
  });
});
