import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import RoleFieldAccessGrid from '../RoleFieldAccessGrid';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars: unknown, fallback: string) => fallback }),
}));
vi.mock('../authorizationDraftService', () => ({
  authorizationDraftService: {
    fieldPolicyRoots: vi.fn().mockResolvedValue([{ model: 'bom', permissions: ['bom.manage'] }]),
  },
}));
const catalog = [
  {
    code: 'bom',
    label: 'BOM project',
    fields: [
      {
        code: 'remark',
        label: 'Engineering remark',
        access: 'inherit',
        additionalRestrictions: ['Existing shared policy'],
      },
    ],
  },
  {
    code: 'quote',
    label: 'Quote',
    fields: [{ code: 'cost', label: 'Cost', access: 'inherit', additionalRestrictions: [] }],
  },
];
describe('role field access grid', () => {
  it('stages a restriction for an authorized business root without calling a policy mutation', async () => {
    const onChange = vi.fn();
    render(
      <RoleFieldAccessGrid
        rolePid="a"
        catalog={catalog}
        changes={[]}
        models={new Set()}
        grantedCodes={new Set(['bom.manage'])}
        disabled={false}
        onChange={onChange}
      />,
    );
    await waitFor(() => expect(screen.getByTestId('role-field-access-remark')).toBeEnabled());
    expect(screen.getByText('Existing shared policy')).toBeVisible();
    fireEvent.change(screen.getByTestId('role-field-access-remark'), {
      target: { value: 'hidden' },
    });
    expect(onChange).toHaveBeenCalledWith([
      { modelCode: 'bom', fieldCode: 'remark', access: 'hidden' },
    ]);
  });
  it('keeps a staged change when the administrator switches business objects', async () => {
    render(
      <RoleFieldAccessGrid
        rolePid="a"
        catalog={catalog}
        changes={[{ modelCode: 'bom', fieldCode: 'remark', access: 'read_only' }]}
        models={new Set(['bom', 'quote'])}
        grantedCodes={new Set()}
        disabled={false}
        onChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByTestId('role-field-model'), { target: { value: 'quote' } });
    expect(screen.getByTestId('role-field-access-cost')).toBeVisible();
    fireEvent.change(screen.getByTestId('role-field-model'), { target: { value: 'bom' } });
    expect(screen.getByTestId('role-field-access-remark')).toHaveValue('read_only');
  });
  it('does not turn field controls into a grant for another business object', async () => {
    render(
      <RoleFieldAccessGrid
        rolePid="a"
        catalog={catalog}
        changes={[]}
        models={new Set(['bom'])}
        grantedCodes={new Set()}
        disabled={false}
        onChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByTestId('role-field-model'), { target: { value: 'quote' } });
    expect(screen.getByTestId('role-field-access-cost')).toBeDisabled();
    expect(screen.getByText(/This role has no business access/)).toBeVisible();
  });
});
