/**
 * Named Query Create Page
 *
 * Form for creating a new named query definition
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { namedQueryService } from '~/shared/services/namedQueryService';
import { useToastContext } from '~/contexts/ToastContext';
import type { NamedQueryCreateRequest } from '~/shared/services/namedQueryService';
import SqlEditor from './components/SqlEditor';

export default function NamedQueryCreatePage() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [loading, setLoading] = useState(false);
  const [formData, setFormData] = useState<NamedQueryCreateRequest>({
    code: '',
    title: '',
    description: '',
    fromSql: '',
    baseWhere: undefined,
    defaultOrder: undefined,
  });

  const [baseWhereText, setBaseWhereText] = useState('');
  const [defaultOrderText, setDefaultOrderText] = useState('');

  /**
   * Handle form field change
   */
  const handleFieldChange = useCallback((field: keyof NamedQueryCreateRequest, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Handle submit
   */
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      if (!formData.code || !formData.title || !formData.fromSql) {
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
        const request: NamedQueryCreateRequest = {
          ...formData,
          baseWhere,
          defaultOrder,
        };

        const result = await namedQueryService.create(request);
        showSuccessToast('创建查询成功');
        navigate(`/meta/named-queries/${result.pid}`);
      } catch (error: any) {
        console.error('Failed to create named query:', error);
        showErrorToast(error.message || '创建查询失败');
      } finally {
        setLoading(false);
      }
    },
    [formData, baseWhereText, defaultOrderText, navigate, showSuccessToast, showErrorToast],
  );

  /**
   * Handle cancel
   */
  const handleCancel = useCallback(() => {
    navigate('/meta/named-queries');
  }, [navigate]);

  /**
   * Handle validate
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

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">新建查询</h1>
        <p className="mt-1 text-sm text-gray-500">创建新的命名查询定义</p>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="space-y-6">
        {/* Section 1: Basic Info */}
        <div className="rounded-lg bg-white p-6 shadow">
          <h2 className="mb-4 text-lg font-medium text-gray-900">基本信息</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                查询编码 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.code}
                onChange={(e) => handleFieldChange('code', e.target.value)}
                placeholder="例如: user_list_query"
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                required
                data-testid="form-field-code"
              />
              <p className="mt-1 text-xs text-gray-500">使用小写字母、数字和下划线</p>
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                查询标题 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.title}
                onChange={(e) => handleFieldChange('title', e.target.value)}
                placeholder="例如: 用户列表查询"
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                required
                data-testid="form-field-title"
              />
            </div>

            <div className="md:col-span-2">
              <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
              <textarea
                value={formData.description}
                onChange={(e) => handleFieldChange('description', e.target.value)}
                placeholder="查询的用途和说明"
                rows={2}
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                data-testid="form-field-description"
              />
            </div>
          </div>
        </div>

        {/* Section 2: SQL Config */}
        <div className="rounded-lg bg-white p-6 shadow">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-medium text-gray-900">SQL 配置</h2>
            <button
              type="button"
              onClick={handleValidate}
              className="rounded-md border border-blue-300 px-3 py-1.5 text-sm text-blue-700 hover:bg-blue-50"
            >
              验证 SQL
            </button>
          </div>
          <div className="space-y-4">
            <div data-testid="form-field-fromSql">
              <label className="mb-1 block text-sm font-medium text-gray-700">
                FROM SQL <span className="text-red-500">*</span>
              </label>
              <SqlEditor
                value={formData.fromSql}
                onChange={(val) => handleFieldChange('fromSql', val)}
                height="160px"
                placeholder="e.g. ab_user u LEFT JOIN ab_department d ON u.dept_id = d.id"
              />
              <p className="mt-1 text-xs text-gray-500">FROM 子句，不需要包含 FROM 关键字</p>
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                Base WHERE (JSON)
              </label>
              <textarea
                value={baseWhereText}
                onChange={(e) => setBaseWhereText(e.target.value)}
                placeholder='例如: {"field": "status", "op": "EQ", "value": "active"}'
                rows={3}
                className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
              <p className="mt-1 text-xs text-gray-500">默认 WHERE 条件，JSON 格式</p>
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                Default Order (JSON)
              </label>
              <textarea
                value={defaultOrderText}
                onChange={(e) => setDefaultOrderText(e.target.value)}
                placeholder='例如: [{"field": "created_at", "direction": "desc"}]'
                rows={3}
                className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
              <p className="mt-1 text-xs text-gray-500">默认排序规则，JSON 格式</p>
            </div>
          </div>
        </div>

        {/* Section 3: Actions */}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={handleCancel}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            disabled={loading}
            data-testid="form-btn-cancel"
          >
            取消
          </button>
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
            disabled={loading}
            data-testid="form-btn-submit"
          >
            {loading ? '创建中...' : '创建查询'}
          </button>
        </div>
      </form>
    </div>
  );
}
