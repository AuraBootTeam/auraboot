import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AuthoringValidationNotice } from '../AuthoringValidationNotice';
import type { AuthoringSession } from '../types';

describe('AuthoringValidationNotice', () => {
  it('shows exact server-owned issue locations without collapsing save and validation', () => {
    render(<AuthoringValidationNotice session={invalidSession()} />);

    expect(screen.getByText('当前 revision 有 2 个校验错误，尚未提交评审')).toBeInTheDocument();
    expect(screen.getByText(/草稿已保存，ChangeSet 仍保持可编辑/)).toBeInTheDocument();
    expect(screen.getByText('默认筛选必须是结构化条件')).toBeInTheDocument();
    expect(screen.getByText('block: table-1')).toBeInTheDocument();
    expect(screen.getByText('path: /props/defaultFilter')).toBeInTheDocument();
  });

  it('stays hidden when the current revision is not invalid', () => {
    render(
      <AuthoringValidationNotice
        session={{ ...invalidSession(), validationState: 'UNVALIDATED', validation: null }}
      />,
    );

    expect(screen.queryByTestId('authoring-validation-notice')).not.toBeInTheDocument();
  });

  it('bounds inline details while the professional workbench can show every issue', () => {
    const issues = Array.from({ length: 9 }, (_, index) => ({
      code: 'DEFAULT_FILTER_INVALID',
      severity: 'ERROR',
      changeItemPid: `item-${index}`,
      blockId: `table-${index}`,
      propertyPath: `/props/defaultFilter/${index}`,
      messageKey: 'authoring.validation.default-filter-invalid',
    }));
    const session = {
      ...invalidSession(),
      validation: { ...invalidSession().validation!, errorCount: issues.length, issues },
    };
    const inline = render(
      <AuthoringValidationNotice session={session} maxVisibleIssues={8} />,
    );

    expect(screen.getAllByRole('listitem')).toHaveLength(8);
    expect(screen.getByText('另有 1 个错误，请在专业工作台继续查看。')).toBeInTheDocument();

    inline.rerender(<AuthoringValidationNotice session={session} />);

    expect(screen.getAllByRole('listitem')).toHaveLength(9);
    expect(screen.queryByText(/请在专业工作台继续查看/)).not.toBeInTheDocument();
  });
});

function invalidSession(): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 2,
    riskLevel: 'L2',
    route: 'GUIDED_INLINE',
    publishPolicy: 'REQUIRED_REVIEW',
    validationState: 'INVALID',
    validation: {
      validationRunPid: 'validation-1',
      revision: 2,
      status: 'INVALID',
      errorCount: 2,
      issues: [
        {
          code: 'DEFAULT_FILTER_INVALID',
          severity: 'ERROR',
          changeItemPid: 'item-1',
          blockId: 'table-1',
          propertyPath: '/props/defaultFilter',
          messageKey: 'authoring.validation.default-filter-invalid',
        },
      ],
      validatedAt: '2026-08-09T12:00:00Z',
    },
    approvalState: 'PENDING',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: {},
    interactionContext: {},
    expiresAt: '2026-08-09T18:00:00Z',
  };
}
