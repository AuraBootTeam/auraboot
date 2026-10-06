import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ModelDetailPage from '../$pid';
import { modelDetailText } from '../modelDetailText';
import messages from '../modelDetail.i18n.json';

// The locale contract under test: every chrome string on the model detail page
// must come from the modelDetail catalog via useI18n().locale, while user data
// (model displayName, codes, raw enum values like `draft`) keeps its source values.
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

const navigate = vi.hoisted(() => vi.fn());
const loaderData = vi.hoisted(() => ({
  model: {
    pid: 'model-locale',
    code: 'leave_request',
    displayName: '请假申请',
    status: 'draft',
    description: '演示模型',
    sourceType: 'physical',
    namespace: 'core',
    env: 'prod',
    updatedAt: '2026-01-02T03:04:05Z',
    createdAt: '2026-01-01T00:00:00Z',
    updatedBy: 'tester',
    createdBy: 'tester',
    version: 3,
    modelType: 'entity',
    isCurrent: true,
  },
  fields: [],
  permissions: [],
  versions: [
    {
      version: 3,
      isCurrent: true,
      status: 'draft',
      versionNote: '',
      createdAt: '2026-01-01T00:00:00Z',
      createdBy: 'tester',
    },
    {
      version: 2,
      isCurrent: false,
      status: 'published',
      versionNote: 'init',
      createdAt: '2025-12-31T00:00:00Z',
      createdBy: 'tester',
    },
  ],
  pages: [],
}));

vi.mock('react-router', () => ({
  useNavigate: () => navigate,
  useParams: () => ({ pid: 'model-locale' }),
  useLoaderData: () => loaderData,
  useLocation: () => ({ hash: '', pathname: '/meta/models/model-locale', search: '' }),
  Link: ({ children, to }: { children: ReactNode; to: string }) => (
    <a href={to}>{children}</a>
  ),
}));

const confirmDialog = vi.hoisted(() => vi.fn(async () => false));
const showSuccessToast = vi.hoisted(() => vi.fn());
const showErrorToast = vi.hoisted(() => vi.fn());

vi.mock('~/utils/confirmDialog', () => ({ confirmDialog }));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast, showErrorToast }),
}));

vi.mock('~/shared/services/modelService', () => ({
  modelService: {
    findByPid: vi.fn(),
    getModelFields: vi.fn(async () => []),
    updateFieldsOrder: vi.fn(),
    updateFieldBinding: vi.fn(),
    bindDictToField: vi.fn(),
    unbindDictFromField: vi.fn(),
    delete: vi.fn(),
    refreshCache: vi.fn(),
    previewPublishDDL: vi.fn(),
    replayPublishImpact: vi.fn(),
    publish: vi.fn(),
    unpublish: vi.fn(),
    getVersionHistory: vi.fn(async () => loaderData.versions),
    getRelatedPages: vi.fn(async () => []),
    rollbackToVersion: vi.fn(),
  },
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { getModelPermissions: vi.fn(async () => []) },
}));

vi.mock('~/ui/meta/CrudTemplateWizard', () => ({ CrudTemplateWizard: () => null }));
vi.mock('~/ui/meta/RuntimeVerification', () => ({ RuntimeVerification: () => null }));
vi.mock('~/ui/meta/FieldListManager', () => ({ FieldListManager: () => null }));
vi.mock('~/ui/meta/FieldConfigDialog', () => ({ FieldConfigDialog: () => null }));
vi.mock('~/ui/meta/DictConfigDialog', () => ({ DictConfigDialog: () => null }));
vi.mock('~/shared/components/SourceTypeBadge', () => ({ SourceTypeBadge: () => null }));
vi.mock('../ModelPublishReplayResultCard', () => ({
  ModelPublishReplayResultCard: () => null,
}));

function renderPage() {
  return render(<ModelDetailPage />);
}

describe('ModelDetailPage locale contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmDialog.mockResolvedValue(false);
  });

  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('keeps the default zh-CN chrome byte-identical to the previous literals', () => {
    language.locale = 'zh-CN';
    renderPage();

    // Header status + meta
    expect(screen.getByText('草稿')).toBeInTheDocument();
    expect(screen.getByText('模型编码:', { exact: false })).toBeInTheDocument();
    // Stat cards
    expect(screen.getByText('字段')).toBeInTheDocument();
    expect(screen.getByText('页面')).toBeInTheDocument();
    expect(screen.getByText('版本')).toBeInTheDocument();
    expect(screen.getByText('最近更新')).toBeInTheDocument();
    // Header actions
    expect(screen.getByTestId('model-primary-page-action')).toHaveTextContent('生成基础 CRUD');
    expect(screen.getByText('编辑模型')).toBeInTheDocument();
    expect(screen.getByText('更多')).toBeInTheDocument();
    expect(screen.getByText('刷新缓存')).toBeInTheDocument();
    expect(screen.getByText('删除模型')).toBeInTheDocument();
    // Tab navigation
    expect(screen.getByText('概览')).toBeInTheDocument();
    expect(screen.getByText('字段 (0)')).toBeInTheDocument();
    expect(screen.getByText('页面 (0)')).toBeInTheDocument();
    expect(screen.getByText('版本 (2)')).toBeInTheDocument();
    expect(screen.getByText('运行时验证')).toBeInTheDocument();
    expect(screen.getByText('高级')).toBeInTheDocument();
    // Overview tab
    expect(screen.getByText('模型信息')).toBeInTheDocument();
    expect(screen.getByText('模型编码')).toBeInTheDocument();
    expect(screen.getByText('显示名称')).toBeInTheDocument();
    expect(screen.getByText('状态')).toBeInTheDocument();
    expect(screen.getByText('来源')).toBeInTheDocument();
    expect(screen.getByText('命名空间')).toBeInTheDocument();
    expect(screen.getByText('环境')).toBeInTheDocument();
    expect(screen.getByText('描述')).toBeInTheDocument();
    expect(screen.getByText('更新时间')).toBeInTheDocument();
    expect(screen.getByText('更新人')).toBeInTheDocument();
    expect(screen.getByText('设计联动摘要')).toBeInTheDocument();
    expect(screen.getByText('页面覆盖情况')).toBeInTheDocument();
    expect(screen.getByText('列表页')).toBeInTheDocument();
    expect(screen.getAllByText('未创建')).toHaveLength(3);
    expect(screen.getByText('最近编辑页面')).toBeInTheDocument();
    expect(screen.getByText('暂无页面')).toBeInTheDocument();
    expect(screen.getByText('查看页面工作台')).toBeInTheDocument();
  });

  it('keeps the advanced tab zh-CN chrome byte-identical', () => {
    language.locale = 'zh-CN';
    renderPage();
    fireEvent.click(screen.getByText('高级'));

    expect(screen.getByText('权限点')).toBeInTheDocument();
    expect(screen.getByText('暂无权限点')).toBeInTheDocument();
    expect(screen.getByText('技术元数据')).toBeInTheDocument();
    expect(screen.getByText('模型类型')).toBeInTheDocument();
    expect(screen.getByText('实体')).toBeInTheDocument();
    expect(screen.getByText('是否当前版本')).toBeInTheDocument();
    expect(screen.getByText('是')).toBeInTheDocument();
    expect(screen.getByText('创建时间')).toBeInTheDocument();
    expect(screen.getByText('创建人')).toBeInTheDocument();
  });

  it('keeps the versions tab zh-CN chrome byte-identical and renders raw enum values', () => {
    language.locale = 'zh-CN';
    renderPage();
    fireEvent.click(screen.getByText('版本 (2)'));

    expect(screen.getByText('版本 3')).toBeInTheDocument();
    expect(screen.getByText('当前版本')).toBeInTheDocument();
    // Raw version.status enum values stay untranslated.
    expect(screen.getByText('draft')).toBeInTheDocument();
    expect(screen.getByText('published')).toBeInTheDocument();
    expect(screen.getByText('无说明')).toBeInTheDocument();
    expect(screen.getByText('init')).toBeInTheDocument();
    expect(screen.getAllByText('查看')).toHaveLength(2);
    expect(screen.getByText('回滚')).toBeInTheDocument();
  });

  it('keeps the pages workbench zh-CN chrome byte-identical', () => {
    language.locale = 'zh-CN';
    renderPage();
    fireEvent.click(screen.getByText('页面 (0)'));

    expect(screen.getByText('页面工作台')).toBeInTheDocument();
    expect(screen.getByText('先补齐页面，再直接进入设计器调整细节。')).toBeInTheDocument();
    expect(screen.getByText('还没有标准页面')).toBeInTheDocument();
    expect(screen.getByText('可以一键生成基础 CRUD 页面，或按页面类型逐个创建。')).toBeInTheDocument();
    expect(screen.getByText('一键生成 CRUD')).toBeInTheDocument();
    expect(screen.getByText('手动选择页面')).toBeInTheDocument();
    expect(screen.getByText('详情页')).toBeInTheDocument();
    expect(screen.getByText('表单页')).toBeInTheDocument();
    expect(screen.getAllByText('未创建')).toHaveLength(3);
    expect(screen.getAllByText('还没有此类标准页面')).toHaveLength(3);
    expect(screen.getAllByText('创建')).toHaveLength(3);
    expect(screen.getByText('其他页面')).toBeInTheDocument();
    expect(screen.getByText('打开 Page Schema 列表')).toBeInTheDocument();
    expect(screen.getByText('暂无其他自定义页面')).toBeInTheDocument();
  });

  it.each(['en', 'en-US', 'en-GB'])('localizes the chrome through the catalog for %s', (locale) => {
    language.locale = locale;
    renderPage();

    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('Model code:', { exact: false })).toBeInTheDocument();
    expect(screen.getByTestId('model-primary-page-action')).toHaveTextContent('Generate basic CRUD');
    expect(screen.getByText('Edit model')).toBeInTheDocument();
    expect(screen.getByText('Delete model')).toBeInTheDocument();
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getByText('Fields (0)')).toBeInTheDocument();
    expect(screen.getByText('Pages (0)')).toBeInTheDocument();
    expect(screen.getByText('Versions (2)')).toBeInTheDocument();
    expect(screen.getByText('Runtime verification')).toBeInTheDocument();
    expect(screen.getByText('Advanced')).toBeInTheDocument();
    expect(screen.getByText('Model information')).toBeInTheDocument();
    expect(screen.getByText('Design linkage summary')).toBeInTheDocument();
    expect(screen.getByText('Page coverage')).toBeInTheDocument();
    expect(screen.getByText('List page')).toBeInTheDocument();
    expect(screen.getAllByText('Not created')).toHaveLength(3);
    // The zh-CN chrome is gone once an English locale is active.
    expect(screen.queryByText('概览')).not.toBeInTheDocument();
    expect(screen.queryByText('生成基础 CRUD')).not.toBeInTheDocument();
    // User data keeps its source values regardless of locale.
    expect(screen.getAllByText('请假申请').length).toBeGreaterThan(0);
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering an empty label', () => {
    language.locale = 'fr-FR';
    renderPage();

    expect(screen.getByText('概览')).toBeInTheDocument();
    expect(screen.getAllByText('生成基础 CRUD').length).toBe(2);
    expect(screen.queryByText('Overview')).not.toBeInTheDocument();
  });

  it('switches chrome with locale on rerender while the active tab stays stable', () => {
    language.locale = 'zh-CN';
    const view = renderPage();

    fireEvent.click(screen.getByText('高级'));
    expect(screen.getByText('技术元数据')).toBeInTheDocument();

    language.locale = 'en-US';
    view.rerender(<ModelDetailPage />);

    expect(screen.getByText('Technical metadata')).toBeInTheDocument();
    expect(screen.getByText('Permission points')).toBeInTheDocument();
    expect(screen.queryByText('技术元数据')).not.toBeInTheDocument();
    expect(screen.queryByText('概览')).not.toBeInTheDocument();
  });

  it('interpolates confirm dialogs with user data in the active locale', async () => {
    language.locale = 'zh-CN';
    const view = renderPage();
    fireEvent.click(screen.getByText('版本 (2)'));
    fireEvent.click(screen.getByText('回滚'));

    expect(confirmDialog).toHaveBeenCalledWith(
      expect.objectContaining({ content: '确定要回滚到版本 2 吗？' }),
    );

    language.locale = 'en-US';
    view.rerender(<ModelDetailPage />);
    fireEvent.click(screen.getByText('Versions (2)'));
    fireEvent.click(screen.getByText('Roll back'));

    expect(confirmDialog).toHaveBeenLastCalledWith(
      expect.objectContaining({ content: 'Roll back to version 2?' }),
    );
  });
});

describe('modelDetail catalog contract', () => {
  it('pairs every catalog entry with both zh-CN and en text', () => {
    const entries = Object.entries(messages as Record<string, Record<string, string>>);
    expect(entries.length).toBeGreaterThan(120);
    for (const [key, entry] of entries) {
      expect(entry['zh-CN'], `zh-CN missing for ${key}`).toBeTruthy();
      expect(entry['en'], `en missing for ${key}`).toBeTruthy();
    }
  });

  it('resolves regional variants to en and unknown locales to zh-CN', () => {
    expect(modelDetailText('actionGenerateCrud', 'zh-CN')).toBe('生成基础 CRUD');
    expect(modelDetailText('actionGenerateCrud', 'en')).toBe('Generate basic CRUD');
    expect(modelDetailText('actionGenerateCrud', 'en-US')).toBe('Generate basic CRUD');
    expect(modelDetailText('actionGenerateCrud', 'en-GB')).toBe('Generate basic CRUD');
    expect(modelDetailText('actionGenerateCrud', 'fr-FR')).toBe('生成基础 CRUD');
  });

  it('interpolates params and keeps unknown tokens verbatim', () => {
    expect(modelDetailText('rollbackSuccess', 'zh-CN', { version: 3 })).toBe('回滚到版本 3 成功');
    expect(modelDetailText('rollbackSuccess', 'en-US', { version: 3 })).toBe(
      'Rolled back to version 3',
    );
    expect(modelDetailText('deleteModelConfirm', 'zh-CN', { name: '请假申请' })).toBe(
      '确定要删除模型 "请假申请" 吗？此操作不可恢复。',
    );
    expect(modelDetailText('fieldsPageImpactNotice', 'en-GB', { count: 2 })).toBe(
      'This model already has 2 related page(s); field changes may affect page configuration.',
    );
    // Missing params keep the raw token instead of rendering "undefined".
    expect(modelDetailText('rollbackSuccess', 'en-US')).toBe('Rolled back to version {version}');
  });

  // Counter-evidence: deleting a catalog entry (or ignoring the locale argument)
  // turns these fixed-key assertions red — '' from a missing entry never equals
  // the expected text, and a locale-blind resolver cannot return both sides.
  it('fails if an entry is removed or the locale is ignored', () => {
    expect(modelDetailText('tabOverview', 'zh-CN')).toBe('概览');
    expect(modelDetailText('tabOverview', 'en-US')).toBe('Overview');
    expect(modelDetailText('tabOverview', 'en-US')).not.toBe(
      modelDetailText('tabOverview', 'zh-CN'),
    );
    expect(modelDetailText('unbindFieldConfirm', 'zh-CN', { name: 'amount' })).toBe(
      '确定要从模型中移除字段 "amount" 吗？',
    );
    expect(modelDetailText('unbindFieldConfirm', 'en-US', { name: 'amount' })).toBe(
      'Remove field "amount" from the model?',
    );
  });
});
