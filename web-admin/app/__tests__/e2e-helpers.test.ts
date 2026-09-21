import type { Page } from '@playwright/test';
import { describe, expect, it, vi } from 'vitest';

import { ensureSidebarExpanded } from '../../tests/e2e/helpers';

function mockPage(reload: ReturnType<typeof vi.fn>): Page {
  return {
    evaluate: vi.fn().mockResolvedValue(undefined),
    reload,
    waitForLoadState: vi.fn().mockResolvedValue(undefined),
  } as unknown as Page;
}

describe('ensureSidebarExpanded', () => {
  it('retries one reload aborted by a concurrent navigation', async () => {
    const reload = vi
      .fn()
      .mockRejectedValueOnce(new Error('page.reload: net::ERR_ABORTED; maybe frame was detached?'))
      .mockResolvedValueOnce(null);
    const page = mockPage(reload);

    await ensureSidebarExpanded(page);

    expect(reload).toHaveBeenCalledTimes(2);
    expect(page.waitForLoadState).toHaveBeenCalledTimes(2);
  });

  it('does not retry unrelated reload failures', async () => {
    const reload = vi.fn().mockRejectedValue(new Error('page.reload: net::ERR_CONNECTION_REFUSED'));
    const page = mockPage(reload);

    await expect(ensureSidebarExpanded(page)).rejects.toThrow('ERR_CONNECTION_REFUSED');
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
