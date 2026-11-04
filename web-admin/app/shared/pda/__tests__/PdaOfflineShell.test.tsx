import { fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PdaOfflineShell } from '../PdaOfflineShell';
import { PDA_SHELL_ROUTES } from '../offlineReplay';

// Exercise real SPA location changes rather than the shared setup's '/' stub.
vi.unmock('react-router');

const lifecycle = vi.hoisted(() => ({ start: vi.fn(), suspend: vi.fn(), replay: vi.fn() }));
vi.mock('../offlineReplay', async (original) => ({
  ...(await original<typeof import('../offlineReplay')>()),
  startPdaOfflineShell: lifecycle.start,
  suspendPdaOfflineShell: lifecycle.suspend,
  replayNow: lifecycle.replay,
}));

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});

describe('authenticated PDA route lifecycle', () => {
  it('activates on SPA entry and suspends when returning to a Quote page', () => {
    render(
      <MemoryRouter initialEntries={['/p/qo_quote_list']}>
        <Link to="/inventory/pda-pick">PDA</Link>
        <Link to="/p/qo_quote_list">Quotes</Link>
        <PdaOfflineShell enabled />
      </MemoryRouter>,
    );
    expect(lifecycle.start).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('PDA'));
    expect(lifecycle.start).toHaveBeenCalledOnce();
    expect(lifecycle.replay).toHaveBeenCalledWith('route-entry');
    fireEvent.click(screen.getByText('Quotes'));
    expect(lifecycle.suspend).toHaveBeenCalledOnce();
    expect(lifecycle.start).toHaveBeenCalledOnce();
  });

  it('waits for authentication and suspends when it is removed', () => {
    const content = (enabled: boolean) => (
      <MemoryRouter initialEntries={['/inventory/pda-tasks']}>
        <PdaOfflineShell enabled={enabled} />
      </MemoryRouter>
    );
    const { rerender } = render(content(false));
    expect(lifecycle.start).not.toHaveBeenCalled();
    rerender(content(true));
    expect(lifecycle.start).toHaveBeenCalledOnce();
    rerender(content(false));
    expect(lifecycle.suspend).toHaveBeenCalledOnce();
  });

  it.each(PDA_SHELL_ROUTES)('retains startup replay on %s', (path) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <PdaOfflineShell enabled />
      </MemoryRouter>,
    );
    expect(lifecycle.start).toHaveBeenCalledOnce();
    expect(lifecycle.replay).toHaveBeenCalledWith('route-entry');
  });
});
