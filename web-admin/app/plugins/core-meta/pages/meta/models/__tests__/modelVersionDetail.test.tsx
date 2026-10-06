import type { ReactNode } from 'react';
import type { MetaModelDTO } from '~/types/model';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  detail: vi.fn<(code: string, version: number) => Promise<MetaModelDTO>>(),
  toast: vi.fn(),
  loaderData: {
    model: { pid: 'model-pid', code: 'test_model', displayName: 'Current model', version: 2, status: 'draft', sourceType: 'physical' },
    fields: [], permissions: [], pages: [],
    versions: [
      { version: 1, isCurrent: false, status: 'published', createdAt: '2026-10-02T00:00:00Z' },
      { version: 2, isCurrent: true, status: 'draft', createdAt: '2026-10-03T00:00:00Z' },
    ],
  },
}));
vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({ pid: mocks.loaderData.model.pid }),
  useLocation: () => ({ hash: '#versions' }),
  useLoaderData: () => mocks.loaderData,
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));
vi.mock('~/shared/services/modelService', () => ({ modelService: { getVersionDetail: mocks.detail } }));
vi.mock('~/shared/services/permissionService', () => ({ permissionService: {} }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showSuccessToast: mocks.toast, showErrorToast: mocks.toast }) }));
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (text: string | Record<string, string>) => typeof text === 'string' ? text : text['zh-CN'] }));
vi.mock('~/ui/PermissionGuard', () => ({ PermissionGuard: ({ children }: { children: ReactNode }) => <>{children}</> }));
vi.mock('~/ui/meta/CrudTemplateWizard', () => ({ CrudTemplateWizard: () => null }));
vi.mock('~/ui/meta/RuntimeVerification', () => ({ RuntimeVerification: () => null }));
vi.mock('~/ui/meta/FieldListManager', () => ({ FieldListManager: () => null }));
vi.mock('~/ui/meta/FieldConfigDialog', () => ({ FieldConfigDialog: () => null }));
vi.mock('~/ui/meta/DictConfigDialog', () => ({ DictConfigDialog: () => null }));
vi.mock('~/shared/components/SourceTypeBadge', () => ({ SourceTypeBadge: () => null }));

import ModelDetailPage from '../$pid';

function versionDetail(version: number, name = `Historical model ${version}`): MetaModelDTO {
  return { id: version, pid: `version-${version}`, code: mocks.loaderData.model.code, displayName: name,
    description: 'Historical business description', modelType: 'entity', status: 'published', version, isCurrent: false } as MetaModelDTO;
}
function pendingDetail() {
  let resolve!: (value: MetaModelDTO) => void;
  const promise = new Promise<MetaModelDTO>(done => { resolve = done; });
  return { promise, resolve };
}

describe('model version detail in the existing model page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.loaderData.model.code = 'test_model';
    mocks.detail.mockResolvedValue(versionDetail(1));
  });

  it('reads the exact historical model version and renders its persisted summary instead of a development toast', async () => {
    render(<ModelDetailPage />);
    fireEvent.click(screen.getByTestId('model-version-view-1'));
    expect(mocks.detail).toHaveBeenCalledWith('test_model', 1);
    const detail = await screen.findByTestId('model-version-detail');
    expect(detail).toHaveAttribute('data-version', '1');
    expect(detail).toHaveTextContent('Historical model 1');
    expect(detail).toHaveTextContent('Historical business description');
    expect(mocks.toast).not.toHaveBeenCalled();
  });

  it('shows a read failure without retaining an earlier version under the failed selection', async () => {
    render(<ModelDetailPage />);
    fireEvent.click(screen.getByTestId('model-version-view-1'));
    await screen.findByTestId('model-version-detail');
    mocks.detail.mockRejectedValueOnce(new Error('403 Forbidden'));
    fireEvent.click(screen.getByTestId('model-version-view-2'));
    expect(await screen.findByRole('alert')).toHaveTextContent('无法读取版本详情');
    expect(screen.queryByTestId('model-version-detail')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-version-view-2')).toBeEnabled();
  });

  it('does not let a late earlier response replace the latest selected version', async () => {
    const first = pendingDetail();
    mocks.detail.mockReturnValueOnce(first.promise).mockResolvedValueOnce(versionDetail(2));
    render(<ModelDetailPage />);
    fireEvent.click(screen.getByTestId('model-version-view-1'));
    expect(screen.getByTestId('model-version-view-1')).toBeDisabled();
    fireEvent.click(screen.getByTestId('model-version-view-2'));
    await waitFor(() => expect(screen.getByTestId('model-version-detail')).toHaveAttribute('data-version', '2'));
    await act(async () => { first.resolve(versionDetail(1)); });
    expect(screen.getByTestId('model-version-detail')).toHaveTextContent('Historical model 2');
  });

  it('invalidates a pending read when the route changes to another model', async () => {
    const first = pendingDetail();
    mocks.detail.mockReturnValueOnce(first.promise);
    const page = render(<ModelDetailPage />);
    fireEvent.click(screen.getByTestId('model-version-view-1'));
    expect(mocks.detail).toHaveBeenCalledWith('test_model', 1);
    mocks.loaderData.model.code = 'another_model';
    page.rerender(<ModelDetailPage />);
    await act(async () => { first.resolve(versionDetail(1, 'Old model response')); });
    expect(screen.queryByTestId('model-version-detail')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-version-view-1')).toBeEnabled();
  });
});
