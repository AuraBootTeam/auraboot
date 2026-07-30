import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { ConfirmCard } from '../ConfirmCard';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('ConfirmCard', () => {
  it('rendersBusinessLabelsWithoutLeakingToolOrFieldCodes', () => {
    render(
      <ConfirmCard
        toolId="tool-1"
        toolName="crm:create_account"
        description="Execute crm:create_account with 3 argument(s)."
        input={{
          crm_acc_name: '示例客户',
          crm_acc_industry: '软件',
          crm_acc_rating: 'A',
        }}
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );

    const card = screen.getByTestId('aurabot-confirm-card');
    expect(card).toHaveTextContent('CRM › 新建客户');
    expect(card).toHaveTextContent('执行前请核对 3 项参数');
    expect(card).toHaveTextContent('名称');
    expect(card).toHaveTextContent('行业');
    expect(card).toHaveTextContent('评级');
    expect(card).not.toHaveTextContent('create_account');
    expect(card).not.toHaveTextContent('crm_acc_');
    expect(card).not.toHaveTextContent('Execute');
  });

  it('keepsConfirmationCallbacksAndLocalizedLabels', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(
      <ConfirmCard
        toolId="tool-2"
        toolName="cmd_crm_update_account"
        description=""
        input={{ crm_acc_name: '示例客户' }}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );

    fireEvent.click(screen.getByTestId('aurabot-confirm-approve'));
    fireEvent.click(screen.getByTestId('aurabot-confirm-cancel'));
    expect(onConfirm).toHaveBeenCalledWith('tool-2');
    expect(onCancel).toHaveBeenCalledWith('tool-2');
    expect(screen.getByTestId('aurabot-confirm-approve')).toHaveTextContent('确认');
    expect(screen.getByTestId('aurabot-confirm-cancel')).toHaveTextContent('取消');
  });
});
