import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { MemberPicker } from '../MemberPicker';

const I18N = {
  member_picker: {
    select: 'Select member…',
    search_placeholder: 'Search members…',
    searching: 'Searching…',
    empty: 'No members found',
  },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function searchResponse(records: unknown[]) {
  return {
    ok: true,
    json: async () => ({ code: '0', data: { records } }),
  };
}

describe('MemberPicker', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps the latest search results when an older request resolves last', async () => {
    const initialSearch = deferred<ReturnType<typeof searchResponse>>();
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => initialSearch.promise)
      .mockResolvedValueOnce(searchResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    render(
      <I18nProvider initialLocale="en-US" initialData={I18N}>
        <MemberPicker />
      </I18nProvider>,
    );

    fireEvent.click(screen.getByTestId('member-picker-add'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByTestId('member-picker-search-input'), {
      target: { value: 'outsider@example.test' },
    });

    expect(await screen.findByText('No members found')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/tenant/members/search',
      expect.objectContaining({
        body: JSON.stringify({
          pageNum: 1,
          pageSize: 20,
          status: 'active',
          keyword: 'outsider@example.test',
        }),
      }),
    );

    initialSearch.resolve(
      searchResponse([
        {
          displayName: 'Stale Tenant Member',
          user: { pid: 'stale-member-pid', email: 'stale@example.test' },
        },
      ]),
    );

    await waitFor(() => expect(screen.getByText('No members found')).toBeInTheDocument());
    expect(screen.queryByTestId('member-picker-option-stale-member-pid')).not.toBeInTheDocument();
    expect(screen.queryByText('Stale Tenant Member')).not.toBeInTheDocument();
  });

  it('clears candidates when the latest search fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        searchResponse([
          {
            displayName: 'Previous Member',
            user: { pid: 'previous-member-pid', email: 'previous@example.test' },
          },
        ]),
      )
      .mockResolvedValueOnce({ ok: false });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <I18nProvider initialLocale="en-US" initialData={I18N}>
        <MemberPicker />
      </I18nProvider>,
    );

    fireEvent.click(screen.getByTestId('member-picker-add'));
    expect(await screen.findByText('Previous Member')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('member-picker-search-input'), {
      target: { value: 'unavailable@example.test' },
    });

    expect(await screen.findByText('No members found')).toBeInTheDocument();
    expect(
      screen.queryByTestId('member-picker-option-previous-member-pid'),
    ).not.toBeInTheDocument();
  });
});
