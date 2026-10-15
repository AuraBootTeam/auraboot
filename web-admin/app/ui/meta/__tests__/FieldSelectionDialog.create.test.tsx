import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { FieldSelectionDialog } from '../FieldSelectionDialog';
import { fieldLibraryService } from '~/shared/services/fieldLibraryService';
import { modelService } from '~/shared/services/modelService';
const text = (value: { en: string }) => value.en;
const ensureLoaded = vi.fn();
const getEnumOptions = () => [{ value: 'string', label: 'String' }];
vi.mock('~/utils/i18n', () => ({ useSmartText: () => text }));
vi.mock('~/contexts/DslRegistryContext', () => ({
  useDslRegistry: () => ({ ensureLoaded, getEnumOptions }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast: vi.fn() }),
}));
vi.mock('~/framework/smart/components/formula/FormulaEditor', () => ({
  FormulaEditor: () => null,
}));
vi.mock('~/ui/PermissionGuard', () => ({
  PermissionGuard: ({ children }: { children: ReactNode }) => children,
}));
vi.mock('../FieldPreviewPanel', () => ({ FieldPreviewPanel: () => null }));
vi.mock('../FieldBindingConfigForm', () => ({ FieldBindingConfigForm: () => null }));
vi.mock('~/shared/services/fieldLibraryService', () => ({
  fieldLibraryService: {
    getFieldRecommendations: vi.fn(async () => []),
    searchFields: vi.fn(async () => ({ records: [] })),
    checkFieldCodeUnique: vi.fn(),
    createField: vi.fn(),
  },
}));
vi.mock('~/shared/services/modelService', () => ({ modelService: { bindFieldToModel: vi.fn() } }));
function setup() {
  const onClose = vi.fn();
  const onFieldBound = vi.fn();
  render(
    <FieldSelectionDialog
      isOpen
      modelPid="model-one"
      modelCode="launch_followup"
      onClose={onClose}
      onFieldBound={onFieldBound}
    />,
  );
  fireEvent.click(screen.getByTestId('field-selection-tab-create'));
  fireEvent.change(screen.getByTestId('field-create-display-name'), {
    target: { value: 'Customer name' },
  });
  fireEvent.change(screen.getByPlaceholderText('例如: user_name, email, age'), {
    target: { value: 'customer_name' },
  });
  return { onClose, onFieldBound };
}
beforeEach(() => vi.clearAllMocks());
describe('new field creation action', () => {
  it('rejects an existing code before creating a new version or binding it', async () => {
    vi.mocked(fieldLibraryService.checkFieldCodeUnique).mockResolvedValue(false);
    const { onClose } = setup();
    fireEvent.click(screen.getByTestId('field-selection-create-bind'));
    expect(await screen.findByTestId('field-create-duplicate')).toHaveTextContent('already exists');
    expect(fieldLibraryService.createField).not.toHaveBeenCalled();
    expect(modelService.bindFieldToModel).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
  it('preserves the authored label in the create payload and binds only the returned field', async () => {
    vi.mocked(fieldLibraryService.checkFieldCodeUnique).mockResolvedValue(true);
    vi.mocked(fieldLibraryService.createField).mockResolvedValue({ pid: 'field-one' } as any);
    vi.mocked(modelService.bindFieldToModel).mockResolvedValue({} as any);
    const { onClose, onFieldBound } = setup();
    fireEvent.click(screen.getByTestId('field-selection-create-bind'));
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    expect(fieldLibraryService.createField).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'customer_name', uiSchema: { label: 'Customer name' } }),
    );
    expect(modelService.bindFieldToModel).toHaveBeenCalledWith(
      'model-one',
      expect.objectContaining({ fieldPid: 'field-one' }),
    );
    expect(onFieldBound).toHaveBeenCalledOnce();
  });
  it('cancels without a uniqueness, creation or binding side effect', () => {
    const { onClose } = setup();
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(fieldLibraryService.checkFieldCodeUnique).not.toHaveBeenCalled();
    expect(fieldLibraryService.createField).not.toHaveBeenCalled();
    expect(modelService.bindFieldToModel).not.toHaveBeenCalled();
  });
});
