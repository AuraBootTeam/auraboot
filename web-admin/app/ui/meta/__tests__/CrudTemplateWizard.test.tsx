import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CrudTemplateWizard } from '../CrudTemplateWizard';

const { generate, navigate } = vi.hoisted(() => ({ generate: vi.fn(), navigate: vi.fn() }));
vi.mock('react-router', () => ({ useNavigate: () => navigate }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/shared/services/templateService', () => ({
  templateService: { generateCrudTemplate: generate },
}));
vi.mock('~/shared/designer', () => ({
  PropertyFieldRenderer: () => <span>Schema property</span>,
  DESIGNER_I18N: { unified: { inspectorLabels: {} } },
}));

const result = {
  generatedResources: {
    pages: [{ id: '1', pid: 'list-pid', pageName: '客户列表', kind: 'list', route: '/p/customer' }],
  },
};
function setup() {
  const onClose = vi.fn();
  const onComplete = vi.fn();
  render(
    <CrudTemplateWizard
      modelCode="customer"
      modelName="客户"
      fields={[]}
      onClose={onClose}
      onComplete={onComplete}
    />,
  );
  return { onClose, onComplete };
}
describe('zero-visual-config CRUD entry', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    generate.mockResolvedValue(result);
  });
  it('generates usable pages without visual parameters or implicit security grants', async () => {
    setup();
    expect(screen.queryByTestId('crud-form-presentation')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('crud-generate-submit'));
    await waitFor(() => expect(generate).toHaveBeenCalledOnce());
    const [modelCode, config] = generate.mock.calls[0];
    expect(modelCode).toBe('customer');
    expect(config).toMatchObject({
      generateList: true,
      generateForm: true,
      generateDetail: true,
      createMenu: false,
      createPermissions: false,
      assignRoles: false,
      openDesignerAfterGenerate: false,
    });
    expect(config).not.toHaveProperty('formPresentation');
    expect(config).not.toHaveProperty('presentation');
    await screen.findByTestId('crud-open-page');
    expect(navigate).not.toHaveBeenCalled();
  });
  it('opens the generated runtime directly while retaining the designer as optional', async () => {
    const callbacks = setup();
    fireEvent.click(screen.getByTestId('crud-generate-submit'));
    fireEvent.click(await screen.findByTestId('crud-open-page'));
    expect(navigate).toHaveBeenCalledWith('/p/customer');
    expect(callbacks.onComplete).toHaveBeenCalledOnce();
    expect(callbacks.onClose).toHaveBeenCalledOnce();
  });
  it('keeps schema-driven visual overrides in advanced options', () => {
    setup();
    fireEvent.click(screen.getByText('更多选项'));
    expect(screen.getByTestId('crud-form-presentation')).toBeVisible();
    expect(screen.getAllByText('Schema property').length).toBeGreaterThan(0);
  });
  it('shows generation failure and never navigates to a false success', async () => {
    generate.mockRejectedValueOnce(new Error('No write permission'));
    setup();
    fireEvent.click(screen.getByTestId('crud-generate-submit'));
    await screen.findByText('No write permission');
    expect(screen.queryByTestId('crud-open-page')).not.toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });
});
