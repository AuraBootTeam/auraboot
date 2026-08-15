import React, { useCallback, useEffect, useState } from 'react';
import { useSmartText } from '~/utils/i18n';

type ApiEnvelope<T> = {
  code: string;
  message?: string;
  desc?: string;
  data?: T;
};

export type EmployeeAccountPreviewRow = {
  rowNumber: number;
  name?: string | null;
  userName?: string | null;
  mobile?: string | null;
  employeeCode?: string | null;
  departmentCode?: string | null;
  positionCode?: string | null;
  action: 'CREATE_ACCOUNT' | 'CREATE_EMPLOYEE' | 'LINK_EXISTING_EMPLOYEE' | 'ERROR';
  errors: string[];
};

export type EmployeeAccountPreview = {
  totalRows: number;
  validCount: number;
  errorCount: number;
  rows: EmployeeAccountPreviewRow[];
};

export type EmployeeAccountCredential = {
  userPid: string;
  memberPid?: string | null;
  employeePid?: string | null;
  name: string;
  userName: string;
  mobile?: string | null;
  employeeCode?: string | null;
  departmentCode?: string | null;
  positionCode?: string | null;
  organizationAction?: 'NONE' | 'CREATED' | 'LINKED';
  initialPassword: string;
  assignedRoles: string[];
  mustChangePassword: boolean;
};

export type EmployeeAccountImportResult = {
  total: number;
  accounts: EmployeeAccountCredential[];
};

export interface TenantMemberAccountImportDialogProps {
  open: boolean;
  token?: string | null;
  onClose: () => void;
  onImported: () => void | Promise<void>;
}

const XLSX_CONTENT_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

export function TenantMemberAccountImportDialog({
  open,
  token,
  onClose,
  onImported,
}: TenantMemberAccountImportDialogProps) {
  const st = useSmartText();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<EmployeeAccountPreview | null>(null);
  const [result, setResult] = useState<EmployeeAccountImportResult | null>(null);
  const [busy, setBusy] = useState<'download' | 'preview' | 'commit' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setFile(null);
    setPreview(null);
    setResult(null);
    setBusy(null);
    setError(null);
  }, []);

  useEffect(() => {
    if (!open) reset();
  }, [open, reset]);

  const authorizationHeaders = token ? { Authorization: `Bearer ${token}` } : undefined;

  const downloadTemplate = useCallback(async () => {
    setBusy('download');
    setError(null);
    try {
      const response = await fetch('/api/admin/users/employee-accounts/import/template', {
        headers: authorizationHeaders,
      });
      const bytes = new Uint8Array(await response.arrayBuffer());
      const contentType = response.headers.get('content-type') ?? '';
      if (
        !response.ok ||
        !contentType.includes(XLSX_CONTENT_TYPE) ||
        bytes.length < 4 ||
        bytes[0] !== 0x50 ||
        bytes[1] !== 0x4b
      ) {
        throw new Error(
          st(
            '$i18n:memberImport.error.invalidTemplate',
            'The server did not return a valid Excel template.',
          ),
        );
      }
      const blob = new Blob([bytes], { type: XLSX_CONTENT_TYPE });
      const href = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = href;
      link.download = 'user-import-template.xlsx';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(href);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : st('$i18n:memberImport.error.download', 'Template download failed.'),
      );
    } finally {
      setBusy(null);
    }
  }, [authorizationHeaders, st]);

  const previewWorkbook = useCallback(async () => {
    if (!file) {
      setError(st('$i18n:memberImport.error.selectFile', 'Select an Excel file first.'));
      return;
    }
    setBusy('preview');
    setError(null);
    setPreview(null);
    setResult(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const response = await fetch('/api/admin/users/employee-accounts/import/preview', {
        method: 'POST',
        headers: authorizationHeaders,
        body: form,
      });
      const body = (await response
        .json()
        .catch(() => null)) as ApiEnvelope<EmployeeAccountPreview> | null;
      if (!response.ok || !body || body.code !== '0' || !body.data) {
        throw new Error(
          body?.message ||
            body?.desc ||
            st('$i18n:memberImport.error.preview', 'Workbook validation failed.'),
        );
      }
      setPreview(body.data);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : st('$i18n:memberImport.error.preview', 'Workbook validation failed.'),
      );
    } finally {
      setBusy(null);
    }
  }, [authorizationHeaders, file, st]);

  const commitImport = useCallback(async () => {
    if (!file || !preview || preview.errorCount > 0) return;
    setBusy('commit');
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const response = await fetch('/api/admin/users/employee-accounts/import', {
        method: 'POST',
        headers: authorizationHeaders,
        body: form,
      });
      const body = (await response
        .json()
        .catch(() => null)) as ApiEnvelope<EmployeeAccountImportResult> | null;
      if (!response.ok || !body || body.code !== '0' || !body.data) {
        throw new Error(
          body?.message ||
            body?.desc ||
            st('$i18n:memberImport.error.commit', 'Account import failed.'),
        );
      }
      setResult(body.data);
      await onImported();
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : st('$i18n:memberImport.error.commit', 'Account import failed.'),
      );
    } finally {
      setBusy(null);
    }
  }, [authorizationHeaders, file, onImported, preview, st]);

  const downloadCredentials = useCallback(() => {
    if (!result) return;
    const rows = [
      [
        st('$i18n:memberImport.field.name', 'Name'),
        st('$i18n:memberImport.field.loginName', 'Login name'),
        st('$i18n:memberImport.field.initialPassword', 'Initial password'),
        st('$i18n:memberImport.field.employeeCode', 'Employee code'),
        st('$i18n:memberImport.field.departmentCode', 'Department code'),
        st('$i18n:memberImport.field.positionCode', 'Position code'),
      ],
      ...result.accounts.map((account) => [
        account.name,
        account.userName,
        account.initialPassword,
        account.employeeCode ?? '',
        account.departmentCode ?? '',
        account.positionCode ?? '',
      ]),
    ];
    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(',')).join('\r\n')}`;
    const href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = href;
    link.download = 'employee-account-credentials.csv';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(href);
  }, [result, st]);

  if (!open) return null;

  const close = () => {
    reset();
    onClose();
  };
  const actionLabel = (action: EmployeeAccountPreviewRow['action']) => {
    const definitions: Record<EmployeeAccountPreviewRow['action'], [string, string]> = {
      CREATE_ACCOUNT: ['memberImport.action.createAccount', 'Create account'],
      CREATE_EMPLOYEE: ['memberImport.action.createEmployee', 'Create account and employee'],
      LINK_EXISTING_EMPLOYEE: ['memberImport.action.linkEmployee', 'Link existing employee'],
      ERROR: ['memberImport.action.error', 'Error'],
    };
    const [key, fallback] = definitions[action];
    return st(`$i18n:${key}`, fallback);
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="member-account-import-title"
      data-testid="member-import-dialog"
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/45 p-4"
    >
      <div className="rounded-card border-border bg-panel max-h-[90vh] w-full max-w-5xl overflow-y-auto border shadow-xl">
        <div className="border-border bg-panel sticky top-0 z-10 flex items-start justify-between gap-4 border-b px-5 py-4">
          <div>
            <h2 id="member-account-import-title" className="text-text text-base font-semibold">
              {st('$i18n:memberImport.title', 'Import employee accounts')}
            </h2>
            <p className="text-text-2 mt-1 text-sm">
              {st(
                '$i18n:memberImport.subtitle',
                'Create active accounts without email. Roles remain unassigned for manual administration.',
              )}
            </p>
          </div>
          <button
            type="button"
            onClick={close}
            className="text-text-3 hover:bg-hover hover:text-text rounded-control px-2 py-1 text-sm"
            aria-label={st('$i18n:action.close', 'Close')}
          >
            ×
          </button>
        </div>

        <div className="space-y-5 px-5 py-4">
          {!result && (
            <section className="border-border bg-subtle rounded-control border p-4">
              <div className="flex flex-col gap-4 lg:flex-row lg:items-end">
                <div className="min-w-0 flex-1">
                  <p className="text-text text-sm font-medium">
                    {st('$i18n:memberImport.file.title', 'Employee account workbook')}
                  </p>
                  <p className="text-text-2 mt-1 text-xs">
                    {st(
                      '$i18n:memberImport.file.help',
                      'Columns: Name, login name, mobile, employee code, department code, and position code.',
                    )}
                  </p>
                  <input
                    data-testid="member-import-file-input"
                    type="file"
                    accept=".xlsx"
                    onChange={(event) => {
                      const selected = event.target.files?.[0] ?? null;
                      setFile(selected);
                      setPreview(null);
                      setResult(null);
                      setError(null);
                    }}
                    className="rounded-control border-border-strong text-text-2 file:rounded-control file:bg-accent hover:file:bg-accent-hover mt-3 block w-full border px-3 py-2 text-sm file:mr-3 file:border-0 file:px-3 file:py-2 file:text-sm file:font-medium file:text-white"
                  />
                  {file && <p className="text-text-2 mt-2 text-xs">{file.name}</p>}
                </div>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    data-testid="member-import-download-template"
                    disabled={busy !== null}
                    onClick={() => void downloadTemplate()}
                    className="rounded-control border-border-strong text-text hover:bg-hover border px-3 py-2 text-sm font-medium disabled:opacity-50"
                  >
                    {busy === 'download'
                      ? st('$i18n:memberImport.template.downloading', 'Downloading…')
                      : st('$i18n:memberImport.template.download', 'Download template')}
                  </button>
                  <button
                    type="button"
                    data-testid="member-import-preview"
                    disabled={!file || busy !== null}
                    onClick={() => void previewWorkbook()}
                    className="rounded-control bg-accent hover:bg-accent-hover px-3 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {busy === 'preview'
                      ? st('$i18n:memberImport.preview.running', 'Validating…')
                      : st('$i18n:memberImport.preview.action', 'Validate workbook')}
                  </button>
                </div>
              </div>
            </section>
          )}

          {error && (
            <div
              role="alert"
              data-testid="member-import-error"
              className="rounded-control bg-status-red-bg text-status-red border border-red-200 px-3 py-2 text-sm"
            >
              {error}
            </div>
          )}

          {preview && !result && (
            <section data-testid="member-import-preview-result" className="space-y-3">
              <div className="flex flex-wrap gap-2 text-sm">
                <span className="bg-subtle text-text rounded-full px-2.5 py-1 font-medium">
                  {st('$i18n:memberImport.preview.total', 'Total')}: {preview.totalRows}
                </span>
                <span className="bg-status-green-bg text-status-green rounded-full px-2.5 py-1 font-medium">
                  {st('$i18n:memberImport.preview.valid', 'Valid')}: {preview.validCount}
                </span>
                <span className="bg-status-red-bg text-status-red rounded-full px-2.5 py-1 font-medium">
                  {st('$i18n:memberImport.preview.errors', 'Errors')}: {preview.errorCount}
                </span>
              </div>
              <div className="border-border max-h-80 overflow-auto rounded-md border">
                <table className="min-w-full text-left text-sm">
                  <thead className="bg-subtle text-text-2 sticky top-0">
                    <tr>
                      <th className="px-3 py-2 font-medium">#</th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.name', 'Name')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.loginName', 'Login name')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.employeeCode', 'Employee code')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.departmentCode', 'Department code')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.positionCode', 'Position code')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.action', 'Action')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.issue', 'Issue')}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {preview.rows.map((row) => (
                      <tr key={`${row.rowNumber}:${row.userName ?? row.name ?? ''}`}>
                        <td className="px-3 py-2">{row.rowNumber}</td>
                        <td className="px-3 py-2">{row.name || '—'}</td>
                        <td className="px-3 py-2">{row.userName || '—'}</td>
                        <td className="px-3 py-2">{row.employeeCode || '—'}</td>
                        <td className="px-3 py-2">{row.departmentCode || '—'}</td>
                        <td className="px-3 py-2">{row.positionCode || '—'}</td>
                        <td className="px-3 py-2">{actionLabel(row.action)}</td>
                        <td className="text-status-red max-w-xs px-3 py-2">
                          {row.errors.join('; ') || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="flex justify-end">
                <button
                  type="button"
                  data-testid="member-import-confirm"
                  disabled={preview.errorCount > 0 || busy !== null}
                  onClick={() => void commitImport()}
                  className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {busy === 'commit'
                    ? st('$i18n:memberImport.commit.running', 'Creating accounts…')
                    : st('$i18n:memberImport.commit.action', 'Confirm import')}
                </button>
              </div>
            </section>
          )}

          {result && (
            <section data-testid="member-import-result" className="space-y-3">
              <div className="rounded-control border border-emerald-200 bg-emerald-50 px-4 py-3">
                <p className="font-medium text-emerald-900">
                  {st('$i18n:memberImport.result.title', 'Accounts created')}: {result.total}
                </p>
                <p className="mt-1 text-sm text-emerald-800">
                  {st(
                    '$i18n:memberImport.result.secretHint',
                    'Save these credentials now. Plaintext passwords are not available after this dialog closes.',
                  )}
                </p>
              </div>
              <div className="border-border max-h-80 overflow-auto rounded-md border">
                <table className="min-w-full text-left text-sm">
                  <thead className="bg-subtle text-text-2 sticky top-0">
                    <tr>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.name', 'Name')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.loginName', 'Login name')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.initialPassword', 'Initial password')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {st('$i18n:memberImport.field.employeeCode', 'Employee code')}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {result.accounts.map((account) => (
                      <tr key={account.userPid} data-testid="member-import-credential-row">
                        <td className="px-3 py-2">{account.name}</td>
                        <td className="px-3 py-2 font-mono">{account.userName}</td>
                        <td className="px-3 py-2 font-mono">{account.initialPassword}</td>
                        <td className="px-3 py-2">{account.employeeCode || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="flex justify-end">
                <button
                  type="button"
                  data-testid="member-import-download-credentials"
                  onClick={downloadCredentials}
                  className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white"
                >
                  {st('$i18n:memberImport.result.downloadCredentials', 'Download credentials')}
                </button>
              </div>
            </section>
          )}
        </div>

        <div className="border-border flex justify-end border-t px-5 py-3">
          <button
            type="button"
            onClick={close}
            className="rounded-control border-border-strong text-text hover:bg-hover border px-4 py-2 text-sm font-medium"
          >
            {st('$i18n:action.close', 'Close')}
          </button>
        </div>
      </div>
    </div>
  );
}

function csvCell(value: string): string {
  const escaped = value.replaceAll('"', '""');
  return /[",\r\n]/.test(escaped) ? `"${escaped}"` : escaped;
}
