import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { InboxWidget } from '../InboxWidget';

const submitApprovalAction = vi.fn();
const listInboxItems = vi.fn(async () => ({
  total: 2,
  records: [
    { id: 1, title: 'Close Capa', itemType: 'approval', createdAt: new Date().toISOString() },
    { id: 2, title: 'Verify Capa', itemType: 'task', createdAt: new Date().toISOString() },
  ],
}));

vi.mock('~/shared/services/inboxService', () => ({
  listInboxItems: (...args: unknown[]) => listInboxItems(...(args as [])),
  submitApprovalAction: (...args: unknown[]) => submitApprovalAction(...(args as [])),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('InboxWidget — table redesign', () => {
  it('renders a <table> with new column headers', async () => {
    const { findByRole } = render(<InboxWidget />);
    const table = (await findByRole('table')) as HTMLElement;
    const headers = Array.from(table.querySelectorAll<HTMLElement>('th')).map((h) =>
      h.textContent?.trim(),
    );
    expect(headers).toEqual(expect.arrayContaining(['workbench.inbox.col.task', 'workbench.inbox.col.type', 'workbench.inbox.col.due']));
  });

  it('renders a colored badge for each item type', async () => {
    const { findAllByTestId } = render(<InboxWidget />);
    const badges = await findAllByTestId('inbox-type-badge');
    expect(badges.length).toBeGreaterThan(0);
    expect(badges[0].className).toMatch(/bg-(amber|blue|red|violet|green|indigo|gray)-/);
  });

  it('does not render an avatar column', () => {
    render(<InboxWidget />);
    expect(screen.queryByTestId('inbox-avatar')).toBeNull();
  });

  it('renders a quick-approve button only for approval-type rows', async () => {
    const { findByTestId } = render(<InboxWidget />);
    await findByTestId('inbox-approve-1');
    expect(screen.queryByTestId('inbox-approve-2')).toBeNull();
  });

  it('invokes submitApprovalAction("approve") and refreshes when quick-approve is clicked', async () => {
    submitApprovalAction.mockClear();
    listInboxItems.mockClear();
    const { findByTestId } = render(<InboxWidget />);
    const btn = await findByTestId('inbox-approve-1');
    fireEvent.click(btn);
    await waitFor(() => expect(submitApprovalAction).toHaveBeenCalledWith(1, 'approve'));
    // Initial load + reload after approve
    await waitFor(() => expect(listInboxItems).toHaveBeenCalledTimes(2));
  });
});
