import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import FormDialog from '../FormDialog';
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'America/New_York' }) }));
function open(onSubmit: (value: unknown) => void) {
  render(<I18nProvider initialData={{}} initialLocale="zh-CN"><FormDialog /></I18nProvider>);
  act(() => { window.dispatchEvent(new CustomEvent('dialog:form', { detail: {
    fields: [{ field: 'scheduledAt', label: '预约时间', type: 'datetime' }], defaults: {}, onSubmit,
  } })); });
}
describe('FormDialog tenant time zone', () => {
  it('submits the selected wall time in the effective zone', () => {
    const submit = vi.fn(); open(submit);
    fireEvent.change(screen.getByLabelText('预约时间'), { target: { value: '2026-10-30T15:45' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).toHaveBeenCalledWith({ scheduledAt: '2026-10-30T19:45:00.000Z' });
  });
  it('keeps an invalid spring clock time visible and blocks instead of scheduling immediately', () => {
    const submit = vi.fn(); open(submit);
    fireEvent.change(screen.getByLabelText('预约时间'), { target: { value: '2026-03-08T02:30' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).not.toHaveBeenCalled();
    expect(screen.getByLabelText('预约时间')).toHaveValue('2026-03-08T02:30');
    expect(screen.getByText('所选时间在当前时区不存在，请重新选择。')).toBeVisible();
    fireEvent.change(screen.getByLabelText('预约时间'), { target: { value: '2026-03-08T03:30' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).toHaveBeenCalledWith({ scheduledAt: '2026-03-08T07:30:00.000Z' });
  });
});
