import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ToolResultCard } from '../ToolResultCard';

describe('ToolResultCard', () => {
  it('shows a pending policy decision without exposing transport metadata or claiming completion', () => {
    render(
      <ToolResultCard
        toolName="cmd_private_namespace_create_record"
        result={{ approvalRequired: true, approvalPid: '01PRIVATE', message: 'Internal policy metadata' }}
        success
      />,
    );
    expect(screen.getByText('该操作需要审批')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('PRIVATE');
    expect(document.body).not.toHaveTextContent('Internal policy');
  });

  it('renders a business label after a transport-safe write tool completes', () => {
    render(
      <ToolResultCard
        toolName="cmd_crm_create_account"
        result={{ success: true, recordPid: '01ACCOUNT' }}
        success
      />,
    );

    const card = screen.getByRole('button');
    expect(card).toHaveTextContent('CRM › 新建客户');
    expect(card).not.toHaveTextContent('cmd_crm_create_account');
  });

  it('localizes query result counts', () => {
    render(
      <ToolResultCard
        toolName="nq__crm__active_accounts"
        result={{ records: [{ name: '示例客户' }] }}
        success
      />,
    );

    expect(screen.getByRole('button')).toHaveTextContent('1 条记录');
  });
});
