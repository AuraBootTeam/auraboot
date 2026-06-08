import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { PermissionMatrix, type RoleGrants } from '../PermissionMatrix';

const initial = (): RoleGrants[] => [
  { role: '流程管理员', caps: { view: true, test: true, publish: true, approve: true, field: true } },
  { role: '服务运营', caps: { view: true, test: true } },
  { role: '审计员', caps: { view: true } },
];

function Harness() {
  const [v, setV] = useState<RoleGrants[]>(initial());
  return (
    <>
      <PermissionMatrix value={v} onChange={setV} />
      <div data-testid="dump">{JSON.stringify(v)}</div>
    </>
  );
}

describe('PermissionMatrix', () => {
  it('renders roles × capabilities with granted cells', () => {
    render(<Harness />);
    expect(screen.getByTestId('pm-row-服务运营')).toBeInTheDocument();
    expect(screen.getByLabelText('流程管理员-publish')).toHaveAttribute('data-granted', 'true');
    expect(screen.getByLabelText('服务运营-publish')).toHaveAttribute('data-granted', 'false');
  });

  it('toggles a cell and emits the updated grants', () => {
    render(<Harness />);
    fireEvent.click(screen.getByLabelText('服务运营-publish'));
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '[]') as RoleGrants[];
    expect(dump.find((r) => r.role === '服务运营')?.caps.publish).toBe(true);
    expect(screen.getByLabelText('服务运营-publish')).toHaveAttribute('data-granted', 'true');
  });

  it('readOnly disables toggling', () => {
    const onChange = vi.fn();
    render(<PermissionMatrix value={initial()} onChange={onChange} readOnly />);
    const btn = screen.getByLabelText('审计员-view');
    expect(btn).toBeDisabled();
    fireEvent.click(btn);
    expect(onChange).not.toHaveBeenCalled();
  });
});
