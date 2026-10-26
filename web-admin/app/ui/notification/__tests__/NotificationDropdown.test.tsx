import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationDropdown } from '../NotificationDropdown';

const http = vi.hoisted(() => ({ fetchResult: vi.fn() }));
vi.mock('~/shared/services/http-client', () => http);
vi.mock('~/hooks/useHydrated', () => ({ useHydrated: () => true }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN',t: (key: string) => key }) }));
const notification = { id: 'notice',title: 'Review published policy',content: 'Open the exact version',category: 'system',
  priority: 'normal',isRead: false,createdAt: '2026-10-25T00:00:00Z',sourceType: 'announcement',sourceId: 'version' };

describe('notification dropdown failure and recovery', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows a load failure instead of an empty inbox, retries real loading, and restores Escape focus', async () => {
    http.fetchResult.mockResolvedValueOnce({ code: 'UNAVAILABLE', data: null })
      .mockResolvedValueOnce({ code: '0', data: { records: [notification],total: 1 } });
    render(<MemoryRouter><NotificationDropdown unreadCount={1} onMarkAllRead={vi.fn()} /></MemoryRouter>);
    fireEvent.click(screen.getByTestId('notification-bell'));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('无法加载通知'));
    expect(screen.queryByText('notification.center.empty')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'重试'}));
    await waitFor(() => expect(screen.getByText(notification.title)).toBeVisible());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    screen.getByTestId('mark-all-read-btn').focus();fireEvent.keyDown(document,{key:'Escape'});
    expect(screen.queryByTestId('notification-dropdown-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('notification-bell')).toHaveFocus();
  });

  it('retains unread styling and explains an unconfirmed read-all operation', async () => {
    http.fetchResult.mockResolvedValue({ code: '0', data: { records: [notification],total: 1 } });
    const markAll = vi.fn().mockRejectedValue(new Error('response lost'));
    render(<MemoryRouter><NotificationDropdown unreadCount={1} onMarkAllRead={markAll} /></MemoryRouter>);
    fireEvent.click(screen.getByTestId('notification-bell'));
    await waitFor(() => expect(screen.getByTestId('notification-item')).toBeVisible());
    fireEvent.click(screen.getByTestId('mark-all-read-btn'));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('未能确认全部已读'));
    expect(screen.getByTestId('notification-item').className).toContain('bg-indigo-50/40');
    expect(markAll).toHaveBeenCalledOnce();
  });
});
