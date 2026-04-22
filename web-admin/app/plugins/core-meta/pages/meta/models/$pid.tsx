/**
 * Model详情页面
 *
 * 提供Model详细信息查看界面
 *
 * 功能特性:
 * - 5个Tab页（基本信息、字段、权限点、版本、页面）
 * - 操作按钮（编辑、删除、刷新缓存）
 * - 主页面预览
 * - CRUD向导集成
 * - 版本管理
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { useNavigate, useParams, useLoaderData, useLocation, Link } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { modelService, type RelatedPage } from '~/shared/services/modelService';
import { confirmDialog } from '~/utils/confirmDialog';
import { permissionService } from '~/shared/services/permissionService';
import { useToastContext } from '~/contexts/ToastContext';
import { CrudTemplateWizard } from '~/ui/meta/CrudTemplateWizard';
import { RuntimeVerification } from '~/ui/meta/RuntimeVerification';
import { FieldListManager } from '~/ui/meta/FieldListManager';
import { FieldConfigDialog, type FieldBindingConfig } from '~/ui/meta/FieldConfigDialog';
import { DictConfigDialog } from '~/ui/meta/DictConfigDialog';
import { SourceTypeBadge } from '~/shared/components/SourceTypeBadge';
import type { MetaModelDTO, ModelFieldBinding, Permission, ModelVersion } from '~/types/model';

/**
 * Check whether the given model is a virtual model (non-physical sourceType).
 */
function isVirtualModel(model: { sourceType?: string }): boolean {
  return !!model.sourceType && model.sourceType !== 'physical';
}

/**
 * Human-readable label for a capability flag key.
 */
function capabilityLabel(key: string): string {
  const LABELS: Record<string, string> = {
    list: '可列表',
    detail: '可详情',
    sort: '可排序',
    filter: '可过滤',
    create: '可新建',
    update: '可更新',
    delete: '可删除',
    export: '可导出',
    search: '可搜索',
    paginate: '可分页',
  };
  return LABELS[key] ?? key;
}

/**
 * Tab类型定义
 */
type TabType = 'overview' | 'fields' | 'pages' | 'versions' | 'runtime' | 'advanced';

type StandardPageKind = 'list' | 'detail' | 'form';

function formatDateLabel(value?: string): string {
  if (!value) return '-';
  const timestamp = Date.parse(value);
  if (Number.isNaN(timestamp)) return '-';
  return new Date(timestamp).toLocaleString();
}

function getPageStatus(page: RelatedPage | null | undefined): string {
  const status = page && typeof page.status === 'string' ? page.status.toLowerCase() : '';
  if (status === 'published') return '已发布';
  if (status === 'draft') return '草稿';
  if (status === 'archived') return '已归档';
  return page ? '未标记' : '未创建';
}

function getPageStatusClass(page: RelatedPage | null | undefined): string {
  const status = page && typeof page.status === 'string' ? page.status.toLowerCase() : '';
  if (status === 'published') return 'bg-green-100 text-green-800';
  if (status === 'draft') return 'bg-amber-100 text-amber-800';
  if (status === 'archived') return 'bg-gray-100 text-gray-700';
  return page ? 'bg-sky-100 text-sky-800' : 'bg-gray-100 text-gray-500';
}

function getRelatedPageTitle(page: RelatedPage | null | undefined): string {
  if (!page) return '暂无页面';
  const parseLocalizedString = (value: string): string | null => {
    const trimmed = value.trim();
    if (!trimmed) return null;
    if (!trimmed.startsWith('{')) return trimmed;
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object') {
        const localized = parsed as Record<string, unknown>;
        const preferred = localized['zh-CN'] ?? localized['en-US'] ?? Object.values(localized)[0];
        return typeof preferred === 'string' && preferred.trim() ? preferred : null;
      }
    } catch {
      return trimmed;
    }
    return null;
  };

  const pickDisplay = (value: unknown): string | null => {
    if (typeof value === 'string') {
      return parseLocalizedString(value);
    }
    if (value && typeof value === 'object') {
      const localized = value as Record<string, unknown>;
      const preferred = localized['zh-CN'] ?? localized['en-US'] ?? Object.values(localized)[0];
      if (typeof preferred === 'string') {
        return parseLocalizedString(preferred);
      }
    }
    return null;
  };

  return (
    pickDisplay(page.title) ||
    page.pageKey ||
    page.code ||
    page.name ||
    '未命名页面'
  );
}

function normalizePageKind(page: RelatedPage): StandardPageKind | 'custom' {
  const raw = String(page.kind || page.type || '').toLowerCase();
  if (raw.includes('list')) return 'list';
  if (raw.includes('detail') || raw.includes('view')) return 'detail';
  if (raw.includes('form') || raw.includes('edit') || raw.includes('new')) return 'form';
  return 'custom';
}

function resolvePageRoute(page: RelatedPage): string {
  if (typeof page.route === 'string' && page.route.startsWith('/')) {
    return page.route;
  }
  const pageKey = page.pageKey || page.code || page.name;
  if (pageKey) {
    return `/p/${pageKey}`;
  }
  return '/p/page_schema';
}

/**
 * Loader函数 - 加载Model数据
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Model PID is required', { status: 400 });
  }

  try {
    const model = await modelService.findByPid(pid, request);
    const fields = await modelService.getModelFields(pid, request);
    const permissions = await permissionService.getModelPermissions(model.code, request);
    const versions = await modelService.getVersionHistory(model.code, request);
    const pages = await modelService.getRelatedPages(pid, request);

    return { model, fields, permissions, versions, pages };
  } catch (error) {
    console.error('Failed to load model details:', error);
    throw new Response('Model not found', { status: 404 });
  }
};

/**
 * Model详情页面组件
 */
export default function ModelDetailPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { pid } = useParams();
  const {
    model,
    fields: initialFields,
    permissions: initialPermissions,
    versions: initialVersions,
    pages: initialPages,
  } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  /**
   * Get initial tab from URL hash
   */
  const getInitialTab = (): TabType => {
    const hash = location.hash.replace('#', '');
    if (hash === 'basic') return 'overview';
    if (hash === 'permissions') return 'advanced';
    const validTabs: TabType[] = ['overview', 'fields', 'pages', 'versions', 'runtime', 'advanced'];
    return validTabs.includes(hash as TabType) ? (hash as TabType) : 'overview';
  };

  // 当前激活的Tab
  const [activeTab, setActiveTab] = useState<TabType>(getInitialTab());

  // 数据状态
  const [fields, setFields] = useState<ModelFieldBinding[]>(initialFields);
  const [permissions, setPermissions] = useState<Permission[]>(initialPermissions);
  const [versions, setVersions] = useState<ModelVersion[]>(initialVersions);
  const [pages, setPages] = useState<any[]>(initialPages);

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 发布相关状态
  const [showPublishConfirm, setShowPublishConfirm] = useState(false);
  const [publishPreview, setPublishPreview] = useState<{
    modelCode: string;
    ddlStatements: string[];
    operationType: string;
    affectedTables: string[];
    riskAssessment: { level: string; description: string; warnings: string[] } | null;
  } | null>(null);
  const [publishLoading, setPublishLoading] = useState(false);

  // CRUD向导状态
  const [showCrudWizard, setShowCrudWizard] = useState(false);

  // 字段配置对话框状态
  const [configField, setConfigField] = useState<ModelFieldBinding | null>(null);

  // 字典配置对话框状态
  const [dictConfigField, setDictConfigField] = useState<ModelFieldBinding | null>(null);

  // 虚拟 Model 运行时检查状态
  const [sampleData, setSampleData] = useState<unknown>(null);
  const [connectivityStatus, setConnectivityStatus] = useState<
    { ok: boolean; message?: string } | null
  >(null);

  const standardPages = useMemo(() => {
    const grouped: Record<StandardPageKind, RelatedPage | null> = {
      list: null,
      detail: null,
      form: null,
    };
    for (const page of pages as RelatedPage[]) {
      const kind = normalizePageKind(page);
      if (kind !== 'custom' && !grouped[kind]) {
        grouped[kind] = page;
      }
    }
    return grouped;
  }, [pages]);

  const customPages = useMemo(
    () => (pages as RelatedPage[]).filter((page) => normalizePageKind(page) === 'custom'),
    [pages],
  );

  const primaryDesignerPage = useMemo(
    () =>
      standardPages.detail ||
      standardPages.list ||
      standardPages.form ||
      (pages[0] as RelatedPage | undefined) ||
      null,
    [pages, standardPages],
  );

  const latestPage = useMemo(() => {
    const sorted = [...(pages as Array<RelatedPage & { updatedAt?: string }>)].sort((a, b) => {
      const left = a.updatedAt ? Date.parse(a.updatedAt) : 0;
      const right = b.updatedAt ? Date.parse(b.updatedAt) : 0;
      return right - left;
    });
    return sorted[0] ?? null;
  }, [pages]);

  const hasGeneratedPages = pages.length > 0;
  const hasStandardPages = Boolean(
    standardPages.list || standardPages.detail || standardPages.form,
  );

  /**
   * 虚拟 Model: 重新检测 schema
   */
  const triggerRedetection = useCallback(
    async (modelPid: string) => {
      try {
        const res = await fetch(`/api/meta/virtual-models/${modelPid}/redetect`, {
          method: 'POST',
          credentials: 'include',
        });
        if (res.status === 404 || res.status === 405) {
          showErrorToast('暂未支持自动重新检测,P1 backend 未实现该 endpoint');
          return;
        }
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        showSuccessToast('重新检测已触发');
      } catch (err) {
        console.error('Redetect failed:', err);
        showErrorToast('暂未支持自动重新检测,P1 backend 未实现该 endpoint');
      }
    },
    [showSuccessToast, showErrorToast],
  );

  /**
   * 虚拟 Model: 连通性检查
   */
  const checkConnectivity = useCallback(async () => {
    try {
      const res = await fetch(`/api/dynamic/${model.code}/list?pageNum=1&pageSize=1`, {
        credentials: 'include',
      });
      if (res.ok) {
        setConnectivityStatus({ ok: true });
        showSuccessToast('数据源连通正常');
      } else {
        setConnectivityStatus({ ok: false, message: `HTTP ${res.status}` });
        showErrorToast(`连通性检查失败: HTTP ${res.status}`);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setConnectivityStatus({ ok: false, message: msg });
      showErrorToast(`连通性检查失败: ${msg}`);
    }
  }, [model.code, showSuccessToast, showErrorToast]);

  /**
   * 虚拟 Model: 加载样本数据
   */
  const loadSample = useCallback(async () => {
    try {
      const res = await fetch(`/api/dynamic/${model.code}/list?pageNum=1&pageSize=3`, {
        credentials: 'include',
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const json = await res.json();
      setSampleData(json);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      showErrorToast(`加载样本失败: ${msg}`);
      setSampleData({ error: msg });
    }
  }, [model.code, showErrorToast]);

  /**
   * Sync activeTab with URL hash changes (browser back/forward)
   */
  useEffect(() => {
    const newTab = getInitialTab();
    if (newTab !== activeTab) {
      setActiveTab(newTab);
    }
  }, [location.hash]);

  /**
   * 字段排序
   */
  const handleFieldsReorder = useCallback(
    async (reorderedFields: ModelFieldBinding[]) => {
      try {
        const orderUpdates = reorderedFields.map((f, index) => ({
          fieldCode: f.pid || f.code || f.fieldCode,
          displayOrder: index + 1,
        }));
        await modelService.updateFieldsOrder(pid!, orderUpdates);
        setFields(reorderedFields);
        showSuccessToast('字段顺序已更新');
      } catch (error) {
        console.error('Failed to reorder fields:', error);
        showErrorToast('更新字段顺序失败');
        throw error;
      }
    },
    [pid, showSuccessToast, showErrorToast],
  );

  /**
   * 配置字段
   */
  const handleFieldConfigure = useCallback((field: ModelFieldBinding) => {
    setConfigField(field);
  }, []);

  /**
   * 配置字段字典
   */
  const handleDictConfig = useCallback((field: ModelFieldBinding) => {
    setDictConfigField(field);
  }, []);

  /**
   * 保存字段配置
   */
  const handleFieldConfigSave = useCallback(
    async (config: FieldBindingConfig) => {
      if (!configField) return;

      try {
        await modelService.updateFieldBinding(pid!, configField.fieldCode, config);

        // 更新本地状态
        setFields(fields.map((f) => (f.id === configField.id ? { ...f, ...config } : f)));

        showSuccessToast('字段配置已更新');
      } catch (error) {
        console.error('Failed to update field config:', error);
        showErrorToast('更新字段配置失败');
        throw error;
      }
    },
    [configField, fields, pid, showSuccessToast, showErrorToast],
  );

  /**
   * 保存字典配置
   */
  const handleDictConfigSave = useCallback(
    async (dictCode: string | null) => {
      if (!dictConfigField) return;

      try {
        const fieldPid = dictConfigField.pid || dictConfigField.fieldPid;
        if (!fieldPid) {
          throw new Error('Field PID not found');
        }

        if (dictCode) {
          // Bind dictionary
          await modelService.bindDictToField(fieldPid, dictCode);
          showSuccessToast('字典绑定成功');
        } else {
          // Unbind dictionary
          await modelService.unbindDictFromField(fieldPid);
          showSuccessToast('字典解绑成功');
        }

        // Reload fields to get updated dictionary info
        const updatedFields = await modelService.getModelFields(pid!);
        setFields(updatedFields);
      } catch (error) {
        console.error('Failed to update dict config:', error);
        showErrorToast('更新字典配置失败');
        throw error;
      }
    },
    [dictConfigField, pid, showSuccessToast, showErrorToast],
  );

  /**
   * 解绑字段
   */
  const handleFieldUnbind = useCallback(
    async (field: ModelFieldBinding) => {
      const confirmed = await confirmDialog({
        content: `确定要从模型中移除字段 "${field.fieldName || field.fieldCode}" 吗？`,
        variant: 'danger',
      });

      if (!confirmed) return;

      try {
        await modelService.unbindField(pid!, field.fieldCode);

        setFields(fields.filter((f) => f.id !== field.id));
        showSuccessToast('字段已移除');
      } catch (error) {
        console.error('Failed to unbind field:', error);
        showErrorToast('移除字段失败');
        throw error;
      }
    },
    [fields, pid, showSuccessToast, showErrorToast],
  );

  /**
   * 添加字段
   */
  const handleFieldBound = useCallback(async () => {
    // Reload fields after binding
    try {
      const updatedFields = await modelService.getModelFields(pid!);
      setFields(updatedFields);
      showSuccessToast('字段绑定成功');
    } catch (error) {
      console.error('Failed to reload fields:', error);
    }
  }, [pid, showSuccessToast]);

  /**
   * 切换Tab
   */
  const handleTabChange = useCallback(
    (tab: TabType) => {
      if (tab === activeTab) return; // Prevent unnecessary updates
      setActiveTab(tab);
      // Update URL hash without triggering page reload
      navigate(`#${tab}`, { replace: true });
    },
    [activeTab, navigate],
  );

  /**
   * 编辑Model
   */
  const handleEdit = useCallback(() => {
    navigate(`/meta/models/${pid}/edit`);
  }, [pid, navigate]);

  /**
   * 删除Model
   */
  const handleDelete = useCallback(async () => {
    const confirmed = await confirmDialog({
      content: `确定要删除模型 "${model.displayName}" 吗？此操作不可恢复。`,
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await modelService.delete(pid!);
      showSuccessToast('删除Model成功');
      navigate('/meta/models');
    } catch (error) {
      console.error('Failed to delete model:', error);
      showErrorToast('删除Model失败');
    } finally {
      setLoading(false);
    }
  }, [pid, model, navigate, showSuccessToast, showErrorToast]);

  /**
   * 刷新缓存
   */
  const handleRefreshCache = useCallback(async () => {
    setLoading(true);
    try {
      await modelService.refreshCache(pid!);
      showSuccessToast('刷新缓存成功');
    } catch (error) {
      console.error('Failed to refresh cache:', error);
      showErrorToast('刷新缓存失败');
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  /**
   * 发布模型 - 先预览DDL
   */
  const handlePublishClick = useCallback(async () => {
    setPublishLoading(true);
    try {
      const preview = await modelService.previewPublishDDL(pid!);
      setPublishPreview(preview);
      setShowPublishConfirm(true);
    } catch (error) {
      console.error('Failed to preview DDL:', error);
      showErrorToast('获取DDL预览失败');
    } finally {
      setPublishLoading(false);
    }
  }, [pid, showErrorToast]);

  /**
   * 确认发布
   */
  const handlePublishConfirm = useCallback(async () => {
    setPublishLoading(true);
    try {
      await modelService.publish(pid!);
      showSuccessToast('模型发布成功');
      setShowPublishConfirm(false);
      setPublishPreview(null);
      // Reload page to refresh model status
      window.location.reload();
    } catch (error) {
      console.error('Failed to publish model:', error);
      showErrorToast('模型发布失败');
    } finally {
      setPublishLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  /**
   * 取消发布
   */
  const handleUnpublish = useCallback(async () => {
    const confirmed = await confirmDialog({
      content: '确定要取消发布此模型吗？数据库表将保留，但模型状态会变为 deprecated。',
    });
    if (!confirmed) return;

    setLoading(true);
    try {
      await modelService.unpublish(pid!);
      showSuccessToast('模型已取消发布');
      window.location.reload();
    } catch (error) {
      console.error('Failed to unpublish model:', error);
      showErrorToast('取消发布失败');
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  /**
   * 打开CRUD向导
   */
  const handleOpenCrudWizard = useCallback(() => {
    setShowCrudWizard(true);
  }, []);

  const handleOpenPageDesigner = useCallback(() => {
    if (primaryDesignerPage?.pid) {
      navigate(`/page-designer/${primaryDesignerPage.pid}`);
      return;
    }
    navigate(`/p/page_schema/new?modelCode=${encodeURIComponent(model.code)}`);
  }, [model.code, navigate, primaryDesignerPage]);

  const handleCreatePage = useCallback(
    (kind?: string) => {
      const params = new URLSearchParams({ modelCode: model.code });
      if (kind) {
        params.set('kind', kind);
      }
      navigate(`/p/page_schema/new?${params.toString()}`);
    },
    [model.code, navigate],
  );

  const handleOpenPage = useCallback(
    (page: RelatedPage) => {
      const destination = resolvePageRoute(page);
      if (typeof window !== 'undefined' && destination.startsWith('/')) {
        window.location.assign(destination);
        return;
      }
      navigate(destination);
    },
    [navigate],
  );

  const handleEditPage = useCallback(
    (page: RelatedPage) => {
      navigate(`/page-designer/${page.pid}`);
    },
    [navigate],
  );

  const handlePrimaryPageAction = useCallback(() => {
    if (hasGeneratedPages) {
      handleOpenPageDesigner();
      return;
    }
    handleOpenCrudWizard();
  }, [handleOpenCrudWizard, handleOpenPageDesigner, hasGeneratedPages]);

  const handlePrimaryPreview = useCallback(() => {
    if (standardPages.detail) {
      handleOpenPage(standardPages.detail);
      return;
    }
    if (standardPages.list) {
      handleOpenPage(standardPages.list);
      return;
    }
    if (standardPages.form) {
      handleOpenPage(standardPages.form);
      return;
    }
    showSuccessToast('暂无可预览页面，先生成或创建页面');
  }, [handleOpenPage, showSuccessToast, standardPages.detail, standardPages.form, standardPages.list]);

  /**
   * 关闭CRUD向导
   */
  const handleCloseCrudWizard = useCallback(() => {
    setShowCrudWizard(false);
  }, []);

  /**
   * CRUD向导完成
   */
  const handleCrudWizardComplete = useCallback(async () => {
    setShowCrudWizard(false);
    showSuccessToast('CRUD页面生成成功');

    // 重新加载关联页面
    try {
      const updatedPages = await modelService.getRelatedPages(pid!);
      setPages(updatedPages);

      // 切换到关联页面Tab
      setActiveTab('pages');
    } catch (error) {
      console.error('Failed to reload pages:', error);
    }
  }, [pid, showSuccessToast]);

  /**
   * 查看版本详情
   */
  const handleViewVersion = useCallback(
    (version: number) => {
      // TODO: 实现版本详情查看
      showSuccessToast(`查看版本 ${version} 功能开发中`);
    },
    [showSuccessToast],
  );

  /**
   * 回滚到指定版本
   */
  const handleRollbackToVersion = useCallback(
    async (version: number) => {
      const confirmed = await confirmDialog({
        content: `确定要回滚到版本 ${version} 吗？`,
      });

      if (!confirmed) return;

      setLoading(true);
      try {
        await modelService.rollbackToVersion(model.code, version);
        showSuccessToast(`回滚到版本 ${version} 成功`);
        // 重新加载页面
        window.location.reload();
      } catch (error) {
        console.error('Failed to rollback version:', error);
        showErrorToast('版本回滚失败');
      } finally {
        setLoading(false);
      }
    },
    [model, showSuccessToast, showErrorToast],
  );

  return (
    <div className="mx-auto max-w-7xl p-6">
      <div className="mb-6 rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="space-y-4">
            <div>
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span
                  className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${
                    model.status === 'published'
                      ? 'bg-green-100 text-green-800'
                      : model.status === 'draft'
                        ? 'bg-yellow-100 text-yellow-800'
                        : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {model.status === 'published' && '已发布'}
                  {model.status === 'draft' && '草稿'}
                  {model.status === 'archived' && '已归档'}
                </span>
                <SourceTypeBadge sourceType={model.sourceType} />
              </div>
              <h1 className="text-2xl font-bold text-gray-900">{model.displayName}</h1>
              <p className="mt-1 text-sm text-gray-500">
                模型编码: <span className="font-mono text-blue-600">{model.code}</span>
                {model.description && ` · ${model.description}`}
              </p>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">字段</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{fields.length}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">页面</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{pages.length}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">版本</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{model.version || 'N/A'}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">最近更新</div>
                <div className="mt-1 text-sm font-medium text-gray-900">
                  {new Date(model.updatedAt).toLocaleDateString()}
                </div>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 lg:max-w-md lg:justify-end">
            <button
              data-testid="model-primary-page-action"
              onClick={handlePrimaryPageAction}
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              {hasGeneratedPages ? '打开页面设计' : '生成基础 CRUD'}
            </button>
            {hasGeneratedPages && (
              <button
                data-testid="model-primary-page-preview"
                onClick={handlePrimaryPreview}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
              >
                预览主页面
              </button>
            )}
            <button
              onClick={handleEdit}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
              disabled={loading}
            >
              编辑模型
            </button>
            <details className="group relative">
              <summary className="list-none rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none">
                更多
              </summary>
              <div className="absolute right-0 z-10 mt-2 w-44 rounded-xl border border-gray-200 bg-white p-2 shadow-lg">
                {model.status === 'draft' && (
                  <button
                    onClick={handlePublishClick}
                    className="block w-full rounded-lg px-3 py-2 text-left text-sm text-green-700 hover:bg-green-50"
                    disabled={loading || publishLoading}
                  >
                    {publishLoading ? '加载中...' : '发布模型'}
                  </button>
                )}
                {model.status === 'published' && (
                  <button
                    onClick={handleUnpublish}
                    className="block w-full rounded-lg px-3 py-2 text-left text-sm text-amber-700 hover:bg-amber-50"
                    disabled={loading}
                  >
                    取消发布
                  </button>
                )}
                <button
                  onClick={handleRefreshCache}
                  className="block w-full rounded-lg px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
                  disabled={loading}
                >
                  刷新缓存
                </button>
                {isVirtualModel(model) && (
                  <button
                    className="block w-full rounded-lg px-3 py-2 text-left text-sm text-blue-700 hover:bg-blue-50"
                    onClick={() => triggerRedetection(model.pid)}
                    data-testid="redetect-btn"
                  >
                    重新检测
                  </button>
                )}
                <button
                  onClick={handleDelete}
                  className="block w-full rounded-lg px-3 py-2 text-left text-sm text-red-700 hover:bg-red-50"
                  disabled={loading}
                >
                  删除模型
                </button>
              </div>
            </details>
          </div>
        </div>
      </div>

      {/* Tab导航 */}
      <div className="rounded-lg bg-white shadow">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex">
            <button
              onClick={() => handleTabChange('overview')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'overview'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              概览
            </button>
            <button
              onClick={() => handleTabChange('fields')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'fields'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              字段 ({fields.length})
            </button>
            <button
              onClick={() => handleTabChange('pages')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'pages'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              页面 ({pages.length})
            </button>
            <button
              onClick={() => handleTabChange('versions')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'versions'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              版本 ({versions.length})
            </button>
            <button
              onClick={() => handleTabChange('runtime')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'runtime'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              运行时验证
            </button>
            <button
              onClick={() => handleTabChange('advanced')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'advanced'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              高级
            </button>
          </nav>
        </div>

        {/* Tab内容 */}
        <div className="p-6">
          {/* 概览Tab */}
          {activeTab === 'overview' && (
            <div className="space-y-6">
              {isVirtualModel(model) && (
                <div
                  className="rounded-lg border border-blue-200 bg-blue-50 p-4"
                  data-testid="virtual-model-strip"
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex-1">
                      <div className="mb-2 flex items-center gap-3">
                        <SourceTypeBadge sourceType={model.sourceType} />
                        <span className="text-sm font-medium text-gray-700">
                          {(model as unknown as { sourceRef?: string }).sourceRef ?? '-'}
                        </span>
                      </div>
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {Object.entries(
                          (model as unknown as { capabilities?: Record<string, unknown> })
                            .capabilities ?? {},
                        )
                          .filter(([, v]) => typeof v === 'boolean' && v === true)
                          .map(([k]) => (
                            <span
                              key={k}
                              className="rounded border border-blue-200 bg-white px-2 py-0.5 text-xs text-blue-700"
                            >
                              {capabilityLabel(k)}
                            </span>
                          ))}
                      </div>
                    </div>
                  </div>
                </div>
              )}
              <div className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]">
                <div className="rounded-xl border border-gray-200 p-5">
                  <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">模型信息</h2>
                  <div className="grid grid-cols-2 gap-5">
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">模型编码</label>
                      <div className="font-mono text-sm text-gray-900">{model.code}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">显示名称</label>
                      <div className="text-sm text-gray-900">{model.displayName}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
                      <div className="text-sm text-gray-900">{model.status}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">来源</label>
                      <div className="text-sm text-gray-900">{model.sourceType || 'physical'}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">命名空间</label>
                      <div className="text-sm text-gray-900">{model.namespace || '-'}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">环境</label>
                      <div className="text-sm text-gray-900">{model.env || '-'}</div>
                    </div>
                    <div className="col-span-2">
                      <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
                      <div className="text-sm text-gray-900">{model.description || '-'}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">更新时间</label>
                      <div className="text-sm text-gray-900">{new Date(model.updatedAt).toLocaleString()}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">更新人</label>
                      <div className="text-sm text-gray-900">{model.updatedBy}</div>
                    </div>
                  </div>
                </div>

                <div className="rounded-xl border border-gray-200 p-5">
                  <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">设计联动摘要</h2>
                  <div className="space-y-4">
                    <div className="space-y-2">
                      <div className="text-xs uppercase tracking-wide text-gray-500">页面覆盖情况</div>
                      {(['list', 'detail', 'form'] as StandardPageKind[]).map((kind) => {
                        const page = standardPages[kind];
                        const label =
                          kind === 'list' ? '列表页' : kind === 'detail' ? '详情页' : '表单页';
                        return (
                          <div
                            key={kind}
                            className="flex items-center justify-between rounded-lg border border-gray-200 px-3 py-2 text-sm"
                          >
                            <span className="text-gray-700">{label}</span>
                            <span className={page ? 'text-green-700' : 'text-gray-400'}>
                              {page ? '已创建' : '未创建'}
                            </span>
                          </div>
                        );
                      })}
                    </div>
                    <div className="rounded-lg bg-gray-50 p-3">
                      <div className="text-xs uppercase tracking-wide text-gray-500">最近编辑页面</div>
                      <div className="mt-1 text-sm font-medium text-gray-900">
                        {getRelatedPageTitle(latestPage)}
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <button
                        onClick={handlePrimaryPageAction}
                        className="rounded-md bg-blue-600 px-3 py-2 text-sm text-white hover:bg-blue-700"
                      >
                        {hasGeneratedPages ? '打开页面设计' : '生成基础 CRUD'}
                      </button>
                      <button
                        data-testid="overview-page-workbench-link"
                        onClick={() => handleTabChange('pages')}
                        className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                      >
                        查看页面工作台
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* 字段Tab */}
          {activeTab === 'fields' && (
            <div>
              {pages.length > 0 && (
                <div
                  data-testid="fields-page-impact-notice"
                  className="mb-3 flex items-center justify-between rounded-lg border border-sky-200 bg-sky-50 p-3 text-sm text-sky-900"
                >
                  <span>此模型已有 {pages.length} 个关联页面，字段变更可能影响页面配置。</span>
                  <div className="flex gap-2">
                    <button
                      data-testid="fields-impact-view-pages"
                      onClick={() => handleTabChange('pages')}
                      className="rounded border border-sky-300 bg-white px-3 py-1.5 text-xs text-sky-700 hover:bg-sky-100"
                    >
                      查看关联页面
                    </button>
                    <button
                      data-testid="fields-impact-open-designer"
                      onClick={handleOpenPageDesigner}
                      className="rounded border border-sky-300 bg-white px-3 py-1.5 text-xs text-sky-700 hover:bg-sky-100"
                    >
                      打开页面设计
                    </button>
                  </div>
                </div>
              )}
              {isVirtualModel(model) && (
                <div
                  className="mb-3 rounded bg-amber-50 p-3 text-xs text-amber-800"
                  data-testid="virtual-fields-notice"
                >
                  🔒 虚拟 Model 的字段名 / 类型只读(来自 detection 快照)。仅允许改 label / sortable
                  / filterable。
                </div>
              )}
              <FieldListManager
                fields={fields}
                modelPid={pid!}
                modelCode={model.code}
                onFieldsReorder={handleFieldsReorder}
                onFieldConfigure={handleFieldConfigure}
                onFieldUnbind={handleFieldUnbind}
                onFieldBound={handleFieldBound}
                onDictConfig={handleDictConfig}
              />
            </div>
          )}

          {/* 版本Tab */}
          {activeTab === 'versions' && (
            <div>
              {versions.length === 0 ? (
                <div className="py-12 text-center">
                  <p className="text-gray-500">暂无版本历史</p>
                </div>
              ) : (
                <div className="space-y-4">
                  {versions.map((version) => (
                    <div key={version.version} className="rounded-lg border border-gray-200 p-4">
                      <div className="flex items-start justify-between">
                        <div>
                          <div className="flex items-center gap-2">
                            <h3 className="text-sm font-medium text-gray-900">
                              版本 {version.version}
                            </h3>
                            {version.isCurrent && (
                              <span className="inline-flex rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                                当前版本
                              </span>
                            )}
                            <span
                              className={`inline-flex rounded px-2 py-1 text-xs font-medium ${
                                version.status === 'published'
                                  ? 'bg-blue-100 text-blue-800'
                                  : version.status === 'draft'
                                    ? 'bg-yellow-100 text-yellow-800'
                                    : 'bg-gray-100 text-gray-800'
                              }`}
                            >
                              {version.status}
                            </span>
                          </div>
                          <p className="mt-1 text-sm text-gray-500">
                            {version.versionNote || '无说明'}
                          </p>
                          <p className="mt-1 text-xs text-gray-400">
                            {new Date(version.createdAt).toLocaleString()} · {version.createdBy}
                          </p>
                        </div>
                        <div className="flex gap-2">
                          <button
                            onClick={() => handleViewVersion(version.version)}
                            className="text-sm text-blue-600 hover:text-blue-900"
                          >
                            查看
                          </button>
                          {!version.isCurrent && (
                            <button
                              onClick={() => handleRollbackToVersion(version.version)}
                              className="text-sm text-orange-600 hover:text-orange-900"
                            >
                              回滚
                            </button>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* 关联页面Tab */}
          {activeTab === 'pages' && (
            <div className="space-y-6">
              <div>
                <h2 className="text-lg font-semibold text-gray-900">页面工作台</h2>
                <p className="mt-1 text-sm text-gray-500">先补齐页面，再直接进入设计器调整细节。</p>
              </div>

              {!hasStandardPages && (
                <div className="rounded-2xl border border-dashed border-gray-300 bg-gray-50 px-5 py-5">
                  <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <h3 className="text-sm font-semibold text-gray-900">还没有标准页面</h3>
                      <p className="mt-1 text-sm text-gray-500">
                        可以一键生成基础 CRUD 页面，或按页面类型逐个创建。
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <button
                        data-testid="pages-empty-generate-crud"
                        onClick={handleOpenCrudWizard}
                        className="rounded-md bg-green-600 px-3 py-2 text-sm text-white hover:bg-green-700"
                      >
                        一键生成 CRUD
                      </button>
                      <button
                        data-testid="pages-empty-create-page"
                        onClick={() => handleCreatePage()}
                        className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-white"
                      >
                        手动选择页面
                      </button>
                    </div>
                  </div>
                </div>
              )}

              <div className="grid gap-4 lg:grid-cols-3">
                {(['list', 'detail', 'form'] as StandardPageKind[]).map((kind) => {
                  const page = standardPages[kind];
                  const label =
                    kind === 'list' ? '列表页' : kind === 'detail' ? '详情页' : '表单页';
                  return (
                    <div
                      key={kind}
                      data-testid={`standard-page-card-${kind}`}
                      className="rounded-xl border border-gray-200 p-5"
                    >
                      <div className="mb-3 flex items-center justify-between">
                        <h3 className="text-sm font-semibold text-gray-900">{label}</h3>
                        <span
                          className={`rounded-full px-2.5 py-1 text-xs font-medium ${
                            page
                              ? 'bg-green-100 text-green-800'
                              : 'bg-gray-100 text-gray-500'
                          }`}
                        >
                          {page ? '已创建' : '未创建'}
                        </span>
                      </div>
                      <div className="mb-4 min-h-16 text-sm text-gray-500">
                        {page ? (
                          <>
                            <div className="font-medium text-gray-900">
                              {getRelatedPageTitle(page)}
                            </div>
                            <div className="mt-1 font-mono text-xs text-gray-500">{page.code}</div>
                            <div className="mt-3 flex flex-wrap items-center gap-2">
                              <span
                                className={`rounded-full px-2 py-1 text-xs font-medium ${getPageStatusClass(page)}`}
                              >
                                {getPageStatus(page)}
                              </span>
                              <span className="text-xs text-gray-500">
                                更新于 {formatDateLabel(typeof page.updatedAt === 'string' ? page.updatedAt : undefined)}
                              </span>
                            </div>
                          </>
                        ) : (
                          '还没有此类标准页面'
                        )}
                      </div>
                      <div className="flex flex-wrap gap-2">
                        {page ? (
                          <>
                            <button
                              data-testid={`standard-page-${kind}-edit`}
                              onClick={() => handleEditPage(page)}
                              className="rounded-md border border-blue-300 px-3 py-2 text-sm text-blue-700 hover:bg-blue-50"
                            >
                              编辑设计
                            </button>
                            <button
                              data-testid={`standard-page-${kind}-preview`}
                              onClick={() => handleOpenPage(page)}
                              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                            >
                              预览
                            </button>
                          </>
                        ) : (
                          <button
                            data-testid={`standard-page-${kind}-create`}
                            onClick={() => handleCreatePage(kind)}
                            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                          >
                            创建
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="rounded-xl border border-gray-200 p-5">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-gray-900">其他页面</h3>
                  <Link to="/p/page_schema" className="text-sm text-blue-600 hover:text-blue-800">
                    打开 Page Schema 列表
                  </Link>
                </div>
                {customPages.length === 0 ? (
                  <p className="text-sm text-gray-500">暂无其他自定义页面</p>
                ) : (
                  <div className="space-y-3">
                    {customPages.map((page) => (
                      <div
                        key={page.pid}
                        className="flex items-center justify-between rounded-lg border border-gray-200 px-4 py-3"
                      >
                        <div>
                          <div className="text-sm font-medium text-gray-900">
                            {getRelatedPageTitle(page)}
                          </div>
                          <div className="mt-1 text-xs text-gray-500">{resolvePageRoute(page)}</div>
                          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                            <span
                              className={`rounded-full px-2 py-1 font-medium ${getPageStatusClass(page)}`}
                            >
                              {getPageStatus(page)}
                            </span>
                            <span className="text-gray-500">
                              更新于 {formatDateLabel(typeof page.updatedAt === 'string' ? page.updatedAt : undefined)}
                            </span>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button
                            onClick={() => handleOpenPage(page)}
                            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
                          >
                            打开
                          </button>
                          <button
                            onClick={() => handleEditPage(page)}
                            className="rounded-md border border-blue-300 px-3 py-1.5 text-sm text-blue-700 hover:bg-blue-50"
                          >
                            编辑设计
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* 运行时验证Tab */}
          {activeTab === 'runtime' && (
            <div>
              {isVirtualModel(model) && (
                <div
                  className="mb-4 rounded border p-4"
                  data-testid="virtual-runtime-check"
                >
                  <h3 className="mb-3 text-sm font-medium">虚拟 Model 运行时检查</h3>
                  <div className="space-y-2 text-sm">
                    <div className="flex items-center justify-between">
                      <span>
                        数据源连通性
                        {connectivityStatus && (
                          <span
                            className={`ml-2 text-xs ${
                              connectivityStatus.ok ? 'text-green-600' : 'text-red-600'
                            }`}
                          >
                            {connectivityStatus.ok
                              ? '✅ 正常'
                              : `❌ ${connectivityStatus.message ?? '失败'}`}
                          </span>
                        )}
                      </span>
                      <button
                        onClick={checkConnectivity}
                        className="rounded border px-2 py-1 text-xs"
                        data-testid="check-connectivity-btn"
                      >
                        检查
                      </button>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>样本数据预览</span>
                      <button
                        onClick={loadSample}
                        className="rounded border px-2 py-1 text-xs"
                        data-testid="load-sample-btn"
                      >
                        加载 3 条样本
                      </button>
                    </div>
                  </div>
                  {sampleData !== null && (
                    <pre
                      className="mt-3 max-h-60 overflow-auto rounded bg-gray-50 p-3 text-xs"
                      data-testid="virtual-sample-data"
                    >
                      {JSON.stringify(sampleData, null, 2)}
                    </pre>
                  )}
                </div>
              )}
              <RuntimeVerification
                model={model}
                fields={fields}
                onRefresh={() => window.location.reload()}
              />
            </div>
          )}

          {activeTab === 'advanced' && (
            <div className="space-y-6">
              <div className="rounded-xl border border-gray-200 p-5">
                <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">权限点</h2>
                {permissions.length === 0 ? (
                  <div className="py-8 text-center text-sm text-gray-500">暂无权限点</div>
                ) : (
                  <div className="space-y-4">
                    {permissions.map((permission) => (
                      <div key={permission.id} className="rounded-lg border border-gray-200 p-4">
                        <div className="flex items-start justify-between">
                          <div>
                            <h3 className="text-sm font-medium text-gray-900">
                              {permission.displayName}
                            </h3>
                            <p className="mt-1 text-sm text-gray-500">{permission.description}</p>
                            <div className="mt-2 flex gap-2">
                              <span className="inline-flex rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
                                {permission.type}
                              </span>
                              <span className="inline-flex rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                                {permission.action}
                              </span>
                            </div>
                          </div>
                          <button className="text-sm text-blue-600 hover:text-blue-900">
                            查看引用
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="rounded-xl border border-gray-200 p-5">
                <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">技术元数据</h2>
                <div className="grid grid-cols-2 gap-5">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">模型类型</label>
                    <div className="text-sm text-gray-900">
                      {model.modelType === 'entity' && '实体'}
                      {model.modelType === 'view' && '视图'}
                      {model.modelType === 'aggregate' && '聚合'}
                    </div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">是否当前版本</label>
                    <div className="text-sm text-gray-900">{model.isCurrent ? '是' : '否'}</div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">创建时间</label>
                    <div className="text-sm text-gray-900">{new Date(model.createdAt).toLocaleString()}</div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">创建人</label>
                    <div className="text-sm text-gray-900">{model.createdBy}</div>
                  </div>
                  {model.releaseId && (
                    <>
                      <div>
                        <label className="mb-1 block text-sm font-medium text-gray-700">Release ID</label>
                        <div className="font-mono text-sm text-gray-900">{model.releaseId}</div>
                      </div>
                      <div>
                        <label className="mb-1 block text-sm font-medium text-gray-700">Release PID</label>
                        <div className="font-mono text-sm text-gray-900">{model.releasePid}</div>
                      </div>
                    </>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* CRUD向导对话框 */}
      {showCrudWizard && (
        <CrudTemplateWizard
          modelCode={model.code}
          modelName={model.displayName}
          fields={fields}
          onClose={handleCloseCrudWizard}
          onComplete={handleCrudWizardComplete}
        />
      )}

      {/* 字段配置对话框 */}
      {configField && (
        <FieldConfigDialog
          field={configField}
          onSave={handleFieldConfigSave}
          onClose={() => setConfigField(null)}
        />
      )}

      {/* 字典配置对话框 */}
      {dictConfigField && (
        <DictConfigDialog
          field={dictConfigField}
          modelPid={pid!}
          onSave={handleDictConfigSave}
          onClose={() => setDictConfigField(null)}
        />
      )}

      {/* 发布确认对话框 */}
      {showPublishConfirm && publishPreview && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="mx-4 flex max-h-[80vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-xl">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-semibold text-gray-900">确认发布模型</h3>
              <p className="mt-1 text-sm text-gray-500">以下 DDL 语句将被执行以创建数据库表：</p>
            </div>

            <div className="flex-1 overflow-y-auto px-6 py-4">
              {publishPreview.riskAssessment && (
                <div
                  className={`mb-4 rounded-md p-3 ${
                    publishPreview.riskAssessment.level === 'high'
                      ? 'border border-red-200 bg-red-50'
                      : publishPreview.riskAssessment.level === 'medium'
                        ? 'border border-yellow-200 bg-yellow-50'
                        : 'border border-green-200 bg-green-50'
                  }`}
                >
                  <p className="text-sm font-medium">
                    风险等级: {publishPreview.riskAssessment.level}
                  </p>
                  {publishPreview.riskAssessment.description && (
                    <p className="mt-1 text-sm">{publishPreview.riskAssessment.description}</p>
                  )}
                  {publishPreview.riskAssessment.warnings?.length > 0 && (
                    <ul className="mt-2 list-inside list-disc text-sm">
                      {publishPreview.riskAssessment.warnings.map((w, i) => (
                        <li key={i}>{w}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

              <div className="overflow-x-auto rounded-md bg-gray-900 p-4">
                <pre className="font-mono text-sm whitespace-pre-wrap text-green-400">
                  {publishPreview.ddlStatements?.join('\n\n') || 'No DDL statements'}
                </pre>
              </div>

              {publishPreview.affectedTables?.length > 0 && (
                <div className="mt-3 text-sm text-gray-600">
                  <span className="font-medium">影响的表: </span>
                  {publishPreview.affectedTables.join(', ')}
                </div>
              )}
            </div>

            <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
              <button
                onClick={() => {
                  setShowPublishConfirm(false);
                  setPublishPreview(null);
                }}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
                disabled={publishLoading}
              >
                取消
              </button>
              <button
                onClick={handlePublishConfirm}
                className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700"
                disabled={publishLoading}
              >
                {publishLoading ? '发布中...' : '确认发布'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
