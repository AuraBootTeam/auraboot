import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AddMemberDialog from '../AddMemberDialog';
import { permissionService } from '~/shared/services/permissionService';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/hooks/useFormSubmit', () => ({ useFormSubmit: () => ({ handleSubmitResult: vi.fn() }) }));
vi.mock('~/framework/extensions/use-contribution', () => ({
  useContributionRegistry: () => ({ getRenderer: () => undefined }),
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { getRoleMemberCandidates: vi.fn(), addRoleMembers: vi.fn() },
}));

describe('AddMemberDialog selection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(permissionService.getRoleMemberCandidates).mockResolvedValue([
      { memberPid: 'member-pid-42', userName: 'Candidate', email: 'candidate@example.test',
        departmentName: 'Sales', positionName: '', assignedAt: null, effectiveDate: null, expiryDate: null },
    ]);
    vi.mocked(permissionService.addRoleMembers).mockResolvedValue(undefined);
  });

  it('selects once through the checkbox and submits the public member PID', async () => {
    const onSuccess = vi.fn();
    const onClose = vi.fn();
    render(<AddMemberDialog open rolePid="role-pid" existingMemberPids={[]}
      onSuccess={onSuccess} onClose={onClose} />);
    const row = (await screen.findByTestId('candidate-row-member-pid-42')) as HTMLElement;
    const checkbox = row.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();
    expect(screen.getByTestId('add-member-confirm')).toBeEnabled();
    fireEvent.click(screen.getByTestId('add-member-confirm'));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce());
    expect(permissionService.addRoleMembers).toHaveBeenCalledExactlyOnceWith(
      'role-pid', ['member-pid-42'], undefined,
    );
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('keeps row selection and checkbox deselection in sync without submitting', async () => {
    render(<AddMemberDialog open rolePid="role-pid" existingMemberPids={[]}
      onSuccess={vi.fn()} onClose={vi.fn()} />);
    const row = (await screen.findByTestId('candidate-row-member-pid-42')) as HTMLElement;
    const checkbox = row.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    fireEvent.click(screen.getByText('Candidate'));
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
    expect(screen.getByTestId('add-member-confirm')).toBeDisabled();
    expect(permissionService.addRoleMembers).not.toHaveBeenCalled();
  });
  it('keeps a failed candidate read distinct from a genuine empty list and permits an explicit retry', async () => {
    vi.mocked(permissionService.getRoleMemberCandidates).mockRejectedValueOnce(new Error('Forbidden'));
    render(<AddMemberDialog open rolePid="role-pid" existingMemberPids={[]} onSuccess={vi.fn()} onClose={vi.fn()} />);
    await screen.findByTestId('candidate-load-error');
    expect(screen.queryByTestId('candidate-empty-state')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name:'Retry'}));
    await screen.findByTestId('candidate-row-member-pid-42');
    expect(screen.queryByTestId('candidate-load-error')).not.toBeInTheDocument();
  });
  it('selects a nullable name using its public PID without displaying internal identifiers', async () => {
    vi.mocked(permissionService.getRoleMemberCandidates).mockResolvedValue([{memberPid:'nullable-pid',userName:null,
      email:null,departmentName:null,positionName:null,assignedAt:null,effectiveDate:null,expiryDate:null}]);
    render(<AddMemberDialog open rolePid="role-pid" existingMemberPids={[]} onSuccess={vi.fn()} onClose={vi.fn()} />);
    const row=await screen.findByTestId('candidate-row-nullable-pid');
    expect(row).toHaveTextContent('Member');expect(row).not.toHaveTextContent('nullable-pid');
    fireEvent.click(row);fireEvent.click(screen.getByTestId('add-member-confirm'));
    await waitFor(()=>expect(permissionService.addRoleMembers).toHaveBeenCalledExactlyOnceWith('role-pid',['nullable-pid'],undefined));
  });
});
