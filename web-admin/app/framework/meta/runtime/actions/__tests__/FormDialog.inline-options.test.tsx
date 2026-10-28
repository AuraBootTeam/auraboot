/**
 * FormDialog.inline-options.test.tsx
 *
 * Regression test for inline prompt options (plugins#747). promptInputForm only
 * resolved choice options from dictCode or dataSource, so an author-written
 * inline `options: [{ value, label }]` on a select field was silently dropped
 * and the dropdown rendered empty (mes-wages approve_substitute had to bypass
 * with dictCode). Inline options now win over dictCode/dataSource, with the
 * dict fallback preserved when no inline options exist.
 */
import { MemoryRouter } from 'react-router';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FormDialog, { FormDialogProvider } from '../FormDialog';
import { promptInputForm } from '../ActionRegistry';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));

function Root({ children }: { children?: React.ReactNode }) {
  return (
    <MemoryRouter>
      <FormDialogProvider>
        <FormDialog />
        <FormDialog />
        {children}
      </FormDialogProvider>
    </MemoryRouter>
  );
}

describe('promptInputForm inline options', () => {
  it('renders author-provided inline options without any fetch and submits the chosen value', async () => {
    render(<Root />);
    const fetcher = vi.fn();
    let pending!: Promise<Record<string, unknown>>;
    act(() => {
      pending = promptInputForm(
        [
          {
            field: 'approver_role',
            type: 'select',
            label: 'Role',
            options: [
              { value: 'manager', label: 'Manager' },
              { value: 'finance', label: 'Finance' },
              { value: 'auditor', label: 'Auditor' },
            ],
          },
        ],
        'Pick role',
        fetcher,
        'Submit',
        {},
        { locale: 'en' },
      );
    });
    await screen.findByRole('dialog', { name: 'Pick role' });
    const select = screen.getByTestId('form-dialog-field-approver_role');
    const options = screen.getAllByRole('option');
    // Placeholder + the three inline options, in authored order.
    expect(options).toHaveLength(4);
    expect(options[1]).toHaveTextContent('Manager');
    expect(options[2]).toHaveTextContent('Finance');
    expect(options[3]).toHaveTextContent('Auditor');
    // Inline options must not trigger any dictionary/api fetch.
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.change(select, { target: { value: 'finance' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await expect(pending).resolves.toEqual({ approver_role: 'finance' });
  });

  it('prefers inline options over dictCode (no dictionary fetch, inline labels render)', async () => {
    render(<Root />);
    const fetcher = vi.fn();
    let pending!: Promise<Record<string, unknown>>;
    act(() => {
      pending = promptInputForm(
        [
          {
            field: 'status',
            type: 'select',
            label: 'Status',
            dictCode: 'case_status',
            options: [
              { value: 'open', label: 'Open (inline)' },
              { value: 'archived', label: 'Archived (inline)' },
            ],
          },
        ],
        'Inline wins',
        fetcher,
      );
    });
    // The prompt stays open (never submitted); absorb the teardown cancel.
    void pending.catch(() => null);
    await screen.findByRole('dialog', { name: 'Inline wins' });
    const select = screen.getByTestId('form-dialog-field-status');
    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(3);
    expect(options[1]).toHaveTextContent('Open (inline)');
    expect(options[2]).toHaveTextContent('Archived (inline)');
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('falls back to dictCode options when the field has no inline options', async () => {
    render(<Root />);
    const fetcher = vi
      .fn()
      .mockResolvedValue({
        code: '0',
        data: [
          { value: 'new', label: 'New' },
          { value: 'closed', label: 'Closed' },
        ],
      });
    let pending!: Promise<Record<string, unknown>>;
    act(() => {
      pending = promptInputForm(
        [{ field: 'case_state', type: 'select', label: 'State', dictCode: 'case_state' }],
        'Dict fallback',
        fetcher,
      );
    });
    // The prompt stays open (never submitted); absorb the teardown cancel.
    void pending.catch(() => null);
    await screen.findByRole('dialog', { name: 'Dict fallback' });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe('/api/meta/dict/by-code/case_state/data');
    const select = screen.getByTestId('form-dialog-field-case_state');
    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(3);
    expect(options[1]).toHaveTextContent('New');
    expect(options[2]).toHaveTextContent('Closed');
  });
});
