import { getPublishText, buildPermissionReplayContext, buildSlaNodeReplayContext, buildWorkflowReplayContext } from './modelPublishSampleContext';
import { getPolicyHeading, getMigrationPlanMessage, getHistoricalPolicyMessage } from './modelPublishPolicyMessages';
import { useModelDetailText, type ModelDetailTextFn, type ModelDetailTextKey } from './modelDetailText';
import { ModelPublishReplayResultCard } from './ModelPublishReplayResultCard';
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

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import {
  useNavigate,
  useParams,
  useLoaderData,
  useLocation,
  Link,
  type LoaderFunctionArgs,
} from 'react-router';
import {
  modelService,
  type ModelPublishReplayReport,
  type PublishPreview,
  type RelatedPage,
} from '~/shared/services/modelService';
import { confirmDialog } from '~/utils/confirmDialog';
import { permissionService } from '~/shared/services/permissionService';
import { useToastContext } from '~/contexts/ToastContext';
import { CrudTemplateWizard } from '~/ui/meta/CrudTemplateWizard';
import { RuntimeVerification } from '~/ui/meta/RuntimeVerification';
import { FieldListManager } from '~/ui/meta/FieldListManager';
import { FieldConfigDialog } from '~/ui/meta/FieldConfigDialog';
import { DictConfigDialog } from '~/ui/meta/DictConfigDialog';
import { SourceTypeBadge } from '~/shared/components/SourceTypeBadge';
import { PermissionGuard } from '~/ui/PermissionGuard';
import { useSmartText, type LocalizedText } from '~/utils/i18n';
import type { MetaModelDTO, ModelFieldBinding, Permission, ModelVersion } from '~/types/model';

/**
 * Check whether the given model is a virtual model (non-physical sourceType).
 */
function isVirtualModel(model: { sourceType?: string }): boolean {
  return !!model.sourceType && model.sourceType !== 'physical';
}

const CAPABILITY_LABEL_KEYS: Record<string, ModelDetailTextKey> = {
  list: 'capabilityList',
  detail: 'capabilityDetail',
  sort: 'capabilitySort',
  filter: 'capabilityFilter',
  create: 'capabilityCreate',
  update: 'capabilityUpdate',
  delete: 'capabilityDelete',
  export: 'capabilityExport',
  search: 'capabilitySearch',
  paginate: 'capabilityPaginate',
};

/**
 * Human-readable label for a capability flag key. Unknown keys fall back to the raw code.
 */
function capabilityLabel(key: string, text: ModelDetailTextFn): string {
  const entry = CAPABILITY_LABEL_KEYS[key];
  return entry ? text(entry) : key;
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

function getPageStatus(page: RelatedPage | null | undefined, text: ModelDetailTextFn): string {
  const status = page && typeof page.status === 'string' ? page.status.toLowerCase() : '';
  if (status === 'published') return text('statusPublished');
  if (status === 'draft') return text('statusDraft');
  if (status === 'archived') return text('statusArchived');
  return page ? text('statusUnmarked') : text('statusNotCreated');
}

function getPageStatusClass(page: RelatedPage | null | undefined): string {
  const status = page && typeof page.status === 'string' ? page.status.toLowerCase() : '';
  if (status === 'published') return 'bg-green-100 text-green-800';
  if (status === 'draft') return 'bg-amber-100 text-amber-800';
  if (status === 'archived') return 'bg-gray-100 text-gray-700';
  return page ? 'bg-sky-100 text-sky-800' : 'bg-gray-100 text-gray-500';
}

function getRelatedPageTitle(page: RelatedPage | null | undefined, text: ModelDetailTextFn): string {
  if (!page) return text('pageEmpty');
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
    text('pageUnnamed')
  );
}

function metadataString(metadata: Record<string, unknown> | undefined, key: string): string {
  const value = metadata?.[key];
  return typeof value === 'string' && value.trim() ? value : '';
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
  const { locale, text } = useModelDetailText();
  const smartText = useSmartText();
  const statusLabels: Record<string, LocalizedText> = {
    published: { 'zh-CN': '已发布', en: 'Published' },
    draft: { 'zh-CN': '草稿', en: 'Draft' },
    archived: { 'zh-CN': '已归档', en: 'Archived' },
  };
  const sourceLabels: Record<string, LocalizedText> = {
    physical: { 'zh-CN': '物理表', en: 'Physical table' },
    namedQuery: { 'zh-CN': '命名查询', en: 'Named query' },
    endpoint: { 'zh-CN': '接口数据', en: 'Endpoint data' },
    sqlView: { 'zh-CN': '数据库视图', en: 'Database view' },
  };
  const statusLabel = (value: string) => smartText(statusLabels[value] ?? { 'zh-CN': '未知状态', en: 'Unknown status' });
  const sourceLabel = (value?: string) => smartText(sourceLabels[value || 'physical'] ?? { 'zh-CN': '其他来源', en: 'Other source' });
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
  const [permissions] = useState<Permission[]>(initialPermissions);
  const [versions] = useState<ModelVersion[]>(initialVersions);
  const [viewedVersion, setViewedVersion] = useState<MetaModelDTO | null>(null);
  const [viewingVersion, setViewingVersion] = useState<number | null>(null);
  const [versionReadError, setVersionReadError] = useState<string | null>(null);
  const versionReadGeneration = useRef(0);
  const [pages, setPages] = useState<any[]>(initialPages);

  useEffect(() => {
    setViewedVersion(null);
    setViewingVersion(null);
    setVersionReadError(null);
    return () => { versionReadGeneration.current += 1; };
  }, [model.code]);

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 发布相关状态
  const [showPublishConfirm, setShowPublishConfirm] = useState(false);
  const [publishPreview, setPublishPreview] = useState<PublishPreview | null>(null);
  const [publishReplayReport, setPublishReplayReport] =
    useState<ModelPublishReplayReport | null>(null);
  const [publishImpactAcknowledged, setPublishImpactAcknowledged] = useState(false);
  const [publishLoading, setPublishLoading] = useState(false);
  const [publishReplayLoading, setPublishReplayLoading] = useState(false);
  const [publishReplaySampleMemberId, setPublishReplaySampleMemberId] = useState('');
  const [publishReplaySamplePermissionCode, setPublishReplaySamplePermissionCode] = useState('');
  const [publishReplaySampleRecordPid, setPublishReplaySampleRecordPid] = useState('');
  const [publishReplaySampleRecordJson, setPublishReplaySampleRecordJson] = useState('{}');
  const [publishReplaySampleError, setPublishReplaySampleError] = useState<string | null>(null);
  const [publishReplaySlaProcessInstanceId, setPublishReplaySlaProcessInstanceId] = useState('');
  const [publishReplaySlaTaskId, setPublishReplaySlaTaskId] = useState('');
  const [publishReplaySlaTenantId, setPublishReplaySlaTenantId] = useState('');
  const [publishReplaySlaProcessKey, setPublishReplaySlaProcessKey] = useState('');
  const [publishReplaySlaRecordJson, setPublishReplaySlaRecordJson] = useState('{}');
  const [publishReplaySlaSampleError, setPublishReplaySlaSampleError] = useState<string | null>(null);
  const [publishReplayWorkflowProcessInstanceId, setPublishReplayWorkflowProcessInstanceId] = useState('');
  const [publishReplayWorkflowProcessKey, setPublishReplayWorkflowProcessKey] = useState('');
  const [publishReplayWorkflowRecordPid, setPublishReplayWorkflowRecordPid] = useState('');
  const [publishReplayWorkflowRecordJson, setPublishReplayWorkflowRecordJson] = useState('{}');
  const [publishReplayWorkflowSampleError, setPublishReplayWorkflowSampleError] = useState<string | null>(null);

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

  const permissionReplayStep = useMemo(
    () =>
      publishPreview?.governance?.replayPlan?.find(
        (step) => step.consumerType === 'PERMISSION_POLICY',
      ) || null,
    [publishPreview],
  );

  const slaNodeReplayStep = useMemo(
    () =>
      publishPreview?.governance?.replayPlan?.find(
        (step) =>
          step.consumerType === 'SLA_RULE' &&
          String(step.metadata?.targetType || '').toUpperCase() === 'NODE',
      ) || null,
    [publishPreview],
  );

  const workflowReplayStep = useMemo(
    () =>
      publishPreview?.governance?.replayPlan?.find(
        (step) => step.consumerType === 'WORKFLOW_PROCESS',
      ) || null,
    [publishPreview],
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
          showErrorToast(text('redetectUnsupported'));
          return;
        }
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        showSuccessToast(text('redetectTriggered'));
      } catch (err) {
        console.error('Redetect failed:', err);
        showErrorToast(text('redetectUnsupported'));
      }
    },
    [showSuccessToast, showErrorToast, text],
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
        showSuccessToast(text('connectivityOk'));
      } else {
        setConnectivityStatus({ ok: false, message: `HTTP ${res.status}` });
        showErrorToast(text('connectivityFailed', { message: `HTTP ${res.status}` }));
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setConnectivityStatus({ ok: false, message: msg });
      showErrorToast(text('connectivityFailed', { message: msg }));
    }
  }, [model.code, showSuccessToast, showErrorToast, text]);

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
      showErrorToast(text('sampleLoadFailed', { message: msg }));
      setSampleData({ error: msg });
    }
  }, [model.code, showErrorToast, text]);

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
        showSuccessToast(text('fieldOrderUpdated'));
      } catch (error) {
        console.error('Failed to reorder fields:', error);
        showErrorToast(text('fieldOrderUpdateFailed'));
        throw error;
      }
    },
    [pid, showSuccessToast, showErrorToast, text],
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
    async (binding: Partial<ModelFieldBinding>) => {
      if (!configField) return;

      try {
        const fieldCode = configField.fieldCode || configField.code || configField.fieldName;
        if (!fieldCode) throw new Error('Missing field code for binding update');
        await modelService.updateFieldBinding(pid!, fieldCode, binding);

        const savedFields = await modelService.getModelFields(pid!);
        setFields(savedFields);

        showSuccessToast(text('fieldConfigUpdated'));
      } catch (error) {
        console.error('Failed to update field config:', error);
        showErrorToast(text('fieldConfigUpdateFailed'));
        throw error;
      }
    },
    [configField, fields, pid, showSuccessToast, showErrorToast, text],
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
          showSuccessToast(text('dictBindSuccess'));
        } else {
          // Unbind dictionary
          await modelService.unbindDictFromField(fieldPid);
          showSuccessToast(text('dictUnbindSuccess'));
        }

        // Reload fields to get updated dictionary info
        const updatedFields = await modelService.getModelFields(pid!);
        setFields(updatedFields);
      } catch (error) {
        console.error('Failed to update dict config:', error);
        showErrorToast(text('dictConfigUpdateFailed'));
        throw error;
      }
    },
    [dictConfigField, pid, showSuccessToast, showErrorToast, text],
  );

  /**
   * 解绑字段
   */
  const handleFieldUnbind = useCallback(
    async (field: ModelFieldBinding) => {
      const confirmed = await confirmDialog({
        content: text('unbindFieldConfirm', { name: field.fieldName || field.fieldCode }),
        variant: 'danger',
      });

      if (!confirmed) return;

      try {
        await modelService.unbindField(pid!, field.fieldCode);

        setFields(fields.filter((f) => f.id !== field.id));
        showSuccessToast(text('fieldRemoved'));
      } catch (error) {
        console.error('Failed to unbind field:', error);
        showErrorToast(text('fieldRemoveFailed'));
        throw error;
      }
    },
    [fields, pid, showSuccessToast, showErrorToast, text],
  );

  /**
   * 添加字段
   */
  const handleFieldBound = useCallback(async () => {
    // Reload fields after binding
    try {
      const updatedFields = await modelService.getModelFields(pid!);
      setFields(updatedFields);
      showSuccessToast(text('fieldBoundSuccess'));
    } catch (error) {
      console.error('Failed to reload fields:', error);
    }
  }, [pid, showSuccessToast, text]);

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
      content: text('deleteModelConfirm', { name: model.displayName }),
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await modelService.delete(pid!);
      showSuccessToast(text('deleteModelSuccess'));
      navigate('/meta/models');
    } catch (error) {
      console.error('Failed to delete model:', error);
      showErrorToast(text('deleteModelFailed'));
    } finally {
      setLoading(false);
    }
  }, [pid, model, navigate, showSuccessToast, showErrorToast, text]);

  /**
   * 刷新缓存
   */
  const handleRefreshCache = useCallback(async () => {
    setLoading(true);
    try {
      await modelService.refreshCache(pid!);
      showSuccessToast(text('refreshCacheSuccess'));
    } catch (error) {
      console.error('Failed to refresh cache:', error);
      showErrorToast(text('refreshCacheFailed'));
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast, text]);

  /**
   * 发布模型 - 先预览DDL
   */
  const handlePublishClick = useCallback(async () => {
    setPublishLoading(true);
    try {
      const preview = await modelService.previewPublishDDL(pid!);
      const permissionStep = preview.governance?.replayPlan?.find(
        (step) => step.consumerType === 'PERMISSION_POLICY',
      );
      setPublishPreview(preview);
      setPublishReplayReport(null);
      setPublishImpactAcknowledged(false);
      setPublishReplaySampleMemberId('');
      setPublishReplaySamplePermissionCode(
        metadataString(permissionStep?.metadata, 'permissionCode') ||
          permissionStep?.sourceCode ||
          '',
      );
      setPublishReplaySampleRecordPid('');
      setPublishReplaySampleRecordJson('{}');
      setPublishReplaySampleError(null);
      setPublishReplaySlaProcessInstanceId('');
      setPublishReplaySlaTaskId('');
      setPublishReplaySlaTenantId('');
      setPublishReplaySlaProcessKey('');
      setPublishReplaySlaRecordJson('{}');
      setPublishReplaySlaSampleError(null);
      setPublishReplayWorkflowProcessInstanceId('');
      setPublishReplayWorkflowProcessKey('');
      setPublishReplayWorkflowRecordPid('');
      setPublishReplayWorkflowRecordJson('{}');
      setPublishReplayWorkflowSampleError(null);
      setShowPublishConfirm(true);
    } catch (error) {
      console.error('Failed to preview DDL:', error);
      showErrorToast(getPublishText('previewFailed', locale));
    } finally {
      setPublishLoading(false);
    }
  }, [pid, showErrorToast, locale]);

  /**
   * 生成发布后复核报告。没有代表性样本时只收集可自动化/需人工项，不伪装成已执行。
   */
  const buildPermissionReplaySampleContext = useCallback(() => buildPermissionReplayContext({
    memberId: publishReplaySampleMemberId,
    permissionCode: publishReplaySamplePermissionCode,
    recordPid: publishReplaySampleRecordPid,
    recordJson: publishReplaySampleRecordJson,
  }, locale), [publishReplaySampleMemberId, publishReplaySamplePermissionCode,
    publishReplaySampleRecordPid, publishReplaySampleRecordJson, locale]);

  const buildSlaNodeReplaySampleContext = useCallback(() => buildSlaNodeReplayContext({
    processInstanceId: publishReplaySlaProcessInstanceId,
    tenantId: publishReplaySlaTenantId,
    taskId: publishReplaySlaTaskId,
    processKey: publishReplaySlaProcessKey,
    recordJson: publishReplaySlaRecordJson,
  }, locale), [publishReplaySlaProcessInstanceId, publishReplaySlaTenantId,
    publishReplaySlaTaskId, publishReplaySlaProcessKey, publishReplaySlaRecordJson, locale]);

  const buildWorkflowReplaySampleContext = useCallback(() => buildWorkflowReplayContext({
    processInstanceId: publishReplayWorkflowProcessInstanceId,
    processKey: publishReplayWorkflowProcessKey,
    recordPid: publishReplayWorkflowRecordPid,
    recordJson: publishReplayWorkflowRecordJson,
  }, locale), [publishReplayWorkflowProcessInstanceId, publishReplayWorkflowProcessKey,
    publishReplayWorkflowRecordPid, publishReplayWorkflowRecordJson, locale]);

  const handlePublishReplay = useCallback(async (
    replayMode: 'default' | 'permission' | 'sla-node' | 'workflow' = 'default',
  ) => {
    setPublishReplaySampleError(null);
    setPublishReplaySlaSampleError(null);
    setPublishReplayWorkflowSampleError(null);
    let sampleContext: Record<string, Record<string, unknown>> | undefined;
    if (replayMode === 'permission') {
      try {
        sampleContext = buildPermissionReplaySampleContext();
      } catch (error) {
        const message = error instanceof Error ? error.message : getPublishText('permissionInvalid', locale);
        setPublishReplaySampleError(message);
        return;
      }
    } else if (replayMode === 'sla-node') {
      try {
        sampleContext = buildSlaNodeReplaySampleContext();
      } catch (error) {
        const message = error instanceof Error ? error.message : getPublishText('slaInvalid', locale);
        setPublishReplaySlaSampleError(message);
        return;
      }
    } else if (replayMode === 'workflow') {
      try {
        sampleContext = buildWorkflowReplaySampleContext();
      } catch (error) {
        const message = error instanceof Error ? error.message : getPublishText('workflowInvalid', locale);
        setPublishReplayWorkflowSampleError(message);
        return;
      }
    }

    setPublishReplayLoading(true);
    try {
      const report = await modelService.replayPublishImpact(pid!, {
        executeAutomated: replayMode !== 'default',
        correlationId:
          replayMode === 'permission'
            ? `model-publish-${model.code}-permission`
            : replayMode === 'sla-node'
              ? `model-publish-${model.code}-sla-node`
              : replayMode === 'workflow'
                ? `model-publish-${model.code}-workflow`
              : `model-publish-${model.code}`,
        sampleContext,
      });
      setPublishReplayReport(report);
    } catch (error) {
      console.error('Failed to replay model publish impact:', error);
      const message = error instanceof Error ? error.message : getPublishText('reportFailed', locale);
      if (replayMode === 'sla-node') {
        setPublishReplaySlaSampleError(message);
      } else if (replayMode === 'workflow') {
        setPublishReplayWorkflowSampleError(message);
      } else {
        setPublishReplaySampleError(message);
      }
      showErrorToast(message);
    } finally {
      setPublishReplayLoading(false);
    }
  }, [
    buildWorkflowReplaySampleContext,
    buildPermissionReplaySampleContext,
    buildSlaNodeReplaySampleContext,
    model.code,
    pid,
    showErrorToast,
    locale,
  ]);

  /**
   * 确认发布
   */
  const handlePublishConfirm = useCallback(async () => {
    setPublishLoading(true);
    try {
      await modelService.publish(pid!, {
        impactAcknowledged: publishImpactAcknowledged,
        acknowledgementNote: publishPreview?.governance?.requiresAcknowledgement
          ? 'Model publish impact acknowledged in model detail publish dialog'
          : undefined,
      });
      showSuccessToast(getPublishText('publishSuccess', locale));
      setShowPublishConfirm(false);
      setPublishPreview(null);
      setPublishReplayReport(null);
      setPublishImpactAcknowledged(false);
      setPublishReplaySampleMemberId('');
      setPublishReplaySamplePermissionCode('');
      setPublishReplaySampleRecordPid('');
      setPublishReplaySampleRecordJson('{}');
      setPublishReplaySampleError(null);
      setPublishReplaySlaProcessInstanceId('');
      setPublishReplaySlaTaskId('');
      setPublishReplaySlaTenantId('');
      setPublishReplaySlaProcessKey('');
      setPublishReplaySlaRecordJson('{}');
      setPublishReplaySlaSampleError(null);
      setPublishReplayWorkflowProcessInstanceId('');
      setPublishReplayWorkflowProcessKey('');
      setPublishReplayWorkflowRecordPid('');
      setPublishReplayWorkflowRecordJson('{}');
      setPublishReplayWorkflowSampleError(null);
      // Reload page to refresh model status
      window.location.reload();
    } catch (error) {
      console.error('Failed to publish model:', error);
      showErrorToast(getPublishText('publishFailed', locale));
    } finally {
      setPublishLoading(false);
    }
  }, [pid, publishImpactAcknowledged, publishPreview, showSuccessToast, showErrorToast, locale]);

  /**
   * 取消发布
   */
  const handleUnpublish = useCallback(async () => {
    const confirmed = await confirmDialog({
      content: text('unpublishConfirm'),
    });
    if (!confirmed) return;

    setLoading(true);
    try {
      await modelService.unpublish(pid!);
      showSuccessToast(text('unpublishSuccess'));
      window.location.reload();
    } catch (error) {
      console.error('Failed to unpublish model:', error);
      showErrorToast(text('unpublishFailed'));
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast, text]);

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
    showSuccessToast(text('noPreviewablePage'));
  }, [handleOpenPage, showSuccessToast, text, standardPages.detail, standardPages.form, standardPages.list]);

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
    showSuccessToast(text('crudPagesGenerated'));

    // 重新加载关联页面
    try {
      const updatedPages = await modelService.getRelatedPages(pid!);
      setPages(updatedPages);

      // 切换到关联页面Tab
      setActiveTab('pages');
    } catch (error) {
      console.error('Failed to reload pages:', error);
    }
  }, [pid, showSuccessToast, text]);

  /**
   * 查看版本详情
   */
  const handleViewVersion = useCallback(
    async (version: number) => {
      const generation = ++versionReadGeneration.current;
      setViewedVersion(null);
      setVersionReadError(null);
      setViewingVersion(version);
      try {
        const detail = await modelService.getVersionDetail(model.code, version);
        if (generation === versionReadGeneration.current) setViewedVersion(detail);
      } catch {
        if (generation === versionReadGeneration.current) {
          setVersionReadError(smartText({ 'zh-CN': '无法读取版本详情，请重试。', en: 'Could not read this version. Please try again.' }));
        }
      } finally {
        if (generation === versionReadGeneration.current) setViewingVersion(null);
      }
    },
    [model.code, smartText],
  );

  /**
   * 回滚到指定版本
   */
  const handleRollbackToVersion = useCallback(
    async (version: number) => {
      const confirmed = await confirmDialog({
        content: text('rollbackConfirm', { version }),
      });

      if (!confirmed) return;

      setLoading(true);
      try {
        await modelService.rollbackToVersion(model.code, version);
        showSuccessToast(text('rollbackSuccess', { version }));
        // 重新加载页面
        window.location.reload();
      } catch (error) {
        console.error('Failed to rollback version:', error);
        showErrorToast(text('rollbackFailed'));
      } finally {
        setLoading(false);
      }
    },
    [model, showSuccessToast, showErrorToast, text],
  );

  return (
    <div className="mx-auto w-full min-w-0 max-w-7xl p-6">
      <div className="mb-6 rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0 flex-1 space-y-4">
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
                  {model.status === 'published' && text('statusPublished')}
                  {model.status === 'draft' && text('statusDraft')}
                  {model.status === 'archived' && text('statusArchived')}
                </span>
                <SourceTypeBadge sourceType={model.sourceType} />
              </div>
              <h1 className="break-words text-2xl font-bold text-gray-900">{model.displayName}</h1>
              <p className="mt-1 break-words text-sm text-gray-500">
                模型编码: <span className="break-all font-mono text-blue-600">{model.code}</span>
                {model.description && ` · ${model.description}`}
              </p>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">{text('statFields')}</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{fields.length}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">{text('statPages')}</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{pages.length}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">{text('statVersions')}</div>
                <div className="mt-1 text-lg font-semibold text-gray-900">{model.version || 'N/A'}</div>
              </div>
              <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                <div className="text-xs uppercase tracking-wide text-gray-500">{text('statLastUpdated')}</div>
                <div className="mt-1 text-sm font-medium text-gray-900">
                  {new Date(model.updatedAt).toLocaleDateString()}
                </div>
              </div>
            </div>
          </div>

          <div className="flex min-w-0 flex-wrap items-center gap-2 lg:max-w-md lg:justify-end">
            <PermissionGuard permission={hasGeneratedPages ? "page.page.manage" : "meta.page.update"}><button
              data-testid="model-primary-page-action"
              onClick={handlePrimaryPageAction}
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              {hasGeneratedPages ? '打开页面设计' : '生成基础 CRUD'}
            </button></PermissionGuard>
            {hasGeneratedPages && (
              <button
                data-testid="model-primary-page-preview"
                onClick={handlePrimaryPreview}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
              >
                {text('actionPreviewMainPage')}
              </button>
            )}
            <PermissionGuard permission="meta.model.update">
              <button
                data-testid="model-edit-action"
                onClick={handleEdit}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
                disabled={loading}
              >
                编辑模型
              </button>
              <details className="group relative">
                <summary
                  data-testid="model-more-actions"
                  className="list-none rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
                >
                  更多
                </summary>
                <div className="absolute right-0 z-10 mt-2 w-44 rounded-xl border border-gray-200 bg-white p-2 shadow-lg">
                  {model.status === 'draft' && (
                    <button
                      data-testid="model-publish-action"
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
            </PermissionGuard>
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
              {text('tabOverview')}
            </button>
            <button
              onClick={() => handleTabChange('fields')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'fields'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {text('tabFieldsWithCount', { count: fields.length })}
            </button>
            <button
              onClick={() => handleTabChange('pages')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'pages'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {text('tabPagesWithCount', { count: pages.length })}
            </button>
            <button
              onClick={() => handleTabChange('versions')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'versions'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {text('tabVersionsWithCount', { count: versions.length })}
            </button>
            <button
              onClick={() => handleTabChange('runtime')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'runtime'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {text('tabRuntime')}
            </button>
            <button
              onClick={() => handleTabChange('advanced')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'advanced'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              {text('tabAdvanced')}
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
                              {capabilityLabel(k, text)}
                            </span>
                          ))}
                      </div>
                    </div>
                  </div>
                </div>
              )}
              <div className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]">
                <div className="rounded-xl border border-gray-200 p-5">
                  <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">{text('sectionModelInfo')}</h2>
                  <div className="grid grid-cols-2 gap-5">
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">模型编码</label>
                      <div className="break-all font-mono text-sm text-gray-900">{model.code}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelDisplayName')}</label>
                      <div className="text-sm text-gray-900">{model.displayName}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
                      <div className="text-sm text-gray-900">{statusLabel(model.status)}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">来源</label>
                      <div className="text-sm text-gray-900">{sourceLabel(model.sourceType)}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelNamespace')}</label>
                      <div className="text-sm text-gray-900">{model.namespace || '-'}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelEnv')}</label>
                      <div className="text-sm text-gray-900">{model.env || '-'}</div>
                    </div>
                    <div className="col-span-2">
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelDescription')}</label>
                      <div className="text-sm text-gray-900">{model.description || '-'}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelUpdatedAt')}</label>
                      <div className="text-sm text-gray-900">{new Date(model.updatedAt).toLocaleString()}</div>
                    </div>
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelUpdatedBy')}</label>
                      <div className="text-sm text-gray-900">{model.updatedBy}</div>
                    </div>
                  </div>
                </div>

                <div className="rounded-xl border border-gray-200 p-5">
                  <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">{text('sectionDesignSummary')}</h2>
                  <div className="space-y-4">
                    <div className="space-y-2">
                      <div className="text-xs uppercase tracking-wide text-gray-500">{text('labelPageCoverage')}</div>
                      {(['list', 'detail', 'form'] as StandardPageKind[]).map((kind) => {
                        const page = standardPages[kind];
                        const label =
                          kind === 'list' ? text('pageKindList') : kind === 'detail' ? text('pageKindDetail') : text('pageKindForm');
                        return (
                          <div
                            key={kind}
                            className="flex items-center justify-between rounded-lg border border-gray-200 px-3 py-2 text-sm"
                          >
                            <span className="text-gray-700">{label}</span>
                            <span className={page ? 'text-green-700' : 'text-gray-400'}>
                              {page ? text('statusCreated') : text('statusNotCreated')}
                            </span>
                          </div>
                        );
                      })}
                    </div>
                    <div className="rounded-lg bg-gray-50 p-3">
                      <div className="text-xs uppercase tracking-wide text-gray-500">{text('labelLatestEditedPage')}</div>
                      <div className="mt-1 text-sm font-medium text-gray-900">
                        {getRelatedPageTitle(latestPage, text)}
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <PermissionGuard permission={hasGeneratedPages ? "page.page.manage" : "meta.page.update"}><button
                        onClick={handlePrimaryPageAction}
                        className="rounded-md bg-blue-600 px-3 py-2 text-sm text-white hover:bg-blue-700"
                      >
                        {hasGeneratedPages ? '打开页面设计' : '生成基础 CRUD'}
                      </button></PermissionGuard>
                      <button
                        data-testid="overview-page-workbench-link"
                        onClick={() => handleTabChange('pages')}
                        className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                      >
                        {text('actionViewPageWorkbench')}
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
                  <span>{text('fieldsPageImpactNotice', { count: pages.length })}</span>
                  <div className="flex gap-2">
                    <button
                      data-testid="fields-impact-view-pages"
                      onClick={() => handleTabChange('pages')}
                      className="rounded border border-sky-300 bg-white px-3 py-1.5 text-xs text-sky-700 hover:bg-sky-100"
                    >
                      {text('actionViewRelatedPages')}
                    </button>
                    <button
                      data-testid="fields-impact-open-designer"
                      onClick={handleOpenPageDesigner}
                      className="rounded border border-sky-300 bg-white px-3 py-1.5 text-xs text-sky-700 hover:bg-sky-100"
                    >
                      {text('actionOpenPageDesign')}
                    </button>
                  </div>
                </div>
              )}
              {isVirtualModel(model) && (
                <div
                  className="mb-3 rounded bg-amber-50 p-3 text-xs text-amber-800"
                  data-testid="virtual-fields-notice"
                >
                  {text('virtualFieldsReadonly')}
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
              {versionReadError && <p role="alert" data-testid="model-version-read-error" className="mb-3 text-sm text-red-700">{versionReadError}</p>}
              {versions.length === 0 ? (
                <div className="py-12 text-center">
                  <p className="text-gray-500">{text('noVersionHistory')}</p>
                </div>
              ) : (
                <div className="space-y-4">
                  {versions.map((version) => (
                    <div key={version.version} className="rounded-lg border border-gray-200 p-4">
                      <div className="flex items-start justify-between">
                        <div>
                          <div className="flex items-center gap-2">
                            <h3 className="text-sm font-medium text-gray-900">
                              {text('versionLabel', { version: version.version })}
                            </h3>
                            {version.isCurrent && (
                              <span className="inline-flex rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                                {text('currentVersionBadge')}
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
                              {statusLabel(version.status)}
                            </span>
                          </div>
                          <p className="mt-1 text-sm text-gray-500">
                            {version.versionNote || text('noVersionNote')}
                          </p>
                          <p className="mt-1 text-xs text-gray-400">
                            {new Date(version.createdAt).toLocaleString()} · {version.createdBy}
                          </p>
                        </div>
                        <div className="flex gap-2">
                          <button
                            data-testid={`model-version-view-${version.version}`}
                            onClick={() => handleViewVersion(version.version)}
                            disabled={viewingVersion === version.version}
                            className="text-sm text-blue-600 hover:text-blue-900 disabled:opacity-50"
                          >
                            {viewingVersion === version.version
                              ? smartText({ 'zh-CN': '读取中…', en: 'Loading…' })
                              : smartText({ 'zh-CN': '查看', en: 'View' })}
                          </button>
                          {!version.isCurrent && (
                            <PermissionGuard permission="meta.model.update">
                              <button
                                onClick={() => handleRollbackToVersion(version.version)}
                                className="text-sm text-orange-600 hover:text-orange-900"
                              >
                                回滚
                              </button>
                            </PermissionGuard>
                          )}
                        </div>
                      </div>
                      {viewedVersion?.version === version.version && (
                        <dl data-testid="model-version-detail" data-version={viewedVersion.version} className="border-border bg-subtle mt-3 grid grid-cols-1 gap-3 rounded-lg border p-3 text-sm sm:grid-cols-2">
                          <div><dt className="text-text-2">{smartText({ 'zh-CN': '名称', en: 'Name' })}</dt><dd className="break-words font-medium">{viewedVersion.displayName}</dd></div>
                          <div><dt className="text-text-2">{smartText({ 'zh-CN': '版本', en: 'Version' })}</dt><dd>{viewedVersion.version}</dd></div>
                          <div><dt className="text-text-2">{smartText({ 'zh-CN': '状态', en: 'Status' })}</dt><dd>{statusLabel(viewedVersion.status)}</dd></div>
                          <div><dt className="text-text-2">{smartText({ 'zh-CN': '说明', en: 'Description' })}</dt><dd className="whitespace-pre-wrap break-words">{viewedVersion.description || smartText({ 'zh-CN': '暂无说明', en: 'No description' })}</dd></div>
                        </dl>
                      )}
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
                <h2 className="text-lg font-semibold text-gray-900">{text('pageTitleWorkbench')}</h2>
                <p className="mt-1 text-sm text-gray-500">{text('pagesWorkbenchHint')}</p>
              </div>

              {!hasStandardPages && (
                <div className="rounded-2xl border border-dashed border-gray-300 bg-gray-50 px-5 py-5">
                  <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <h3 className="text-sm font-semibold text-gray-900">{text('noStandardPagesTitle')}</h3>
                      <p className="mt-1 text-sm text-gray-500">
                        {text('noStandardPagesHint')}
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <PermissionGuard permission="meta.page.update"><button
                        data-testid="pages-empty-generate-crud"
                        onClick={handleOpenCrudWizard}
                        className="rounded-md bg-green-600 px-3 py-2 text-sm text-white hover:bg-green-700"
                      >
                        一键生成 CRUD
                      </button></PermissionGuard>
                      <button
                        data-testid="pages-empty-create-page"
                        onClick={() => handleCreatePage()}
                        className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-white"
                      >
                        {text('actionPickPageManually')}
                      </button>
                    </div>
                  </div>
                </div>
              )}

              <div className="grid gap-4 lg:grid-cols-3">
                {(['list', 'detail', 'form'] as StandardPageKind[]).map((kind) => {
                  const page = standardPages[kind];
                  const label =
                    kind === 'list' ? text('pageKindList') : kind === 'detail' ? text('pageKindDetail') : text('pageKindForm');
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
                          {page ? text('statusCreated') : text('statusNotCreated')}
                        </span>
                      </div>
                      <div className="mb-4 min-h-16 text-sm text-gray-500">
                        {page ? (
                          <>
                            <div className="font-medium text-gray-900">
                              {getRelatedPageTitle(page, text)}
                            </div>
                            <div className="mt-1 font-mono text-xs text-gray-500">{page.code}</div>
                            <div className="mt-3 flex flex-wrap items-center gap-2">
                              <span
                                className={`rounded-full px-2 py-1 text-xs font-medium ${getPageStatusClass(page)}`}
                              >
                                {getPageStatus(page, text)}
                              </span>
                              <span className="text-xs text-gray-500">
                                {text('updatedAtIndex', { date: formatDateLabel(typeof page.updatedAt === 'string' ? page.updatedAt : undefined) })}
                              </span>
                            </div>
                          </>
                        ) : (
                          text('noSuchStandardPage')
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
                              {text('actionEditDesign')}
                            </button>
                            <button
                              data-testid={`standard-page-${kind}-preview`}
                              onClick={() => handleOpenPage(page)}
                              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                            >
                              {text('actionPreview')}
                            </button>
                          </>
                        ) : (
                          <button
                            data-testid={`standard-page-${kind}-create`}
                            onClick={() => handleCreatePage(kind)}
                            className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                          >
                            {text('actionCreate')}
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="rounded-xl border border-gray-200 p-5">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-gray-900">{text('sectionOtherPages')}</h3>
                  <Link to="/p/page_schema" className="text-sm text-blue-600 hover:text-blue-800">
                    {text('actionOpenPageSchemaList')}
                  </Link>
                </div>
                {customPages.length === 0 ? (
                  <p className="text-sm text-gray-500">{text('noCustomPages')}</p>
                ) : (
                  <div className="space-y-3">
                    {customPages.map((page) => (
                      <div
                        key={page.pid}
                        className="flex items-center justify-between rounded-lg border border-gray-200 px-4 py-3"
                      >
                        <div>
                          <div className="text-sm font-medium text-gray-900">
                            {getRelatedPageTitle(page, text)}
                          </div>
                          <div className="mt-1 text-xs text-gray-500">{resolvePageRoute(page)}</div>
                          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                            <span
                              className={`rounded-full px-2 py-1 font-medium ${getPageStatusClass(page)}`}
                            >
                              {getPageStatus(page, text)}
                            </span>
                            <span className="text-gray-500">
                              {text('updatedAtIndex', { date: formatDateLabel(typeof page.updatedAt === 'string' ? page.updatedAt : undefined) })}
                            </span>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button
                            onClick={() => handleOpenPage(page)}
                            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
                          >
                            {text('actionOpen')}
                          </button>
                          <button
                            onClick={() => handleEditPage(page)}
                            className="rounded-md border border-blue-300 px-3 py-1.5 text-sm text-blue-700 hover:bg-blue-50"
                          >
                            {text('actionEditDesign')}
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
                  <h3 className="mb-3 text-sm font-medium">{text('runtimeCheckTitle')}</h3>
                  <div className="space-y-2 text-sm">
                    <div className="flex items-center justify-between">
                      <span>
                        {text('labelDataSourceConnectivity')}
                        {connectivityStatus && (
                          <span
                            className={`ml-2 text-xs ${
                              connectivityStatus.ok ? 'text-green-600' : 'text-red-600'
                            }`}
                          >
                            {connectivityStatus.ok
                              ? text('runtimeStatusOk')
                              : text('runtimeStatusFail', {
                                  message: connectivityStatus.message ?? text('runtimeStatusFailDefault'),
                                })}
                          </span>
                        )}
                      </span>
                      <button
                        onClick={checkConnectivity}
                        className="rounded border px-2 py-1 text-xs"
                        data-testid="check-connectivity-btn"
                      >
                        {text('actionCheck')}
                      </button>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>{text('labelSamplePreview')}</span>
                      <button
                        onClick={loadSample}
                        className="rounded border px-2 py-1 text-xs"
                        data-testid="load-sample-btn"
                      >
                        {text('actionLoadSamples')}
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
                <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">{text('sectionPermissions')}</h2>
                {permissions.length === 0 ? (
                  <div className="py-8 text-center text-sm text-gray-500">{text('noPermissions')}</div>
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
                            {text('actionViewReferences')}
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="rounded-xl border border-gray-200 p-5">
                <h2 className="mb-4 text-sm font-semibold tracking-wide text-gray-900">{text('sectionTechMetadata')}</h2>
                <div className="grid grid-cols-2 gap-5">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelModelType')}</label>
                    <div className="text-sm text-gray-900">
                      {model.modelType === 'entity' && text('modelTypeEntity')}
                      {model.modelType === 'view' && text('modelTypeView')}
                      {model.modelType === 'aggregate' && text('modelTypeAggregate')}
                    </div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelIsCurrent')}</label>
                    <div className="text-sm text-gray-900">{model.isCurrent ? text('yesLabel') : text('noLabel')}</div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelCreatedAt')}</label>
                    <div className="text-sm text-gray-900">{new Date(model.createdAt).toLocaleString()}</div>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">{text('labelCreatedBy')}</label>
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
        <div
          data-testid="model-publish-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
        >
          <div className="mx-4 flex max-h-[80vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-xl">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-semibold text-gray-900">{getPublishText('dialogTitle', locale)}</h3>
              <p className="mt-1 text-sm text-gray-500">{getPublishText('dialogHint', locale)}</p>
            </div>

            <div className="flex-1 overflow-y-auto px-6 py-4">
              {publishPreview.governance && (
                <div
                  data-testid="model-publish-governance"
                  className={`mb-4 rounded-md border p-4 ${
                    publishPreview.governance.requiresAcknowledgement
                      ? 'border-red-200 bg-red-50'
                      : 'border-emerald-200 bg-emerald-50'
                  }`}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div>
                      <h4 className="text-sm font-semibold text-gray-900">{getPublishText('governanceTitle', locale)}</h4>
                      <p className="mt-1 text-sm text-gray-700">
                        {publishPreview.governance.requiresAcknowledgement
                          ? getPublishText('impactRequired', locale)
                          : getPublishText('impactClear', locale)}
                      </p>
                    </div>
                    <span
                      className={`shrink-0 rounded px-2 py-1 text-xs font-medium ${
                        publishPreview.governance.requiresAcknowledgement
                          ? 'bg-red-100 text-red-700'
                          : 'bg-emerald-100 text-emerald-700'
                      }`}
                    >
                      {publishPreview.governance.requiresAcknowledgement ? getPublishText('ackRequired', locale) : getPublishText('readyToPublish', locale)}
                    </span>
                  </div>

                  {publishPreview.governance.schemaChangeKinds?.length ? (
                    <div className="mt-3 flex flex-wrap gap-2">
                      {publishPreview.governance.schemaChangeKinds.map((kind) => (
                        <span
                          key={kind}
                          className="rounded border border-gray-200 bg-white px-2 py-1 text-xs text-gray-700"
                        >
                          {kind}
                        </span>
                      ))}
                    </div>
                  ) : null}

                  {publishPreview.governance.fieldImpacts?.length ? (
                    <div className="mt-4 space-y-2">
                      {publishPreview.governance.fieldImpacts.map((impact) => (
                        <div
                          key={impact.fieldRef}
                          className="rounded border border-white/70 bg-white/80 p-3 text-sm"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <span className="font-medium text-gray-900">{impact.fieldRef}</span>
                            <span className="text-xs text-gray-500">
                              {impact.references?.length || 0} {getPublishText('referenceCount', locale)}</span>
                          </div>
                          {impact.risk?.summary && (
                            <p className="mt-1 text-gray-700">{impact.risk.summary}</p>
                          )}
                          {impact.risk?.counts && Object.keys(impact.risk.counts).length > 0 && (
                            <div className="mt-2 flex flex-wrap gap-2">
                              {Object.entries(impact.risk.counts).map(([key, value]) => (
                                <span
                                  key={key}
                                  className="rounded bg-gray-100 px-2 py-1 text-xs text-gray-700"
                                >
                                  {key}: {value}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  ) : null}

                  {publishPreview.governance.replayPlan?.length ? (
                    <div
                      data-testid="model-publish-replay-plan"
                      className="mt-4 rounded border border-white/70 bg-white/80 p-3"
                    >
                      <div className="flex items-center justify-between gap-3">
                        <div>
                          <p className="text-xs font-semibold text-gray-600">{getPublishText('planTitle', locale)}</p>
                          <span className="text-xs text-gray-500">
                            {publishPreview.governance.replayPlan.length} {getPublishText('consumerCount', locale)}</span>
                        </div>
                        <button
                          type="button"
                          data-testid="model-publish-run-replay"
                          onClick={() => handlePublishReplay('default')}
                          className="rounded border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-medium text-blue-700 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-60"
                          disabled={publishReplayLoading}
                        >
                          {publishReplayLoading ? getPublishText('generating', locale) : getPublishText('generateReport', locale)}
                        </button>
                      </div>
                      <div className="mt-3 space-y-2">
                        {publishPreview.governance.replayPlan.map((step, index) => {
                          const source =
                            step.sourceName ||
                            step.sourceCode ||
                            step.sourcePid ||
                            step.consumerType ||
                            getPublishText('unnamedConsumer', locale);
                          return (
                            <div
                              key={`${step.consumerType || 'consumer'}-${step.sourcePid || step.sourceCode || index}`}
                              className="rounded border border-gray-100 bg-gray-50 p-3"
                            >
                              <div className="flex flex-wrap items-center gap-2">
                                <span className="rounded bg-blue-50 px-2 py-1 text-xs font-medium text-blue-700">
                                  {step.consumerLabel || step.consumerType || getPublishText('ruleConsumer', locale)}
                                </span>
                                {step.required && (
                                  <span className="rounded bg-red-50 px-2 py-1 text-xs font-medium text-red-700">
                                    {getPublishText('required', locale)}</span>
                                )}
                                <span className="text-sm font-medium text-gray-900">{source}</span>
                                {step.sourceVersion && (
                                  <span className="text-xs text-gray-500">v{step.sourceVersion}</span>
                                )}
                              </div>
                              {(step.fieldRef || step.targetPath || step.binding) && (
                                <div className="mt-2 flex flex-wrap gap-2 text-xs text-gray-600">
                                  {step.fieldRef && <span>{getPublishText('field', locale)}{' '}{step.fieldRef}</span>}
                                  {step.targetPath && <span>{getPublishText('path', locale)}{' '}{step.targetPath}</span>}
                                  {step.binding && <span>{getPublishText('binding', locale)}{' '}{step.binding}</span>}
                                </div>
                              )}
                              {step.recommendedAction && (
                                <p className="mt-2 text-sm text-gray-700">{step.recommendedAction}</p>
                              )}
                            </div>
                          );
                        })}
                      </div>
                      {permissionReplayStep && (
                        <div
                          data-testid="model-publish-permission-sample"
                          className="mt-4 rounded border border-amber-100 bg-amber-50/80 p-3"
                        >
                          <div className="flex flex-wrap items-start justify-between gap-3">
                            <div>
                              <p className="text-xs font-semibold text-amber-900">{getPublishText('permissionTitle', locale)}</p>
                              <p className="mt-1 text-xs text-amber-800">
                                {getPublishText('permissionHint', locale)}</p>
                            </div>
                            <button
                              type="button"
                              data-testid="model-publish-run-permission-replay"
                              onClick={() => handlePublishReplay('permission')}
                              className="rounded border border-amber-300 bg-white px-3 py-1.5 text-xs font-medium text-amber-800 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                              disabled={publishReplayLoading}
                            >
                              {publishReplayLoading ? getPublishText('executing', locale) : getPublishText('executeSample', locale)}
                            </button>
                          </div>
                          <div className="mt-3 grid gap-3 md:grid-cols-2">
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('memberId', locale)}<input
                                data-testid="model-publish-permission-member-id"
                                aria-label="permission-replay-member-id"
                                type="number"
                                min="1"
                                value={publishReplaySampleMemberId}
                                onChange={(event) =>
                                  setPublishReplaySampleMemberId(event.target.value)
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('permissionCode', locale)}<input
                                data-testid="model-publish-permission-code"
                                aria-label="permission-replay-permission-code"
                                value={publishReplaySamplePermissionCode}
                                onChange={(event) =>
                                  setPublishReplaySamplePermissionCode(event.target.value)
                                }
                                placeholder={
                                  metadataString(permissionReplayStep.metadata, 'permissionCode') ||
                                  permissionReplayStep.sourceCode ||
                                  'model.order.approve'
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('recordPid', locale)}<input
                                data-testid="model-publish-permission-record-pid"
                                aria-label="permission-replay-record-pid"
                                value={publishReplaySampleRecordPid}
                                onChange={(event) =>
                                  setPublishReplaySampleRecordPid(event.target.value)
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700 md:col-span-2">
                              {getPublishText('recordJson', locale)}<textarea
                                data-testid="model-publish-permission-record-json"
                                aria-label="permission-replay-record-json"
                                value={publishReplaySampleRecordJson}
                                onChange={(event) =>
                                  setPublishReplaySampleRecordJson(event.target.value)
                                }
                                rows={4}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 font-mono text-sm text-gray-900"
                              />
                            </label>
                          </div>
                          {publishReplaySampleError && (
                            <p
                              data-testid="model-publish-permission-sample-error"
                              className="mt-2 text-xs text-red-700"
                            >
                              {publishReplaySampleError}
                            </p>
                          )}
                        </div>
                      )}
                      {workflowReplayStep && (
                        <div
                          data-testid="model-publish-workflow-sample"
                          className="mt-4 rounded border border-cyan-100 bg-cyan-50/80 p-3"
                        >
                          <div className="flex flex-wrap items-start justify-between gap-3">
                            <div>
                              <p className="text-xs font-semibold text-cyan-950">{getPublishText('workflowTitle', locale)}</p>
                              <p className="mt-1 text-xs text-cyan-900">
                                {getPublishText('workflowHint', locale)}</p>
                            </div>
                            <button
                              type="button"
                              data-testid="model-publish-run-workflow-replay"
                              onClick={() => handlePublishReplay('workflow')}
                              className="rounded border border-cyan-300 bg-white px-3 py-1.5 text-xs font-medium text-cyan-900 hover:bg-cyan-100 disabled:cursor-not-allowed disabled:opacity-60"
                              disabled={publishReplayLoading}
                            >
                              {publishReplayLoading ? getPublishText('executing', locale) : getPublishText('executeWorkflow', locale)}
                            </button>
                          </div>
                          <div className="mt-3 grid gap-3 md:grid-cols-2">
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('instanceId', locale)}<input
                                data-testid="model-publish-workflow-process-instance-id"
                                aria-label="workflow-replay-process-instance-id"
                                value={publishReplayWorkflowProcessInstanceId}
                                onChange={(event) =>
                                  setPublishReplayWorkflowProcessInstanceId(event.target.value)
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('processKey', locale)}<input
                                data-testid="model-publish-workflow-process-key"
                                aria-label="workflow-replay-process-key"
                                value={publishReplayWorkflowProcessKey}
                                onChange={(event) => setPublishReplayWorkflowProcessKey(event.target.value)}
                                placeholder={
                                  metadataString(workflowReplayStep.metadata, 'processKey') ||
                                  workflowReplayStep.sourceCode ||
                                  'approval_flow'
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('recordPid', locale)}<input
                                data-testid="model-publish-workflow-record-pid"
                                aria-label="workflow-replay-record-pid"
                                value={publishReplayWorkflowRecordPid}
                                onChange={(event) => setPublishReplayWorkflowRecordPid(event.target.value)}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700 md:col-span-2">
                              {getPublishText('recordJson', locale)}<textarea
                                data-testid="model-publish-workflow-record-json"
                                aria-label="workflow-replay-record-json"
                                value={publishReplayWorkflowRecordJson}
                                onChange={(event) => setPublishReplayWorkflowRecordJson(event.target.value)}
                                rows={3}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 font-mono text-sm text-gray-900"
                              />
                            </label>
                          </div>
                          {publishReplayWorkflowSampleError && (
                            <p
                              data-testid="model-publish-workflow-sample-error"
                              className="mt-2 text-xs text-red-700"
                            >
                              {publishReplayWorkflowSampleError}
                            </p>
                          )}
                        </div>
                      )}
                      {slaNodeReplayStep && (
                        <div
                          data-testid="model-publish-sla-node-sample"
                          className="mt-4 rounded border border-sky-100 bg-sky-50/80 p-3"
                        >
                          <div className="flex flex-wrap items-start justify-between gap-3">
                            <div>
                              <p className="text-xs font-semibold text-sky-900">{getPublishText('slaTitle', locale)}</p>
                              <p className="mt-1 text-xs text-sky-800">
                                {getPublishText('slaHint', locale)}</p>
                            </div>
                            <button
                              type="button"
                              data-testid="model-publish-run-sla-node-replay"
                              onClick={() => handlePublishReplay('sla-node')}
                              className="rounded border border-sky-300 bg-white px-3 py-1.5 text-xs font-medium text-sky-800 hover:bg-sky-100 disabled:cursor-not-allowed disabled:opacity-60"
                              disabled={publishReplayLoading}
                            >
                              {publishReplayLoading ? getPublishText('executing', locale) : getPublishText('executeWorkflow', locale)}
                            </button>
                          </div>
                          <div className="mt-3 grid gap-3 md:grid-cols-2">
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('instanceId', locale)}<input
                                data-testid="model-publish-sla-process-instance-id"
                                aria-label="sla-node-replay-process-instance-id"
                                value={publishReplaySlaProcessInstanceId}
                                onChange={(event) =>
                                  setPublishReplaySlaProcessInstanceId(event.target.value)
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('tenantId', locale)}<input
                                data-testid="model-publish-sla-tenant-id"
                                aria-label="sla-node-replay-tenant-id"
                                type="number"
                                min="1"
                                value={publishReplaySlaTenantId}
                                onChange={(event) => setPublishReplaySlaTenantId(event.target.value)}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('taskId', locale)}<input
                                data-testid="model-publish-sla-task-id"
                                aria-label="sla-node-replay-task-id"
                                value={publishReplaySlaTaskId}
                                onChange={(event) => setPublishReplaySlaTaskId(event.target.value)}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700">
                              {getPublishText('processKey', locale)}<input
                                data-testid="model-publish-sla-process-key"
                                aria-label="sla-node-replay-process-key"
                                value={publishReplaySlaProcessKey}
                                onChange={(event) => setPublishReplaySlaProcessKey(event.target.value)}
                                placeholder={
                                  metadataString(slaNodeReplayStep.metadata, 'processKey') ||
                                  'approval_flow'
                                }
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900"
                              />
                            </label>
                            <label className="block text-xs font-medium text-gray-700 md:col-span-2">
                              {getPublishText('recordJson', locale)}<textarea
                                data-testid="model-publish-sla-record-json"
                                aria-label="sla-node-replay-record-json"
                                value={publishReplaySlaRecordJson}
                                onChange={(event) => setPublishReplaySlaRecordJson(event.target.value)}
                                rows={3}
                                className="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 font-mono text-sm text-gray-900"
                              />
                            </label>
                          </div>
                          {publishReplaySlaSampleError && (
                            <p
                              data-testid="model-publish-sla-node-sample-error"
                              className="mt-2 text-xs text-red-700"
                            >
                              {publishReplaySlaSampleError}
                            </p>
                          )}
                        </div>
                      )}
                      {publishReplayReport && (
                        <div
                          data-testid="model-publish-replay-report"
                          className="mt-4 rounded border border-blue-100 bg-blue-50/70 p-3"
                        >
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <p className="text-xs font-semibold text-blue-900">{getPublishText('reportTitle', locale)}</p>
                            <div className="flex flex-wrap gap-2 text-xs text-blue-800">
                              <span>{getPublishText('total', locale)}{' '}{publishReplayReport.totalCount ?? 0}</span>
                              <span>{getPublishText('executed', locale)}{' '}{publishReplayReport.executedCount ?? 0}</span>
                              <span>{getPublishText('manual', locale)}{' '}{publishReplayReport.manualCount ?? 0}</span>
                              <span>{getPublishText('needsSample', locale)}{' '}{publishReplayReport.needsInputCount ?? 0}</span>
                            </div>
                          </div>
                          <div className="mt-3 space-y-2">
                            {(publishReplayReport.results || []).map((result, index) => (
                              <ModelPublishReplayResultCard
                                key={'replay-result-' + (result.step?.consumerType || 'consumer') + '-' +
                                  (result.step?.sourcePid || result.step?.sourceCode || index)}
                                result={result} locale={locale} />
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  ) : null}

                  {publishPreview.governance.migrationPlan && (
                    <div className="mt-4">
                      <p className="text-xs font-semibold text-gray-600">{getPolicyHeading('migrationHeading', locale)}</p>
                      <p className="mt-1 text-sm text-gray-700">
                        {getMigrationPlanMessage(publishPreview.governance, locale)}
                      </p>
                    </div>
                  )}

                  {publishPreview.governance.historicalVersionPolicy && (
                    <div className="mt-4">
                      <p className="text-xs font-semibold text-gray-600">{getPolicyHeading('historyHeading', locale)}</p>
                      <p className="mt-1 text-sm text-gray-700">
                        {getHistoricalPolicyMessage(publishPreview.governance, locale)}
                      </p>
                    </div>
                  )}

                  {publishPreview.governance.warnings?.length ? (
                    <ul className="mt-3 list-inside list-disc text-sm text-amber-700">
                      {publishPreview.governance.warnings.map((warning, index) => (
                        <li key={index}>{warning}</li>
                      ))}
                    </ul>
                  ) : null}

                  {publishPreview.governance.requiresAcknowledgement && (
                    <label className="mt-4 flex items-start gap-2 text-sm text-gray-800">
                      <input
                        data-testid="model-publish-impact-ack"
                        aria-label="model-publish-impact-ack"
                        type="checkbox"
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600"
                        checked={publishImpactAcknowledged}
                        onChange={(event) => setPublishImpactAcknowledged(event.target.checked)}
                      />
                      <span>{getPublishText('acknowledge', locale)}</span>
                    </label>
                  )}
                </div>
              )}

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
                    {getPublishText('riskLevel', locale)}{' '}{publishPreview.riskAssessment.level}
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
                  {publishPreview.ddlStatements?.join('\n\n') || getPublishText('noDdl', locale)}
                </pre>
              </div>

              {publishPreview.affectedTables?.length > 0 && (
                <div className="mt-3 text-sm text-gray-600">
                  <span className="font-medium">{getPublishText('affectedTables', locale)}{' '}</span>
                  {publishPreview.affectedTables.join(', ')}
                </div>
              )}
            </div>

            <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
              <button
                onClick={() => {
                  setShowPublishConfirm(false);
                  setPublishPreview(null);
                  setPublishReplayReport(null);
                  setPublishImpactAcknowledged(false);
                  setPublishReplaySampleMemberId('');
                  setPublishReplaySamplePermissionCode('');
                  setPublishReplaySampleRecordPid('');
                  setPublishReplaySampleRecordJson('{}');
                  setPublishReplaySampleError(null);
                  setPublishReplaySlaProcessInstanceId('');
                  setPublishReplaySlaTaskId('');
                  setPublishReplaySlaTenantId('');
                  setPublishReplaySlaProcessKey('');
                  setPublishReplaySlaRecordJson('{}');
                  setPublishReplaySlaSampleError(null);
                  setPublishReplayWorkflowProcessInstanceId('');
                  setPublishReplayWorkflowProcessKey('');
                  setPublishReplayWorkflowRecordPid('');
                  setPublishReplayWorkflowRecordJson('{}');
                  setPublishReplayWorkflowSampleError(null);
                }}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
                disabled={publishLoading}
              >
                {getPublishText('cancel', locale)}</button>
              <button
                data-testid="model-publish-confirm"
                onClick={handlePublishConfirm}
                className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:bg-gray-300"
                disabled={
                  publishLoading ||
                  (Boolean(publishPreview.governance?.requiresAcknowledgement) &&
                    !publishImpactAcknowledged)
                }
              >
                {publishLoading ? getPublishText('publishing', locale) : getPublishText('confirm', locale)}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
