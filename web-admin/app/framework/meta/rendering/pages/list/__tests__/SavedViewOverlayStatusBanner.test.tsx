import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SavedViewOverlayStatusBanner } from '../SavedViewOverlayStatusBanner';

const t = (_key: string, fallback: string) => fallback;

describe('SavedViewOverlayStatusBanner', () => {
  it('stays quiet for current and untracked legacy overlays', () => {
    const { rerender } = render(
      <SavedViewOverlayStatusBanner status="CURRENT" canRepair onRepair={vi.fn()} t={t} />,
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    rerender(
      <SavedViewOverlayStatusBanner status="UNTRACKED" canRepair onRepair={vi.fn()} t={t} />,
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explains compatible release rebasing without offering repair', () => {
    render(<SavedViewOverlayStatusBanner status="REBASED" canRepair onRepair={vi.fn()} t={t} />);

    expect(screen.getByTestId('saved-view-overlay-rebased')).toHaveTextContent(
      '个人视图已适配新版页面',
    );
    expect(screen.queryByTestId('saved-view-overlay-repair')).not.toBeInTheDocument();
  });

  it('shows degraded paths, mandatory recovery and a single scoped repair action', () => {
    const onRepair = vi.fn();
    render(
      <SavedViewOverlayStatusBanner
        status="STALE"
        reasonCodes={['FIELD_REMOVED', 'MANDATORY_ELEMENT_RESTORED']}
        stalePaths={['/columns/legacy', '/columns/mandatory/visible']}
        canRepair
        onRepair={onRepair}
        t={t}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('2项失效设置已忽略');
    expect(screen.getByRole('alert')).toHaveTextContent('必显项已自动恢复');
    fireEvent.click(screen.getByTestId('saved-view-overlay-repair'));
    expect(onRepair).toHaveBeenCalledTimes(1);
  });

  it('disables duplicate repair while saving and gives read-only users a next step', () => {
    const { rerender } = render(
      <SavedViewOverlayStatusBanner status="STALE" canRepair repairing onRepair={vi.fn()} t={t} />,
    );
    expect(screen.getByTestId('saved-view-overlay-repair')).toBeDisabled();

    rerender(
      <SavedViewOverlayStatusBanner status="STALE" canRepair={false} onRepair={vi.fn()} t={t} />,
    );
    expect(screen.queryByTestId('saved-view-overlay-repair')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('请联系视图管理员修复');
  });
});
