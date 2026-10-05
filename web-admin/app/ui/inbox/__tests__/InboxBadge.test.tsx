import React from 'react';
import { renderToString } from 'react-dom/server';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { InboxBadge } from '../InboxBadge';

vi.mock('~/shared/services/inboxService', () => ({ getUnreadCount: vi.fn(async () => 3) }));

describe('InboxBadge hydration boundary', () => {
  it('does not advertise a clickable inbox before hydration attaches the handler', () => {
    const html = renderToString(<InboxBadge onClick={vi.fn()} />);
    const root = document.createElement('div');
    root.innerHTML = html;
    const badge = root.querySelector('button')!;
    expect(badge.disabled).toBe(true);
    expect(badge.getAttribute('aria-busy')).toBe('true');
  });

  it('enables the real handler after hydration and exposes the dropdown state', async () => {
    const onClick = vi.fn();
    const { rerender, unmount } = render(<InboxBadge onClick={onClick} isOpen={false} />);
    const badge = screen.getByTestId('inbox-badge');
    await waitFor(() => expect(badge).toBeEnabled());
    expect(badge).toHaveAttribute('aria-busy', 'false');
    expect(badge).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(badge);
    expect(onClick).toHaveBeenCalledTimes(1);
    rerender(<InboxBadge onClick={onClick} isOpen />);
    expect(badge).toHaveAttribute('aria-expanded', 'true');
    unmount();
  });
});
