import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  PromotionDriftDecisionPanel,
  type PromotionDrift,
} from '../PromotionDriftDecisionPanel';

const drift: PromotionDrift = {
  unitPid: 'unit-1',
  resourceType: 'PAGE_SCHEMA',
  resourcePid: 'source-page',
  targetResourcePid: 'target-page',
  pageKey: 'orders',
  kind: 'TENANT_OVERRIDE',
  status: 'PENDING',
  fingerprint: 'a'.repeat(64),
  decision: null,
  applyReady: false,
  nextAction: 'SELECT_DECISION',
  activeReleasePid: 'release-1',
  channelVersion: 3,
  overridePid: 'override-1',
  sourceVersion: 2,
  targetVersion: 1,
  options: ['REBASE', 'BACKPORT', 'KEEP_OVERRIDE', 'OVERWRITE'],
};

describe('PromotionDriftDecisionPanel', () => {
  it('keeps apply blocked while exposing all explicit fates', () => {
    render(<PromotionDriftDecisionPanel drift={drift} onResolve={vi.fn()} />);

    expect(screen.getByText('目标环境存在现场配置漂移')).toBeInTheDocument();
    expect(screen.getByText('重放本地变更')).toBeInTheDocument();
    expect(screen.getByText('回迁源环境')).toBeInTheDocument();
    expect(screen.getByText('保留租户覆盖')).toBeInTheDocument();
    expect(screen.getByText('用发布版本覆盖')).toBeInTheDocument();
    expect(screen.getByText(/服务端会继续阻止 apply/)).toBeInTheDocument();
    expect(
      screen.getAllByRole('radio').every((radio: HTMLElement) => !radio.hasAttribute('checked')),
    ).toBe(true);
    expect(screen.getByTestId('promotion-drift-submit-unit-1')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('决策原因（必填）'), {
      target: { value: '只填原因还不算明确决策' },
    });
    expect(screen.getByTestId('promotion-drift-submit-unit-1')).toBeDisabled();
  });

  it('submits an exact fingerprint, explicit overwrite, and mandatory reason', () => {
    const onResolve = vi.fn();
    render(<PromotionDriftDecisionPanel drift={drift} onResolve={onResolve} />);

    fireEvent.click(screen.getByText('用发布版本覆盖'));
    fireEvent.change(screen.getByLabelText('决策原因（必填）'), {
      target: { value: '目标环境现场修复已被源版本正式吸收' },
    });
    fireEvent.click(screen.getByTestId('promotion-drift-submit-unit-1'));

    expect(onResolve).toHaveBeenCalledWith({
      expectedFingerprint: 'a'.repeat(64),
      decision: 'OVERWRITE',
      reason: '目标环境现场修复已被源版本正式吸收',
    });
  });
});
