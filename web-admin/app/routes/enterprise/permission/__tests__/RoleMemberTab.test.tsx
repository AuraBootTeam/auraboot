import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RoleMemberTab from '../RoleMemberTab';
import { permissionService } from '~/shared/services/permissionService';
vi.mock('~/contexts/I18nContext',()=>({useI18n:()=>({t:(_key:string,_vars:unknown,fallback:string)=>fallback})}));
vi.mock('~/contexts/ToastContext',()=>({useToastContext:()=>({showSuccessToast:vi.fn(),showErrorToast:vi.fn()})}));
vi.mock('~/shared/services/permissionService',()=>({permissionService:{getRoleMembers:vi.fn(),removeRoleMembers:vi.fn()}}));
vi.mock('../AddMemberDialog',()=>({default:()=>null}));
vi.mock('~/ui/ConfirmDialog',()=>({default:({open,content,onConfirm}:{open:boolean;content:string;onConfirm:()=>void})=>open?<div role="dialog">{content}<button onClick={onConfirm}>Confirm</button></div>:null}));
const member={memberPid:'member-public-pid',userName:null,email:null,departmentName:null,positionName:null,
  assignedAt:null,effectiveDate:null,expiryDate:null};
const page={records:[member],total:1,page:1,pageSize:20,totalPages:1,nextCursor:null};
describe('RoleMemberTab public identity and failure feedback',()=>{
  beforeEach(()=>{vi.clearAllMocks();vi.mocked(permissionService.getRoleMembers).mockResolvedValue(page);vi.mocked(permissionService.removeRoleMembers).mockResolvedValue();});
  it('renders nullable identity with a friendly label and removes the exact public member PID',async()=>{
    render(<RoleMemberTab rolePid="role-pid"/>);
    const row=await screen.findByTestId('role-member-row-member-public-pid');
    expect(row).toHaveTextContent('Member');expect(row).not.toHaveTextContent('null');expect(row).not.toHaveTextContent('member-public-pid');
    fireEvent.click(screen.getByTestId('role-member-remove-member-public-pid'));
    expect(screen.getByRole('dialog')).toHaveTextContent('"Member"');
    fireEvent.click(screen.getByRole('button',{name:'Confirm'}));
    await waitFor(()=>expect(permissionService.removeRoleMembers).toHaveBeenCalledExactlyOnceWith('role-pid',['member-public-pid']));
  });
  it('keeps both add entry points disabled for an empty read-only role',async()=>{
    vi.mocked(permissionService.getRoleMembers).mockResolvedValue({...page,records:[],total:0,totalPages:0});
    render(<RoleMemberTab rolePid="role-pid" readOnly/>);
    await screen.findByTestId('role-member-empty');
    expect(screen.getByTestId('role-member-add-btn')).toBeDisabled();
    expect(screen.getByTestId('role-member-empty-add-btn')).toBeDisabled();
  });
  it('shows a refused read as an error and retries explicitly without inventing an empty success',async()=>{
    vi.mocked(permissionService.getRoleMembers).mockRejectedValueOnce(new Error('Forbidden'));
    render(<RoleMemberTab rolePid="role-pid"/>);
    await screen.findByTestId('role-member-load-error');
    expect(screen.queryByTestId('role-member-empty')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'Retry'}));
    await screen.findByTestId('role-member-row-member-public-pid');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
