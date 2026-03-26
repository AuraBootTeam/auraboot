/**
 * Named Query Edit / Detail Page
 *
 * Displays and edits named query with basic info, SQL config, field management, and test panel
 */

import React, { useState, useCallback, useEffect } from 'react';
import { useNavigate, useParams, useLoaderData, useLocation } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { namedQueryService } from '~/services/namedQueryService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import type {
  NamedQueryDTO,
  NamedQueryUpdateRequest,
  NamedQueryFieldDTO,
  NamedQueryFieldRequest,
  NamedQueryTestRequest,
  NamedQueryTestResult,
  NamedQueryPolicyDTO,
  NamedQueryVersionDTO,
} from '~/services/namedQueryService';
import { OPERATOR_LABELS } from './components/constants';
import SqlEditor from './components/SqlEditor';
import FieldForm from './components/FieldForm';
import FieldInference from './components/FieldInference';
import ConditionBuilder from './components/ConditionBuilder';
import OrderBuilder from './components/OrderBuilder';
import ExportPanel from './components/ExportPanel';

type TabType = 'basic' | 'fields' | 'test' | 'policy' | 'versions';

/**
 * Loader function
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Named Query PID is required', { status: 400 });
  }

  try {
    const query = await namedQueryService.findByPid(pid, request);
    let fields: NamedQueryFieldDTO[] = [];
    try {
      fields = await namedQueryService.getFields(query.code, request);
    } catch {
      // Fields may not exist yet
    }
    return { query, fields };
  } catch (error) {
    console.error('Failed to load named query:', error);
    throw new Response('Named query not found', { status: 404 });
  }
};

/**
 * Named Query Edit Page Component
 */
export default function NamedQueryEditPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { pid } = useParams();
  const { query: initialQuery, fields: initialFields } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  /**
   * Get initial tab from URL hash
   */
  const getInitialTab = (): TabType => {
    const hash = location.hash.replace('#', '');
    const validTabs: TabType[] = ['basic', 'fields', 'test', 'policy', 'versions'];
    return validTabs.includes(hash as TabType) ? (hash as TabType) : 'basic';
  };

  const [activeTab, setActiveTab] = useState<TabType>(getInitialTab());
  const [query, setQuery] = useState<NamedQueryDTO>(initialQuery);
  const [loading, setLoading] = useState(false);

  // Basic info form
  const [formData, setFormData] = useState<NamedQueryUpdateRequest>({
    title: initialQuery.title,
    description: initialQuery.description || '',
    fromSql: initialQuery.fromSql,
  });
  const [baseWhereText, setBaseWhereText] = useState(
    initialQuery.baseWhere ? JSON.stringify(initialQuery.baseWhere, null, 2) : '',
  );
  const [defaultOrderText, setDefaultOrderText] = useState(
    initialQuery.defaultOrder ? JSON.stringify(initialQuery.defaultOrder, null, 2) : '',
  );

  // Field management
  const [fields, setFields] = useState<NamedQueryFieldDTO[]>(initialFields);
  const [editingField, setEditingField] = useState<NamedQueryFieldDTO | null>(null);
  const [showAddField, setShowAddField] = useState(false);

  // Test panel
  const [testRequest, setTestRequest] = useState<NamedQueryTestRequest>({
    pageNum: 1,
    pageSize: 10,
    params: {},
  });
  const [testWhereText, setTestWhereText] = useState('');
  const [testOrderText, setTestOrderText] = useState('');
  const [testResult, setTestResult] = useState<NamedQueryTestResult | null>(null);
  const [testLoading, setTestLoading] = useState(false);

  // Version history
  const [versions, setVersions] = useState<NamedQueryVersionDTO[]>([]);
  const [versionsLoaded, setVersionsLoaded] = useState(false);
  const [selectedVersion, setSelectedVersion] = useState<NamedQueryVersionDTO | null>(null);

  // Policy config
  const [policyData, setPolicyData] = useState<NamedQueryPolicyDTO>(
    initialQuery.policy || {
      maxRows: 5000,
      timeoutMs: 30000,
      rateLimitPerMinute: 60,
      cacheTtlSeconds: 0,
      exportMaxRows: 50000,
      sandboxMaxRows: 100,
    },
  );

  /**
   * Listen to hash changes
   */
  useEffect(() => {
    const handleHashChange = () => {
      const newTab = getInitialTab();
      setActiveTab(newTab);
    };

    window.addEventListener('hashchange', handleHashChange);
    return () => window.removeEventListener('hashchange', handleHashChange);
  }, [location]);

  /**
   * Lazy-load versions when activeTab switches to 'versions'
   */
  useEffect(() => {
    if (activeTab === 'versions' && !versionsLoaded) {
      namedQueryService
        .getVersions(query.code)
        .then((data) => {
          setVersions(data);
          setVersionsLoaded(true);
        })
        .catch((err) => {
          console.error('Failed to load versions:', err);
          setVersionsLoaded(true);
        });
    }
  }, [activeTab, query.code, versionsLoaded]);

  /**
   * Handle tab change
   */
  const handleTabChange = useCallback(
    (tab: TabType) => {
      setActiveTab(tab);
      window.location.hash = tab;
      // Lazy-load versions when tab is first opened
      if (tab === 'versions' && !versionsLoaded) {
        namedQueryService
          .getVersions(query.code)
          .then((data) => {
            setVersions(data);
            setVersionsLoaded(true);
          })
          .catch((err) => {
            console.error('Failed to load versions:', err);
            setVersionsLoaded(true);
          });
      }
    },
    [query.code, versionsLoaded],
  );

  /**
   * Handle form field change
   */
  const handleFieldChange = useCallback((field: keyof NamedQueryUpdateRequest, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Handle save basic info
   */
  const handleSave = useCallback(async () => {
    if (!formData.title || !formData.fromSql) {
      showErrorToast('请填写必填字段');
      return;
    }

    // Parse JSON fields
    let baseWhere: any = undefined;
    let defaultOrder: any = undefined;

    if (baseWhereText.trim()) {
      try {
        baseWhere = JSON.parse(baseWhereText);
      } catch {
        showErrorToast('baseWhere JSON 格式不正确');
        return;
      }
    }

    if (defaultOrderText.trim()) {
      try {
        defaultOrder = JSON.parse(defaultOrderText);
      } catch {
        showErrorToast('defaultOrder JSON 格式不正确');
        return;
      }
    }

    setLoading(true);
    try {
      const request: NamedQueryUpdateRequest = {
        ...formData,
        baseWhere,
        defaultOrder,
      };

      const result = await namedQueryService.update(pid!, request);
      setQuery(result);
      showSuccessToast('保存成功');
    } catch (error: any) {
      console.error('Failed to update named query:', error);
      showErrorToast(error.message || '保存失败');
    } finally {
      setLoading(false);
    }
  }, [pid, formData, baseWhereText, defaultOrderText, showSuccessToast, showErrorToast]);

  /**
   * Handle validate SQL
   */
  const handleValidate = useCallback(async () => {
    if (!formData.fromSql) {
      showErrorToast('请先输入 FROM SQL');
      return;
    }

    let baseWhere: any = undefined;
    if (baseWhereText.trim()) {
      try {
        baseWhere = JSON.parse(baseWhereText);
      } catch {
        showErrorToast('baseWhere JSON 格式不正确');
        return;
      }
    }

    try {
      const result = await namedQueryService.validate({
        fromSql: formData.fromSql,
        baseWhere,
      });
      if (result.valid) {
        showSuccessToast('SQL 验证通过');
      } else {
        showErrorToast(result.message || 'SQL 验证失败');
      }
    } catch (error: any) {
      showErrorToast(error.message || 'SQL 验证失败');
    }
  }, [formData.fromSql, baseWhereText, showSuccessToast, showErrorToast]);

  /**
   * Handle delete query
   */
  const handleDeleteQuery = useCallback(async () => {
    const confirmed = await confirmDialog({
      content: `确定要删除查询 "${query.title}" 吗？此操作不可恢复。`,
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await namedQueryService.delete(pid!);
      showSuccessToast('删除成功');
      navigate('/meta/named-queries');
    } catch (error) {
      console.error('Failed to delete named query:', error);
      showErrorToast('删除失败');
    } finally {
      setLoading(false);
    }
  }, [pid, query, navigate, showSuccessToast, showErrorToast]);

  /**
   * Handle status transition
   */
  const handleStatusTransition = useCallback(
    async (targetStatus: string) => {
      const statusLabels: Record<string, string> = {
        draft: '草稿',
        testing: '测试中',
        published: '已发布',
        deprecated: '已废弃',
        archived: '已归档',
      };

      setLoading(true);
      try {
        const result = await namedQueryService.updateStatus(pid!, targetStatus);
        setQuery(result);
        showSuccessToast(`已转为${statusLabels[targetStatus]}`);
      } catch (error: any) {
        console.error('Failed to update status:', error);
        showErrorToast(error.message || '状态更新失败');
      } finally {
        setLoading(false);
      }
    },
    [pid, showSuccessToast, showErrorToast],
  );

  /**
   * Get available status transitions
   */
  const getStatusTransitions = (): { label: string; target: string; variant: string }[] => {
    const transitions: Record<string, { label: string; target: string; variant: string }[]> = {
      draft: [
        {
          label: '开始测试',
          target: 'testing',
          variant: 'bg-yellow-600 text-white hover:bg-yellow-700',
        },
        {
          label: '归档',
          target: 'archived',
          variant: 'border border-red-300 text-red-700 hover:bg-red-50',
        },
      ],
      testing: [
        {
          label: '发布',
          target: 'published',
          variant: 'bg-green-600 text-white hover:bg-green-700',
        },
        {
          label: '退回草稿',
          target: 'draft',
          variant: 'border border-gray-300 text-gray-700 hover:bg-gray-50',
        },
      ],
      published: [
        {
          label: '废弃',
          target: 'deprecated',
          variant: 'border border-orange-300 text-orange-700 hover:bg-orange-50',
        },
      ],
      deprecated: [
        {
          label: '重新发布',
          target: 'published',
          variant: 'bg-green-600 text-white hover:bg-green-700',
        },
        {
          label: '归档',
          target: 'archived',
          variant: 'border border-red-300 text-red-700 hover:bg-red-50',
        },
      ],
      archived: [
        {
          label: '重新打开',
          target: 'draft',
          variant: 'border border-blue-300 text-blue-700 hover:bg-blue-50',
        },
      ],
    };
    return transitions[query.status] || [];
  };

  const isFrozen =
    query.frozen === true || ['published', 'deprecated', 'archived'].includes(query.status);

  // ============================================================================
  // Field management handlers
  // ============================================================================

  /**
   * Handle update field
   */
  const handleUpdateField = useCallback(
    async (fieldCode: string, field: NamedQueryFieldRequest) => {
      setLoading(true);
      try {
        const result = await namedQueryService.updateField(query.code, fieldCode, field);
        setFields((prev) => prev.map((f) => (f.fieldCode === fieldCode ? result : f)));
        setEditingField(null);
        showSuccessToast('更新字段成功');
      } catch (error: any) {
        console.error('Failed to update field:', error);
        showErrorToast(error.message || '更新字段失败');
      } finally {
        setLoading(false);
      }
    },
    [query.code, showSuccessToast, showErrorToast],
  );

  /**
   * Handle delete field
   */
  const handleDeleteField = useCallback(
    async (fieldCode: string) => {
      const confirmed = await confirmDialog({
        content: `确定要删除字段 "${fieldCode}" 吗？`,
        variant: 'danger',
      });
      if (!confirmed) return;

      setLoading(true);
      try {
        await namedQueryService.deleteField(query.code, fieldCode);
        setFields((prev) => prev.filter((f) => f.fieldCode !== fieldCode));
        showSuccessToast('删除字段成功');
      } catch (error: any) {
        console.error('Failed to delete field:', error);
        showErrorToast(error.message || '删除字段失败');
      } finally {
        setLoading(false);
      }
    },
    [query.code, showSuccessToast, showErrorToast],
  );

  // ============================================================================
  // Test panel handlers
  // ============================================================================

  /**
   * Handle execute test
   */
  const handleExecuteTest = useCallback(async () => {
    let where: any = undefined;
    let orderBy: any = undefined;

    if (testWhereText.trim()) {
      try {
        where = JSON.parse(testWhereText);
      } catch {
        showErrorToast('WHERE JSON 格式不正确');
        return;
      }
    }

    if (testOrderText.trim()) {
      try {
        orderBy = JSON.parse(testOrderText);
      } catch {
        showErrorToast('Order JSON 格式不正确');
        return;
      }
    }

    setTestLoading(true);
    try {
      const request: NamedQueryTestRequest = {
        ...testRequest,
        where,
        orderBy,
      };

      const result = await namedQueryService.testQuery(pid!, request);
      setTestResult(result);
      showSuccessToast(
        `查询完成，${result.resultCount ?? 0} 条结果，耗时 ${result.executionTimeMs ?? 0}ms`,
      );
    } catch (error: any) {
      console.error('Failed to test query:', error);
      showErrorToast(error.message || '测试执行失败');
    } finally {
      setTestLoading(false);
    }
  }, [pid, testRequest, testWhereText, testOrderText, showSuccessToast, showErrorToast]);

  /**
   * Handle save policy
   */
  const handleSavePolicy = useCallback(async () => {
    setLoading(true);
    try {
      const result = await namedQueryService.update(pid!, { policy: policyData });
      setQuery(result);
      showSuccessToast('策略配置已保存');
    } catch (error: any) {
      console.error('Failed to save policy:', error);
      showErrorToast(error.message || '策略保存失败');
    } finally {
      setLoading(false);
    }
  }, [pid, policyData, showSuccessToast, showErrorToast]);

  /**
   * Get status badge
   */
  const getStatusBadge = (status: string) => {
    const config: Record<string, { color: string; label: string }> = {
      draft: { color: 'bg-gray-100 text-gray-800 border-gray-200', label: '草稿' },
      testing: { color: 'bg-yellow-100 text-yellow-800 border-yellow-200', label: '测试中' },
      published: { color: 'bg-green-100 text-green-800 border-green-200', label: '已发布' },
      deprecated: { color: 'bg-orange-100 text-orange-800 border-orange-200', label: '已废弃' },
      archived: { color: 'bg-red-100 text-red-800 border-red-200', label: '已归档' },
    };
    const c = config[status] || {
      color: 'bg-gray-100 text-gray-800 border-gray-200',
      label: status,
    };
    return (
      <span
        className={`inline-flex items-center rounded-md border px-2.5 py-1 text-sm font-medium ${c.color}`}
      >
        {c.label}
      </span>
    );
  };

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{query.title}</h1>
          <p className="mt-1 text-sm text-gray-500">
            查询编码: <span className="font-mono text-blue-600">{query.code}</span>
            {query.description && ` - ${query.description}`}
          </p>
        </div>

        <div className="flex items-center gap-2">
          {getStatusBadge(query.status)}
          {isFrozen && (
            <span className="rounded bg-orange-50 px-2 py-1 text-xs text-orange-600">
              SQL/字段已冻结
            </span>
          )}
          {getStatusTransitions().map((t) => (
            <button
              key={t.target}
              onClick={() => handleStatusTransition(t.target)}
              className={`rounded-md px-4 py-2 text-sm font-medium ${t.variant}`}
              disabled={loading}
            >
              {t.label}
            </button>
          ))}
          {(query.status === 'draft' || query.status === 'archived') && (
            <button
              onClick={handleDeleteQuery}
              className="rounded-md border border-red-300 px-4 py-2 text-red-700 hover:bg-red-50"
              disabled={loading}
            >
              删除
            </button>
          )}
          <button
            onClick={() => navigate('/meta/named-queries')}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            data-testid="form-btn-back"
          >
            返回列表
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="rounded-lg bg-white shadow">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex">
            <button
              onClick={() => handleTabChange('basic')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'basic'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
              data-testid="tab-basic"
            >
              基本信息 / SQL 配置
            </button>
            <button
              onClick={() => handleTabChange('fields')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'fields'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
              data-testid="tab-fields"
            >
              字段管理 ({fields.length})
            </button>
            <button
              onClick={() => handleTabChange('test')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'test'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
              data-testid="tab-test"
            >
              测试执行
            </button>
            <button
              onClick={() => handleTabChange('policy')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'policy'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
              data-testid="tab-policy"
            >
              执行策略
            </button>
            <button
              onClick={() => handleTabChange('versions')}
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'versions'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
              data-testid="tab-versions"
            >
              版本历史 {query.currentVersion ? `(v${query.currentVersion})` : ''}
            </button>
          </nav>
        </div>

        <div className="p-6">
          {/* ================================================================ */}
          {/* Basic Info / SQL Config Tab                                      */}
          {/* ================================================================ */}
          {activeTab === 'basic' && (
            <div className="space-y-6">
              {/* Basic Info */}
              <div>
                <h3 className="mb-4 text-base font-medium text-gray-900">基本信息</h3>
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">查询编码</label>
                    <input
                      type="text"
                      value={query.code}
                      disabled
                      className="w-full cursor-not-allowed rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-500"
                    />
                    <p className="mt-1 text-xs text-gray-500">查询编码不可修改</p>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      查询标题 <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formData.title}
                      onChange={(e) => handleFieldChange('title', e.target.value)}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                  <div className="md:col-span-2">
                    <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
                    <textarea
                      value={formData.description}
                      onChange={(e) => handleFieldChange('description', e.target.value)}
                      rows={2}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                </div>
              </div>

              {/* SQL Config */}
              <div>
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-base font-medium text-gray-900">
                    SQL 配置
                    {isFrozen && (
                      <span className="ml-2 text-xs text-orange-600">(只读 — 查询已冻结)</span>
                    )}
                  </h3>
                  {!isFrozen && (
                    <button
                      type="button"
                      onClick={handleValidate}
                      className="rounded-md border border-blue-300 px-3 py-1.5 text-sm text-blue-700 hover:bg-blue-50"
                    >
                      验证 SQL
                    </button>
                  )}
                </div>
                <div className="space-y-4">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      FROM SQL <span className="text-red-500">*</span>
                    </label>
                    <SqlEditor
                      value={formData.fromSql || ''}
                      onChange={(val) => handleFieldChange('fromSql', val)}
                      readOnly={isFrozen}
                      height="160px"
                      placeholder="e.g. ab_user u LEFT JOIN ab_department d ON u.dept_id = d.id"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      Base WHERE (JSON)
                    </label>
                    <textarea
                      value={baseWhereText}
                      onChange={(e) => setBaseWhereText(e.target.value)}
                      rows={3}
                      disabled={isFrozen}
                      className={`w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm ${
                        isFrozen
                          ? 'cursor-not-allowed bg-gray-50 text-gray-500'
                          : 'focus:ring-2 focus:ring-blue-500 focus:outline-none'
                      }`}
                      placeholder='{"field": "status", "op": "EQ", "value": "active"}'
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      Default Order (JSON)
                    </label>
                    <textarea
                      value={defaultOrderText}
                      onChange={(e) => setDefaultOrderText(e.target.value)}
                      rows={3}
                      disabled={isFrozen}
                      className={`w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm ${
                        isFrozen
                          ? 'cursor-not-allowed bg-gray-50 text-gray-500'
                          : 'focus:ring-2 focus:ring-blue-500 focus:outline-none'
                      }`}
                      placeholder='[{"field": "created_at", "direction": "desc"}]'
                    />
                  </div>
                </div>
              </div>

              {/* Save button */}
              <div className="flex justify-end gap-3 border-t pt-4">
                <button
                  onClick={handleSave}
                  className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
                  disabled={loading}
                  data-testid="form-btn-save"
                >
                  {loading ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          )}

          {/* ================================================================ */}
          {/* Fields Tab                                                       */}
          {/* ================================================================ */}
          {activeTab === 'fields' && (
            <div>
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-base font-medium text-gray-900">字段列表</h3>
                <div className="flex gap-2">
                  <FieldInference
                    pid={pid!}
                    queryCode={query.code}
                    existingFields={fields}
                    onFieldsAdded={(added) => setFields((prev) => [...prev, ...added])}
                    showToast={(msg, type) =>
                      type === 'success' ? showSuccessToast(msg) : showErrorToast(msg)
                    }
                  />
                  <button
                    onClick={() => setShowAddField(true)}
                    className="inline-flex items-center rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
                  >
                    <svg
                      className="mr-1.5 -ml-0.5 h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 4v16m8-8H4"
                      />
                    </svg>
                    添加字段
                  </button>
                </div>
              </div>

              {/* FieldForm Drawer for add/edit */}
              {showAddField && (
                <FieldForm
                  mode="add"
                  onSave={async (fieldData) => {
                    if (!fieldData.fieldCode || !fieldData.columnExpr) {
                      showErrorToast('请填写字段编码和列表达式');
                      return;
                    }
                    setLoading(true);
                    try {
                      const result = await namedQueryService.addField(query.code, fieldData);
                      setFields((prev) => [...prev, result]);
                      setShowAddField(false);
                      showSuccessToast('添加字段成功');
                    } catch (error: any) {
                      showErrorToast(error.message || '添加字段失败');
                    } finally {
                      setLoading(false);
                    }
                  }}
                  onCancel={() => setShowAddField(false)}
                  loading={loading}
                />
              )}
              {editingField && (
                <FieldForm
                  mode="edit"
                  initialData={editingField}
                  onSave={(updated) => handleUpdateField(editingField.fieldCode, updated)}
                  onCancel={() => setEditingField(null)}
                  loading={loading}
                />
              )}

              {/* Fields Table */}
              {fields.length === 0 ? (
                <div className="rounded-md border border-gray-200 py-12 text-center text-gray-500">
                  暂无字段，请点击"添加字段"按钮添加
                </div>
              ) : (
                <div className="overflow-x-auto rounded-md border border-gray-200">
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          序号
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          字段编码
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          显示名称
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          列表达式
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          数据类型
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          操作符
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          属性
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          操作
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200 bg-white">
                      {fields.map((field, idx) => (
                        <tr key={field.fieldCode} className="hover:bg-gray-50">
                          <td className="px-4 py-3 text-sm text-gray-400">
                            {field.sortOrder ?? idx + 1}
                          </td>
                          <td className="px-4 py-3 font-mono text-sm text-gray-900">
                            {field.fieldCode}
                          </td>
                          <td className="px-4 py-3 text-sm text-gray-700">
                            {field.displayName || '-'}
                          </td>
                          <td className="px-4 py-3 font-mono text-sm text-gray-700">
                            {field.columnExpr}
                          </td>
                          <td className="px-4 py-3 text-sm">
                            <span className="inline-flex rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-800">
                              {field.dataType}
                            </span>
                          </td>
                          <td className="px-4 py-3 text-sm">
                            <div className="flex flex-wrap gap-1">
                              {(field.operators || []).slice(0, 3).map((op) => (
                                <span
                                  key={op}
                                  className="inline-flex rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700"
                                  title={op}
                                >
                                  {OPERATOR_LABELS[op] || op}
                                </span>
                              ))}
                              {(field.operators || []).length > 3 && (
                                <span className="inline-flex rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
                                  +{(field.operators || []).length - 3}
                                </span>
                              )}
                            </div>
                          </td>
                          <td className="px-4 py-3 text-sm text-gray-500">
                            <div className="flex gap-2">
                              {field.sortable && (
                                <span className="rounded bg-green-50 px-1.5 py-0.5 text-xs text-green-700">
                                  排序
                                </span>
                              )}
                              {field.searchable && (
                                <span className="rounded bg-purple-50 px-1.5 py-0.5 text-xs text-purple-700">
                                  搜索
                                </span>
                              )}
                              {field.required && (
                                <span className="rounded bg-red-50 px-1.5 py-0.5 text-xs text-red-700">
                                  必填
                                </span>
                              )}
                            </div>
                          </td>
                          <td className="px-4 py-3 text-sm">
                            <button
                              onClick={() => setEditingField({ ...field })}
                              className="mr-2 text-blue-600 hover:text-blue-900"
                            >
                              编辑
                            </button>
                            <button
                              onClick={() => handleDeleteField(field.fieldCode)}
                              className="text-red-600 hover:text-red-900"
                            >
                              删除
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* ================================================================ */}
          {/* Test Tab                                                         */}
          {/* ================================================================ */}
          {activeTab === 'test' && (
            <div className="space-y-6">
              <div>
                <h3 className="mb-4 text-base font-medium text-gray-900">测试查询执行</h3>

                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <ConditionBuilder
                    fields={fields}
                    value={testWhereText}
                    onChange={setTestWhereText}
                  />
                  <OrderBuilder fields={fields} value={testOrderText} onChange={setTestOrderText} />
                </div>

                <div className="mt-4 flex items-center gap-4">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">页码</label>
                    <input
                      type="number"
                      min={1}
                      value={testRequest.pageNum}
                      onChange={(e) =>
                        setTestRequest((prev) => ({
                          ...prev,
                          pageNum: parseInt(e.target.value) || 1,
                        }))
                      }
                      className="w-24 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">每页条数</label>
                    <select
                      value={testRequest.pageSize}
                      onChange={(e) =>
                        setTestRequest((prev) => ({ ...prev, pageSize: parseInt(e.target.value) }))
                      }
                      className="w-24 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    >
                      <option value={5}>5</option>
                      <option value={10}>10</option>
                      <option value={20}>20</option>
                      <option value={50}>50</option>
                    </select>
                  </div>
                  <div className="flex items-end">
                    <button
                      onClick={handleExecuteTest}
                      className="rounded-md bg-purple-600 px-4 py-2 text-white hover:bg-purple-700 disabled:opacity-50"
                      disabled={testLoading}
                    >
                      {testLoading ? '执行中...' : '执行查询'}
                    </button>
                  </div>
                </div>
              </div>

              {/* Test Result */}
              {testResult &&
                (() => {
                  const rows = testResult.sampleData || [];
                  const columns = rows.length > 0 ? Object.keys(rows[0]) : [];
                  return (
                    <div>
                      <div className="mb-3 flex items-center justify-between">
                        <h3 className="text-base font-medium text-gray-900">查询结果</h3>
                        <div className="text-sm text-gray-500">
                          共 {testResult.resultCount ?? 0} 条，耗时{' '}
                          {testResult.executionTimeMs ?? 0}ms
                        </div>
                      </div>

                      {testResult.warnings && testResult.warnings.length > 0 && (
                        <div className="mb-3 rounded-md border border-yellow-200 bg-yellow-50 p-3 text-sm text-yellow-800">
                          {testResult.warnings.map((w, i) => (
                            <div key={i}>{w}</div>
                          ))}
                        </div>
                      )}

                      {testResult.executedSql && (
                        <details className="mb-3">
                          <summary className="cursor-pointer text-sm text-gray-500 hover:text-gray-700">
                            查看执行的 SQL
                          </summary>
                          <pre className="mt-2 rounded-md border border-gray-200 bg-gray-50 p-3 font-mono text-xs whitespace-pre-wrap">
                            {testResult.executedSql}
                          </pre>
                        </details>
                      )}

                      {rows.length === 0 ? (
                        <div className="rounded-md border border-gray-200 py-8 text-center text-gray-500">
                          无结果
                        </div>
                      ) : (
                        <div className="overflow-x-auto rounded-md border border-gray-200">
                          <table className="min-w-full divide-y divide-gray-200">
                            <thead className="bg-gray-50">
                              <tr>
                                {columns.map((col) => (
                                  <th
                                    key={col}
                                    className="px-4 py-3 text-left text-xs font-medium whitespace-nowrap text-gray-500 uppercase"
                                  >
                                    {col}
                                  </th>
                                ))}
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-200 bg-white">
                              {rows.map((row, rowIdx) => (
                                <tr key={rowIdx} className="hover:bg-gray-50">
                                  {columns.map((col) => (
                                    <td
                                      key={col}
                                      className="px-4 py-3 text-sm whitespace-nowrap text-gray-700"
                                    >
                                      {row[col] != null ? String(row[col]) : '-'}
                                    </td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  );
                })()}

              {/* Export Panel */}
              {testResult && (
                <ExportPanel
                  queryCode={query.code}
                  testResult={testResult}
                  whereJson={testWhereText}
                  orderJson={testOrderText}
                />
              )}
            </div>
          )}

          {/* ================================================================ */}
          {/* Policy Tab                                                       */}
          {/* ================================================================ */}
          {/* ================================================================ */}
          {/* Versions Tab                                                     */}
          {/* ================================================================ */}
          {activeTab === 'versions' && (
            <div className="space-y-6">
              <div>
                <h3 className="mb-4 text-base font-medium text-gray-900">版本历史</h3>
                <p className="mb-4 text-sm text-gray-500">
                  每次发布时自动创建版本快照，包含 SQL 定义和字段配置。
                </p>

                {!versionsLoaded ? (
                  <div className="py-8 text-center text-gray-500">加载中...</div>
                ) : versions.length === 0 ? (
                  <div className="rounded-md border border-gray-200 py-8 text-center text-gray-500">
                    暂无版本记录。首次发布后将自动创建版本快照。
                  </div>
                ) : (
                  <div className="space-y-4">
                    {/* Version list */}
                    <div className="divide-y divide-gray-200 rounded-md border border-gray-200">
                      {versions.map((v) => (
                        <div
                          key={v.pid}
                          className={`cursor-pointer p-4 hover:bg-gray-50 ${
                            selectedVersion?.pid === v.pid
                              ? 'border-l-4 border-l-blue-500 bg-blue-50'
                              : ''
                          }`}
                          onClick={() =>
                            setSelectedVersion(selectedVersion?.pid === v.pid ? null : v)
                          }
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                              <span className="inline-flex items-center rounded bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
                                v{v.versionNo}
                              </span>
                              <span className="text-sm text-gray-900">
                                {v.description || '无描述'}
                              </span>
                            </div>
                            <div className="text-xs text-gray-500">
                              {v.publishedAt
                                ? new Date(v.publishedAt).toLocaleString('zh-CN')
                                : '-'}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>

                    {/* Version detail */}
                    {selectedVersion && (
                      <div className="rounded-md border border-gray-200 bg-gray-50 p-4">
                        <h4 className="mb-3 text-sm font-medium text-gray-900">
                          版本 v{selectedVersion.versionNo} 详情
                        </h4>
                        <div className="space-y-3">
                          <div>
                            <label className="mb-1 block text-xs font-medium text-gray-500">
                              FROM SQL
                            </label>
                            <pre className="rounded border border-gray-200 bg-white p-3 font-mono text-sm whitespace-pre-wrap">
                              {selectedVersion.fromSql}
                            </pre>
                          </div>
                          {selectedVersion.fieldsSnapshot && (
                            <div>
                              <label className="mb-1 block text-xs font-medium text-gray-500">
                                字段快照 (
                                {Array.isArray(selectedVersion.fieldsSnapshot)
                                  ? selectedVersion.fieldsSnapshot.length
                                  : 0}{' '}
                                个字段)
                              </label>
                              <pre className="max-h-60 overflow-auto rounded border border-gray-200 bg-white p-3 font-mono text-sm whitespace-pre-wrap">
                                {JSON.stringify(selectedVersion.fieldsSnapshot, null, 2)}
                              </pre>
                            </div>
                          )}
                          {selectedVersion.baseWhere && (
                            <div>
                              <label className="mb-1 block text-xs font-medium text-gray-500">
                                Base WHERE
                              </label>
                              <pre className="rounded border border-gray-200 bg-white p-3 font-mono text-sm whitespace-pre-wrap">
                                {JSON.stringify(selectedVersion.baseWhere, null, 2)}
                              </pre>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}

          {activeTab === 'policy' && (
            <div className="space-y-6">
              <div>
                <h3 className="mb-4 text-base font-medium text-gray-900">执行策略配置</h3>
                <p className="mb-6 text-sm text-gray-500">
                  配置查询执行的限制策略，包括最大返回行数、超时时间、频率限制等。
                </p>

                <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      最大返回行数
                    </label>
                    <input
                      type="number"
                      min={1}
                      max={100000}
                      value={policyData.maxRows ?? 5000}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          maxRows: parseInt(e.target.value) || 5000,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-max-rows"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      查询执行时强制的 LIMIT 上限（默认 5000）
                    </p>
                  </div>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      查询超时 (ms)
                    </label>
                    <input
                      type="number"
                      min={1000}
                      max={300000}
                      step={1000}
                      value={policyData.timeoutMs ?? 30000}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          timeoutMs: parseInt(e.target.value) || 30000,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-timeout"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      单次查询最大执行时间（默认 30000ms）
                    </p>
                  </div>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      频率限制 (次/分钟)
                    </label>
                    <input
                      type="number"
                      min={0}
                      max={1000}
                      value={policyData.rateLimitPerMinute ?? 60}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          rateLimitPerMinute: parseInt(e.target.value) || 0,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-rate-limit"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      每分钟最大执行次数，0 表示不限制（默认 60）
                    </p>
                  </div>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      缓存 TTL (秒)
                    </label>
                    <input
                      type="number"
                      min={0}
                      max={86400}
                      value={policyData.cacheTtlSeconds ?? 0}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          cacheTtlSeconds: parseInt(e.target.value) || 0,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-cache-ttl"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      查询结果缓存时间，0 表示不缓存（默认 0）
                    </p>
                  </div>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      导出最大行数
                    </label>
                    <input
                      type="number"
                      min={1}
                      max={500000}
                      value={policyData.exportMaxRows ?? 50000}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          exportMaxRows: parseInt(e.target.value) || 50000,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-export-max-rows"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      数据导出时的最大行数上限（默认 50000）
                    </p>
                  </div>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      沙箱最大行数
                    </label>
                    <input
                      type="number"
                      min={1}
                      max={1000}
                      value={policyData.sandboxMaxRows ?? 100}
                      onChange={(e) =>
                        setPolicyData((prev) => ({
                          ...prev,
                          sandboxMaxRows: parseInt(e.target.value) || 100,
                        }))
                      }
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="policy-sandbox-max-rows"
                    />
                    <p className="mt-1 text-xs text-gray-500">
                      草稿/沙箱模式下的最大行数（默认 100）
                    </p>
                  </div>
                </div>

                <div className="mt-6 flex justify-end">
                  <button
                    onClick={handleSavePolicy}
                    className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
                    disabled={loading}
                    data-testid="save-policy-btn"
                  >
                    {loading ? '保存中...' : '保存策略'}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
