import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TenantMemberAccountImportDialog } from '../TenantMemberAccountImportDialog';

const previewData = {
  totalRows: 1,
  validCount: 1,
  errorCount: 0,
  rows: [
    {
      rowNumber: 3,
      name: '王佳霞',
      userName: '王佳霞',
      mobile: '13800000000',
      employeeCode: 'EMP-WJX',
      departmentCode: 'DEPT-SALES',
      positionCode: 'POS-SALES',
      action: 'CREATE_EMPLOYEE' as const,
      errors: [],
    },
  ],
};

const importData = {
  total: 1,
  accounts: [
    {
      userPid: 'usr_wjx',
      memberPid: 'mem_wjx',
      employeePid: 'emp_wjx',
      name: '王佳霞',
      userName: '王佳霞',
      initialPassword: 'jjzz@1234',
      employeeCode: 'EMP-WJX',
      departmentCode: 'DEPT-SALES',
      positionCode: 'POS-SALES',
      organizationAction: 'CREATED' as const,
      assignedRoles: [],
      mustChangePassword: false,
    },
  ],
};

describe('TenantMemberAccountImportDialog', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('previews the workbook before committing and shows one-time credentials', async () => {
    const fetchMock = vi.mocked(global.fetch);
    fetchMock
      .mockResolvedValueOnce(jsonResponse(previewData))
      .mockResolvedValueOnce(jsonResponse(importData));
    const onImported = vi.fn();

    render(
      <TenantMemberAccountImportDialog
        open
        token="access-token"
        onClose={vi.fn()}
        onImported={onImported}
      />,
    );

    uploadWorkbook();
    fireEvent.click(screen.getByTestId('member-import-preview'));

    await waitFor(() =>
      expect(screen.getByTestId('member-import-preview-result')).toBeInTheDocument(),
    );
    expect(screen.getAllByText('王佳霞')).toHaveLength(2);
    expect(screen.getByText('EMP-WJX')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/admin/users/employee-accounts/import/preview',
      expect.objectContaining({
        method: 'POST',
        headers: { Authorization: 'Bearer access-token' },
        body: expect.any(FormData),
      }),
    );

    fireEvent.click(screen.getByTestId('member-import-confirm'));

    await waitFor(() => expect(screen.getByTestId('member-import-result')).toBeInTheDocument());
    expect(screen.getByText('jjzz@1234')).toBeInTheDocument();
    expect(screen.getByTestId('member-import-credential-row')).toHaveTextContent('王佳霞');
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/admin/users/employee-accounts/import',
      expect.objectContaining({ method: 'POST', body: expect.any(FormData) }),
    );
    expect(onImported).toHaveBeenCalledOnce();
  });

  it('blocks commit when preview contains row errors', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce(
      jsonResponse({
        ...previewData,
        validCount: 0,
        errorCount: 1,
        rows: [
          {
            ...previewData.rows[0],
            action: 'ERROR',
            errors: ['Unknown department code: DEPT-MISSING'],
          },
        ],
      }),
    );

    render(<TenantMemberAccountImportDialog open onClose={vi.fn()} onImported={vi.fn()} />);
    uploadWorkbook();
    fireEvent.click(screen.getByTestId('member-import-preview'));

    await waitFor(() =>
      expect(screen.getByText('Unknown department code: DEPT-MISSING')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('member-import-confirm')).toBeDisabled();
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('rejects a non-xlsx template response instead of downloading an error page', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'text/html;charset=utf-8' }),
      arrayBuffer: async () => new TextEncoder().encode('<html>login</html>').buffer,
    } as Response);
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL');

    render(<TenantMemberAccountImportDialog open onClose={vi.fn()} onImported={vi.fn()} />);
    fireEvent.click(screen.getByTestId('member-import-download-template'));

    await waitFor(() => expect(screen.getByTestId('member-import-error')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The server did not return a valid Excel template.',
    );
    expect(createObjectUrl).not.toHaveBeenCalled();
  });
});

function uploadWorkbook() {
  const input = screen.getByTestId('member-import-file-input') as HTMLInputElement;
  const file = new File(['PK\u0003\u0004'], 'employee-accounts.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  fireEvent.change(input, { target: { files: [file] } });
}

function jsonResponse(data: unknown): Response {
  return {
    ok: true,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => ({ code: '0', data }),
  } as Response;
}
