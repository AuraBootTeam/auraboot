/**
 * DecisionOps permission governance matrix (mockup 权限治理 / F7, docs/1.md §23.4, AGENTS §13):
 * role × capability grid for the `decision.*` / `decision.policy.*` permission model — view / test /
 * publish / approve / field. Controlled: toggling a cell emits the updated grant set. The capability
 * keys map to the registered permission codes the backend enforces.
 */

export type Capability = 'view' | 'test' | 'publish' | 'approve' | 'field';

export const CAPABILITIES: { key: Capability; label: string }[] = [
  { key: 'view', label: '查看' },
  { key: 'test', label: '测试' },
  { key: 'publish', label: '发布' },
  { key: 'approve', label: '审批' },
  { key: 'field', label: '字段权限' },
];

export interface RoleGrants {
  role: string;
  caps: Partial<Record<Capability, boolean>>;
}

export interface PermissionMatrixProps {
  value: RoleGrants[];
  onChange?: (next: RoleGrants[]) => void;
  readOnly?: boolean;
}

export function PermissionMatrix({ value, onChange, readOnly = false }: PermissionMatrixProps) {
  const toggle = (roleIdx: number, cap: Capability) => {
    if (readOnly || !onChange) return;
    const next = value.map((r, i) =>
      i === roleIdx ? { ...r, caps: { ...r.caps, [cap]: !r.caps[cap] } } : r);
    onChange(next);
  };

  return (
    <div data-testid="permission-matrix">
      <table className="pm-grid">
        <thead>
          <tr>
            <th>角色</th>
            {CAPABILITIES.map((c) => <th key={c.key} data-testid={`pm-col-${c.key}`}>{c.label}</th>)}
          </tr>
        </thead>
        <tbody>
          {value.map((row, ri) => (
            <tr key={row.role} data-testid={`pm-row-${row.role}`}>
              <td>{row.role}</td>
              {CAPABILITIES.map((c) => {
                const granted = !!row.caps[c.key];
                return (
                  <td key={c.key}>
                    <button
                      type="button"
                      aria-label={`${row.role}-${c.key}`}
                      aria-pressed={granted}
                      data-granted={granted}
                      disabled={readOnly}
                      onClick={() => toggle(ri, c.key)}
                    >{granted ? '✓' : '—'}</button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default PermissionMatrix;
