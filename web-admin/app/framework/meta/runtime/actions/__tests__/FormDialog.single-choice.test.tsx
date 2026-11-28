import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FormDialog from '../FormDialog';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en', t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));

function open(required: boolean, value: unknown) {
  const onSubmit = vi.fn();
  render(<FormDialog />);
  act(() => window.dispatchEvent(new CustomEvent('dialog:form', { detail: {
    fields: [{ field: 'choice', label: 'Linked record', type: 'multiselect', required,
      props: { selectionMode: 'single' }, options: [{ value: 'a', label: 'Alpha' }, { value: 'b', label: 'Beta' }] }],
    defaults: { choice: value }, onSubmit,
  } })));
  fireEvent.click(screen.getByTestId('form-dialog-submit'));
  return onSubmit;
}

describe('single-choice cardinality in command dialogs', () => {
  it('submits an explicitly empty optional choice', () => {
    expect(open(false, [])).toHaveBeenCalledExactlyOnceWith({ choice: [] });
  });
  it('still refuses an empty required choice', () => {
    expect(open(true, [])).not.toHaveBeenCalled();
  });
  it.each([false, true])('refuses multiple selected values when required=%s', required => {
    expect(open(required, ['a', 'b'])).not.toHaveBeenCalled();
  });
  it.each([false, true])('submits one selected value when required=%s', required => {
    expect(open(required, ['a'])).toHaveBeenCalledExactlyOnceWith({ choice: ['a'] });
  });
});
