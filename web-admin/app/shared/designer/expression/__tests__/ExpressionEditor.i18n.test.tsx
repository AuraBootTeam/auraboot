import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

const zh: Record<string, string> = {
  'expression.mode.conditions': '条件',
  'expression.mode.expression': '表达式',
  'expression.action.addCondition': '添加条件',
  'expression.placeholder.selectField': '选择字段...',
  'expression.placeholder.value': '输入值...',
  'expression.fieldGroup.fields': '字段',
  'expression.variable.userId': '用户 ID',
  'expression.variable.userName': '用户姓名',
  'expression.variable.userEmail': '用户邮箱',
  'expression.variable.userRoles': '用户角色',
  'expression.variable.userPermissions': '用户权限',
  'expression.variable.formMode': '表单模式',
  'expression.variable.pageKind': '页面类型',
  'expression.variable.pageModelCode': '页面模型编码',
  'expression.variable.pageKey': '页面键',
  'expression.variable.pageMode': '页面模式',
  'expression.variable.currentRecordPid': '当前记录 PID',
  'expression.variable.recordPid': '记录 PID',
  'expression.variable.activeFilters': '当前筛选',
  'expression.variable.selectedRowPids': '已选行 PID',
  'formula.functions': '函数',
  'formula.insertField': '插入字段...',
  'formula.fieldPicker.title': '可用字段',
  'formula.fieldPicker.close': '收起字段',
  'formula.fieldPicker.quick': '常用上下文',
  'formula.fieldPicker.quickFields': '常用字段',
  'formula.help': "使用 #FUNCTION() 调用函数，使用 #fieldCode 引用字段。例如：#IF(#amount > 100, '高', '低')",
};

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string, _params?: unknown, fallback?: string) => zh[key] ?? fallback ?? key,
  }),
}));

import { ExpressionEditor } from '../ExpressionEditor';

function adapter(value: unknown, setValue = vi.fn()) {
  return {
    value,
    setValue,
    error: undefined,
    required: false,
    disabled: false,
  };
}

describe('ExpressionEditor i18n', () => {
  it('localizes text mode controls and context variables in zh-CN', () => {
    render(<ExpressionEditor adapter={adapter('automation.record.updated')} name="expr" />);

    expect(screen.getByTestId('mode-builder')).toHaveTextContent('条件');
    expect(screen.getByTestId('mode-text')).toHaveTextContent('表达式');
    expect(screen.getByText('fx 函数')).toBeInTheDocument();
    expect(screen.getByText('插入字段...')).toBeInTheDocument();
    expect(screen.queryByTestId('formula-field-picker')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('插入字段...'));

    const picker = screen.getByTestId('formula-field-picker');
    expect(picker).toHaveTextContent('常用上下文');
    expect(pickerButtons(picker, /用户 ID.*\$user\.id/).length).toBeGreaterThan(0);
    expect(screen.queryByText("使用 #FUNCTION() 调用函数，使用 #fieldCode 引用字段。例如：#IF(#amount > 100, '高', '低')")).not.toBeInTheDocument();

    expect(screen.queryByText('Conditions')).not.toBeInTheDocument();
    expect(screen.queryByText('Insert field...')).not.toBeInTheDocument();
    expect(screen.queryByText('User Email')).not.toBeInTheDocument();
  });

  it('inserts runtime template fields for scenario model fields in text mode', () => {
    const setValue = vi.fn();
    render(
      <ExpressionEditor
        adapter={adapter('提醒 ', setValue)}
        name="expr"
        modelFields={[
          {
            code: 'record.wd_req_days',
            name: '请假天数',
            category: 'number',
            group: '请假申请',
            insertion: '${record.wd_req_days}',
          },
          {
            code: '$user.id',
            name: '用户 ID',
            category: 'string',
            group: '$user',
          },
        ]}
      />,
    );

    fireEvent.click(screen.getByText('插入字段...'));

    const picker = screen.getByTestId('formula-field-picker');
    expect(picker).toHaveTextContent('常用字段');
    fireEvent.click(pickerButtons(picker, /请假天数.*record\.wd_req_days/)[0]);

    expect(setValue).toHaveBeenCalledWith('提醒 ${record.wd_req_days}');
  });

  it('localizes builder controls in zh-CN', () => {
    render(<ExpressionEditor adapter={adapter('')} name="expr" />);

    expect(screen.getByTestId('condition-add')).toHaveTextContent('添加条件');

    fireEvent.click(screen.getByTestId('condition-add'));

    expect(screen.getByText('选择字段...')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入值...')).toBeInTheDocument();
  });
});

function pickerButtons(container: HTMLElement, name: RegExp): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button')).filter((button) =>
    name.test(button.textContent?.replace(/\s+/g, ' ') ?? ''),
  );
}
