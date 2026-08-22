import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RecordComments } from '../RecordComments';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const fetchMock = vi.mocked(fetchResult);

const rootComment = {
  commentPid: 'comment-root',
  content: '推进报价确认',
  created_at: '2026-08-21T08:00:00Z',
  updated_at: '2026-08-21T08:00:00Z',
  is_edited: false,
  actorName: '销售一组',
  canEdit: true,
  mentionedUsers: [],
  replies: [
    {
      commentPid: 'comment-reply',
      parentPid: 'comment-root',
      replyToName: '销售一组',
      content: '已联系客户，下午反馈',
      created_at: '2026-08-21T09:00:00Z',
      updated_at: '2026-08-21T09:00:00Z',
      is_edited: false,
      actorName: '方案顾问',
      canEdit: false,
      mentionedUsers: [],
    },
  ],
};

const page = (items = [rootComment]) => ({
  code: '0',
  data: {
    items,
    total: items.length,
    commentCount: items.reduce((count, item) => count + 1 + (item.replies?.length ?? 0), 0),
    page: 1,
    size: 10,
    hasMore: false,
  },
});

describe('RecordComments', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('renders a two-level thread and exposes edit/delete only for owned comments', async () => {
    fetchMock.mockResolvedValue(page() as never);

    render(
      <RecordComments modelCode="crm_activity_common" recordPid="activity-1" locale="zh-CN" />,
    );

    expect(await screen.findByText('推进报价确认')).toBeInTheDocument();
    expect(screen.getByText('已联系客户，下午反馈')).toBeInTheDocument();
    expect(screen.getByText('@销售一组')).toBeInTheDocument();
    expect(screen.getAllByText('编辑')).toHaveLength(1);
    expect(screen.getAllByText('删除')).toHaveLength(1);
  });

  it('posts a reply against the selected comment', async () => {
    fetchMock
      .mockResolvedValueOnce(page() as never)
      .mockResolvedValueOnce({ code: '0', data: { commentPid: 'new-reply' } } as never)
      .mockResolvedValueOnce(page() as never);
    const user = userEvent.setup();

    render(
      <RecordComments modelCode="crm_activity_common" recordPid="activity-1" locale="zh-CN" />,
    );
    await screen.findByText('推进报价确认');
    await user.click(screen.getAllByRole('button', { name: '回复' })[0]);
    const input = screen.getByPlaceholderText(/写下回复/);
    await user.type(input, '报价已确认');
    await user.click(screen.getByRole('button', { name: '发送回复' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/records/crm_activity_common/activity-1/comments',
        expect.objectContaining({
          method: 'post',
          params: { content: '报价已确认', mentionUserPids: [], parentPid: 'comment-root' },
        }),
      );
    });
  });

  it('resolves an @ mention and submits the public user pid', async () => {
    fetchMock.mockImplementation(async (url, options) => {
      if (String(url).includes('/api/admin/users/search')) {
        return {
          code: '0',
          data: [{ pid: 'user-alice', displayName: 'Alice', email: 'alice@example.com' }],
        } as never;
      }
      if (options?.method === 'post')
        return { code: '0', data: { commentPid: 'new-root' } } as never;
      return page([]) as never;
    });
    const user = userEvent.setup();

    render(
      <RecordComments modelCode="crm_activity_common" recordPid="activity-1" locale="zh-CN" />,
    );
    await screen.findByTestId('comment-empty');
    const input = screen.getByPlaceholderText(/写下跟进/);
    await user.type(input, '@Ali');
    expect(await screen.findByText('Alice')).toBeInTheDocument();
    await user.click(screen.getByText('Alice'));
    await waitFor(() => expect(input).toHaveValue('@Alice '));
    fireEvent.change(input, { target: { value: '@Alice 请确认方案' } });
    await user.click(screen.getByRole('button', { name: '发送评论' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/records/crm_activity_common/activity-1/comments',
        expect.objectContaining({
          method: 'post',
          params: {
            content: '@Alice 请确认方案',
            mentionUserPids: ['user-alice'],
            parentPid: undefined,
          },
        }),
      );
    });
  });

  it('requires inline confirmation before deleting an owned root comment', async () => {
    fetchMock
      .mockResolvedValueOnce(page() as never)
      .mockResolvedValueOnce({ code: '0', data: true } as never)
      .mockResolvedValueOnce(page([]) as never);

    render(
      <RecordComments modelCode="crm_activity_common" recordPid="activity-1" locale="zh-CN" />,
    );
    await screen.findByText('推进报价确认');
    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    expect(screen.getByText('确认删除？')).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '确认' }));
    });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/records/crm_activity_common/activity-1/comments/comment-root',
        { method: 'delete' },
      );
    });
  });
});
