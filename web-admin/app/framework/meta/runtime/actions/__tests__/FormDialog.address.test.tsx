import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import FormDialog from '../FormDialog';
import { validAddressInput } from '../addressInput';
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));

function open(defaults: Record<string, unknown> = {}, locale = 'zh-CN') {
  const onSubmit = vi.fn();
  render(
    <I18nProvider initialData={{}} initialLocale={locale}>
      <FormDialog />
    </I18nProvider>,
  );
  act(() =>
    window.dispatchEvent(
      new CustomEvent('dialog:form', {
        detail: {
          fields: [
            {
              field: 'location',
              label: '收货地址',
              type: 'address',
              required: true,
              props: { requireDistrict: true, detailMaxLength: 500, allowManualEntry: true },
            },
          ],
          defaults,
          onSubmit,
        },
      }),
    ),
  );
  return onSubmit;
}

describe('shared command address input', () => {
  it('renders the existing region controls rather than a raw object textbox', () => {
    open();
    expect(screen.getByRole('combobox', { name: '省份' })).toBeVisible();
    expect(screen.getByRole('combobox', { name: '城市' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: '区县' })).toBeDisabled();
  });
  it('does not treat a nonempty but incomplete address object as valid required input', () => {
    const submit = open({
      location: { province: '广东省', city: '深圳市', district: '', detail: '工业一路28号' },
    });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).not.toHaveBeenCalled();
  });
  it('submits one structured region/address value without stringifying it', () => {
    const submit = open();
    fireEvent.change(screen.getByRole('combobox', { name: '省份' }), {
      target: { value: '广东省' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: '城市' }), {
      target: { value: '深圳市' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: '区县' }), {
      target: { value: '宝安区' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: '详细地址' }), {
      target: { value: '工业一路28号' },
    });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).toHaveBeenCalledWith({
      location: { province: '广东省', city: '深圳市', district: '宝安区', detail: '工业一路28号' },
    });
  });
  it('clears dependent regions on province change and keeps the street draft', () => {
    const submit = open({
      location: { province: '广东省', city: '深圳市', district: '宝安区', detail: '保留街道草稿' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: '省份' }), {
      target: { value: '北京市' },
    });
    expect(screen.getByRole('combobox', { name: '城市' })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: '区县' })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: '区县' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: '详细地址' })).toHaveValue('保留街道草稿');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).not.toHaveBeenCalled();
  });
  it('explicitly accepts maintained names outside the compact built-in list', () => {
    const submit = open();
    fireEvent.click(screen.getByRole('checkbox', { name: '手动填写地区' }));
    for (const [label, value] of [
      ['省份', '实际省份'],
      ['城市', '实际城市'],
      ['区县', '实际区县'],
      ['详细地址', '真实地点42号'],
    ])
      fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).toHaveBeenCalledWith({
      location: {
        province: '实际省份',
        city: '实际城市',
        district: '实际区县',
        detail: '真实地点42号',
      },
    });
  });
  it('preserves an unlisted stored address in the visibly selected manual mode', () => {
    const location = {
      province: '列表外省份',
      city: '列表外城市',
      district: '列表外区县',
      detail: '旧地址28号',
    };
    const submit = open({ location });
    expect(screen.getByRole('checkbox', { name: '手动填写地区' })).toBeChecked();
    expect(screen.getByRole('textbox', { name: '城市' })).toHaveValue('列表外城市');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).toHaveBeenCalledWith({ location });
  });
  it('switches back to explicit cascade selection without submitting hidden old region names', () => {
    const submit = open({
      location: {
        province: '列表外省份',
        city: '列表外城市',
        district: '列表外区县',
        detail: '保留地址',
      },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: '手动填写地区' }));
    expect(screen.getByRole('combobox', { name: '省份' })).toHaveValue('');
    expect(screen.getByRole('textbox', { name: '详细地址' })).toHaveValue('保留地址');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(submit).not.toHaveBeenCalled();
  });
  it('cancels a draft without invoking the command callback', () => {
    const submit = open();
    fireEvent.click(screen.getByRole('checkbox', { name: '手动填写地区' }));
    fireEvent.change(screen.getByRole('textbox', { name: '省份' }), {
      target: { value: '草稿省份' },
    });
    fireEvent.click(screen.getByTestId('form-dialog-cancel'));
    expect(submit).not.toHaveBeenCalled();
    expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
  });
  it('localizes the existing component in an English command dialog', () => {
    open({}, 'en-US');
    expect(screen.getByRole('combobox', { name: 'Province' })).toBeVisible();
    expect(screen.getByRole('checkbox', { name: 'Enter region names manually' })).toBeVisible();
    expect(screen.getByRole('textbox', { name: 'Street address' })).toHaveAttribute(
      'placeholder',
      'Enter the street, building, floor or receiving location',
    );
  });
  it.each([
    null,
    'raw address',
    [],
    {},
    { province: 'P', city: 'C', district: 'D', detail: 'Street', extra: 'unknown' },
    { province: 1, city: 'C', district: 'D', detail: 'Street' },
    { province: 'P', city: 'C', district: 'D', detail: ' ' },
    { province: 'P', city: 'C', district: 'D', detail: 'x'.repeat(501) },
    { province: 'x'.repeat(101), city: 'C', district: 'D', detail: 'Street' },
  ])('rejects malformed or incomplete required address %j', (value) => {
    expect(validAddressInput(value, { required: true, detailMaxLength: 500 })).toBe(false);
  });
  it('allows explicit optional omissions without making partial supplied addresses valid', () => {
    expect(validAddressInput(undefined, { required: false })).toBe(true);
    expect(
      validAddressInput(
        { province: 'P', city: 'C', district: '', detail: '' },
        { requireDistrict: false, showDetailAddress: false },
      ),
    ).toBe(true);
    expect(
      validAddressInput(
        { province: 'P', city: '', district: '', detail: '' },
        { requireDistrict: false, showDetailAddress: false },
      ),
    ).toBe(false);
  });
});
