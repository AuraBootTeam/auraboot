/**
 * AiFillBannerBlockRenderer.lockedFields.test.tsx
 *
 * The AI fill banner must forward the form's AI-locked field codes to the
 * backend (D5) so the server never even returns values for locked fields —
 * defence in depth alongside the client-side applyFields skip.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/react';

const postMock = vi.fn();
vi.mock('~/shared/services/http-client', () => ({
  post: (...args: unknown[]) => postMock(...args),
}));

vi.mock('~/framework/meta/rendering/DslFormFillContext', () => ({
  useDslFormFill: () => ({ applyFields: vi.fn(), lockedFields: ['wd_req_reason'] }),
}));

import { AiFillBannerBlockRenderer } from '../AiFillBannerBlockRenderer';

const runtime = {
  getContext: () => ({ locale: 'zh-CN', t: (k: string) => k }),
} as never;

describe('AiFillBannerBlockRenderer — forwards locked fields', () => {
  beforeEach(() => {
    postMock.mockReset();
    postMock.mockResolvedValue({ code: 0, data: { fields: {} } });
  });

  it('includes the form lockedFields in the ai-fill POST body', async () => {
    const block = { id: 'b1', blockType: 'ai-fill-banner', endpoint: '/api/wd-leave-request/ai-fill' } as never;
    const { getByTestId } = render(<AiFillBannerBlockRenderer block={block} runtime={runtime} />);

    fireEvent.click(getByTestId('ai-fill-trigger'));
    fireEvent.change(getByTestId('ai-fill-input'), { target: { value: '请假两天' } });
    fireEvent.click(getByTestId('ai-fill-confirm'));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [endpoint, body] = postMock.mock.calls[0];
    expect(endpoint).toBe('/api/wd-leave-request/ai-fill');
    expect((body as Record<string, unknown>).lockedFields).toEqual(['wd_req_reason']);
  });
});
