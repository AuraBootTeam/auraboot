import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InboxWidget } from '../InboxWidget';

vi.mock('~/shared/services/inboxService', () => ({
  listInboxItems: vi.fn(async () => ({
    total: 2,
    records: [
      { id: 1, title: 'Close Capa', itemType: 'approval', createdAt: new Date().toISOString() },
      { id: 2, title: 'Verify Capa', itemType: 'task', createdAt: new Date().toISOString() },
    ],
  })),
  submitApprovalAction: vi.fn(),
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
});
