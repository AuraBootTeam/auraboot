import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { CapabilityGroup } from '../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));
vi.mock('../capabilityService', () => ({
  capabilityService: { getForRole: vi.fn(), applySelection: vi.fn() },
}));

import { capabilityService } from '../capabilityService';
import CapabilityRoleEditor from '../CapabilityRoleEditor';

function cap(code: string, label: string, granted: boolean) {
  return { code, group: '客户管理', label, sensitive: false, includes: [], granted, conventionDerived: false };
}
const groups: CapabilityGroup[] = [
  { group: '客户管理', capabilities: [cap('crm.cap.account', '维护客户资料', true), cap('crm.cap.lead', '维护线索', false)] },
];

describe('CapabilityRoleEditor', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the role view, seeds selection from granted, and disables Save until dirty', async () => {
    (capabilityService.getForRole as ReturnType<typeof vi.fn>).mockResolvedValue(groups);
    render(<CapabilityRoleEditor roleId="5" />);

    await waitFor(() => screen.getByTestId('capability-role-editor'));
    expect(capabilityService.getForRole).toHaveBeenCalledWith('5');
    expect((screen.getByTestId('capability-checkbox-crm.cap.account') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-crm.cap.lead') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables Save after a toggle and persists the selection via applySelection', async () => {
    (capabilityService.getForRole as ReturnType<typeof vi.fn>).mockResolvedValue(groups);
    (capabilityService.applySelection as ReturnType<typeof vi.fn>).mockResolvedValue(groups);
    render(<CapabilityRoleEditor roleId="5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-checkbox-crm.cap.lead')); // select lead -> dirty
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(screen.getByTestId('capability-save'));
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('5', ['crm.cap.account', 'crm.cap.lead']),
    );
  });
});
