import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import NamedQueryEditPage from '../$pid';

// The router snapshot can lag a native hashchange; the event must use the browser URL.
vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({ pid: 'query-pid' }),
  useLocation: () => ({ pathname: '/meta/named-queries/query-pid', hash: '' }),
  useLoaderData: () => ({
    query: { pid: 'query-pid', code: 'owned_query', title: 'Owned query',
      status: 'draft', fromSql: 'mt_e2et_order', policy: { maxRows: 5000 } },
    fields: [],
  }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('../components/SqlEditor', () => ({ default: () => <div>SQL editor</div> }));
vi.mock('../components/FieldInference', () => ({ default: () => null }));
vi.mock('../components/ExportPanel', () => ({ default: () => null }));
vi.mock('~/shared/services/namedQueryService', () => ({
  namedQueryService: { getVersions: vi.fn().mockResolvedValue([]) },
}));

beforeEach(() => { window.history.replaceState(null, '', '/meta/named-queries/query-pid'); });

describe('NamedQuery native hash navigation', () => {
  it('keeps the clicked tab after hashchange while the router snapshot is stale', () => {
    render(<NamedQueryEditPage />);
    fireEvent.click(screen.getByTestId('tab-fields'));
    expect(window.location.hash).toBe('#fields');
    fireEvent(window, new HashChangeEvent('hashchange'));
    expect(screen.getByText('字段列表')).toBeVisible();
    expect(screen.queryByText('SQL editor')).not.toBeInTheDocument();
  });

  it('follows browser hash changes and rejects unknown tabs', () => {
    render(<NamedQueryEditPage />);
    window.history.replaceState(null, '', '#policy');
    fireEvent(window, new HashChangeEvent('hashchange'));
    expect(screen.getByTestId('policy-max-rows')).toHaveValue(5000);
    window.history.replaceState(null, '', '#unknown');
    fireEvent(window, new HashChangeEvent('hashchange'));
    expect(screen.getByText('SQL editor')).toBeVisible();
    expect(screen.queryByTestId('policy-max-rows')).not.toBeInTheDocument();
  });
});
