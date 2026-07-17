import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { FormulaEditor } from '../FormulaEditor';

const zh: Record<string, string> = {
  'formula.functions': '函数',
  'formula.insertField': '插入字段',
  'formula.fieldPicker.title': '可用字段',
  'formula.fieldPicker.close': '收起字段',
  'formula.fieldPicker.quick': '常用上下文',
  'formula.fieldPicker.quickFields': '常用字段',
  'formula.fieldPicker.empty': '暂无可插入字段',
  'formula.help': "使用 #FUNCTION() 调用函数，使用 #fieldCode 引用字段。例如：#IF(#amount > 100, '高', '低')",
};

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string, _params?: unknown, fallback?: string) => zh[key] ?? fallback ?? key,
  }),
}));

describe('FormulaEditor field picker', () => {
  it('keeps field options collapsed until the user opens the picker', () => {
    render(
      <FormulaEditor
        value=""
        onChange={vi.fn()}
        fields={[
          { code: '$record.pid', name: '记录 PID', group: '$record' },
          { code: 'wd_req_days', name: '请假申请 / 天数', group: '请假申请' },
        ]}
      />,
    );

    expect(screen.getByRole('button', { name: '插入字段' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /记录 PID/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('formula-field-picker')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '插入字段' }));

    const picker = screen.getByTestId('formula-field-picker');
    expect(picker).toHaveTextContent('常用字段');
    expect(picker).toHaveTextContent('$record');
    expect(pickerButtons(picker, /记录 PID/).length).toBeGreaterThan(0);
    expect(pickerButtons(picker, new RegExp('请假申请 / 天数')).length)
      .toBeGreaterThan(0);
  });

  it('prioritizes scenario fields in the quick row before generic runtime context', () => {
    render(
      <FormulaEditor
        value=""
        onChange={vi.fn()}
        fields={[
          { code: '$user.id', name: '用户 ID', group: '$user' },
          { code: '$page.pageKey', name: '页面键', group: '$page' },
          { code: 'wd_req_days', name: '请假天数', group: '请假申请' },
          { code: 'wd_req_no', name: '申请编号', group: '请假申请' },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '插入字段' }));

    const picker = screen.getByTestId('formula-field-picker');
    const quickSection = Array.from(picker.children as HTMLCollectionOf<HTMLElement>).find((child) =>
      child.textContent?.includes('常用字段'),
    );
    expect(quickSection).toHaveTextContent('请假天数');
    expect(quickSection).toHaveTextContent('申请编号');
    expect(quickSection).not.toHaveTextContent('用户 ID');

    expect(picker.textContent!.indexOf('请假天数')).toBeLessThan(
      picker.textContent!.indexOf('$user'),
    );
  });

  it('inserts the selected field without prefixing # for runtime context variables', () => {
    const onChange = vi.fn();
    render(
      <FormulaEditor
        value="主管 "
        onChange={onChange}
        fields={[{ code: '$record.pid', name: '记录 PID', group: '$record' }]}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '插入字段' }));
    fireEvent.click(screen.getAllByRole('button', { name: /记录 PID/ })[0]);

    expect(onChange).toHaveBeenCalledWith('主管 $record.pid');
  });

  it('keeps legacy formula fields prefixed with # when inserted', () => {
    const onChange = vi.fn();
    render(
      <FormulaEditor
        value=""
        onChange={onChange}
        fields={[{ code: 'wd_req_days', name: '请假申请 / 天数', group: '请假申请' }]}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '插入字段' }));
    fireEvent.click(screen.getAllByRole('button', { name: new RegExp('请假申请 / 天数') })[0]);

    expect(onChange).toHaveBeenCalledWith('#wd_req_days');
  });

  it('can hide the generic syntax help in compact property panels', () => {
    render(
      <FormulaEditor
        value=""
        onChange={vi.fn()}
        showHelp={false}
        fields={[{ code: '$record.pid', name: '记录 PID', group: '$record' }]}
      />,
    );

    expect(screen.queryByText(zh['formula.help'])).not.toBeInTheDocument();
  });
});

function pickerButtons(container: HTMLElement, name: RegExp): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button')).filter((button) =>
    name.test(button.textContent?.replace(/\s+/g, ' ') ?? ''),
  );
}
