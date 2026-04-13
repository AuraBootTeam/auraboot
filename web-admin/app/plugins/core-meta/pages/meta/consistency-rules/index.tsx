import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  PencilSquareIcon,
  TrashIcon,
  MagnifyingGlassIcon,
  ShieldCheckIcon,
  CheckCircleIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';
import {
  listConsistencyRules,
  createConsistencyRule,
  updateConsistencyRule,
  deleteConsistencyRule,
  type ConsistencyRule,
  type ConsistencyRuleRequest,
  type PaginatedRules,
} from '~/shared/services/consistencyRuleService';

const AGGREGATION_OPTIONS = [
  { value: 'sum', label: 'sum' },
  { value: 'count', label: 'count' },
  { value: 'max', label: 'max' },
  { value: 'min', label: 'min' },
  { value: 'avg', label: 'avg' },
];

const OPERATOR_OPTIONS = [
  { value: 'LE', label: '<= (Less than or equal)' },
  { value: 'LT', label: '< (Less than)' },
  { value: 'EQ', label: '= (Equal)' },
  { value: 'GE', label: '>= (Greater than or equal)' },
  { value: 'GT', label: '> (Greater than)' },
  { value: 'NE', label: '!= (Not equal)' },
];

const SEVERITY_OPTIONS = [
  { value: 'error', label: 'Error' },
  { value: 'warning', label: 'Warning' },
  { value: 'info', label: 'Info' },
];

const emptyFormData: ConsistencyRuleRequest = {
  code: '',
  name: '',
  ruleType: 'cross_document',
  severity: 'error',
  sourceModel: '',
  sourceField: '',
  targetModel: '',
  targetField: '',
  linkField: '',
  aggregation: 'sum',
  operator: 'LE',
  messageTemplate: '',
  enabled: true,
};

export default function ConsistencyRulesPage() {
  const [rules, setRules] = useState<ConsistencyRule[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [searchModel, setSearchModel] = useState('');

  // Form state
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [formData, setFormData] = useState<ConsistencyRuleRequest>({
    ...emptyFormData,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const fetchRules = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listConsistencyRules(page, pageSize, searchModel || undefined);
      if (result.code === '0' && result.data) {
        const data = result.data as PaginatedRules;
        setRules(data.records || []);
        setTotal(data.total || 0);
      }
    } catch (e) {
      console.error('Failed to fetch rules:', e);
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, searchModel]);

  useEffect(() => {
    fetchRules();
  }, [fetchRules]);

  const handleCreate = () => {
    setFormData({ ...emptyFormData });
    setEditingId(null);
    setShowForm(true);
    setError('');
  };

  const handleEdit = (rule: ConsistencyRule) => {
    setFormData({
      code: rule.code,
      name: rule.name,
      ruleType: rule.ruleType,
      severity: rule.severity,
      sourceModel: rule.sourceModel,
      sourceField: rule.sourceField,
      targetModel: rule.targetModel,
      targetField: rule.targetField,
      linkField: rule.linkField,
      aggregation: rule.aggregation,
      operator: rule.operator,
      messageTemplate: rule.messageTemplate || '',
      enabled: rule.enabled,
    });
    setEditingId(rule.id);
    setShowForm(true);
    setError('');
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this rule?')) return;
    try {
      const result = await deleteConsistencyRule(id);
      if (result.code === '0') {
        fetchRules();
      }
    } catch (e) {
      console.error('Failed to delete rule:', e);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      let result;
      if (editingId) {
        result = await updateConsistencyRule(editingId, formData);
      } else {
        result = await createConsistencyRule(formData);
      }

      if (result.code === '0') {
        setShowForm(false);
        fetchRules();
      } else {
        setError(typeof result.data === 'string' ? result.data : 'Failed to save rule');
      }
    } catch (e) {
      setError('An error occurred while saving');
    } finally {
      setSaving(false);
    }
  };

  const handleFormChange = (field: keyof ConsistencyRuleRequest, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const getOperatorLabel = (op: string) => {
    return OPERATOR_OPTIONS.find((o) => o.value === op)?.label || op;
  };

  const getSeverityBadge = (severity: string) => {
    const styles: Record<string, string> = {
      ERROR: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
      WARNING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
      INFO: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
    };
    return (
      <span
        className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${
          styles[severity] || styles.INFO
        }`}
      >
        {severity}
      </span>
    );
  };

  return (
    <div className="w-full p-6">
      {/* Page Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ShieldCheckIcon className="h-8 w-8 text-indigo-600 dark:text-indigo-400" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Consistency Rules</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Cross-document validation rules for data integrity
            </p>
          </div>
        </div>
        <button
          onClick={handleCreate}
          className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-white transition-colors hover:bg-indigo-700"
        >
          <PlusIcon className="h-5 w-5" />
          New Rule
        </button>
      </div>

      {/* Search Bar */}
      <div className="mb-4 flex gap-2">
        <div className="relative max-w-sm flex-1">
          <MagnifyingGlassIcon className="absolute top-1/2 left-3 h-5 w-5 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder="Filter by source model..."
            value={searchModel}
            onChange={(e) => {
              setSearchModel(e.target.value);
              setPage(1);
            }}
            className="w-full rounded-lg border border-gray-300 bg-white py-2 pr-4 pl-10 text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
          />
        </div>
      </div>

      {/* Rules Table */}
      <div className="overflow-hidden rounded-lg bg-white shadow dark:bg-gray-800">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Code
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Source
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Target
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Rule
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Severity
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Status
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-gray-500">
                    Loading...
                  </td>
                </tr>
              ) : rules.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-gray-500">
                    No consistency rules defined yet.
                  </td>
                </tr>
              ) : (
                rules.map((rule) => (
                  <tr key={rule.id} className="hover:bg-gray-50 dark:hover:bg-gray-700/50">
                    <td className="px-4 py-3">
                      <div className="text-sm font-medium text-gray-900 dark:text-white">
                        {rule.code}
                      </div>
                      <div className="text-xs text-gray-500 dark:text-gray-400">{rule.name}</div>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-700 dark:text-gray-300">
                      <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs dark:bg-gray-700">
                        {rule.sourceModel}
                      </span>
                      <span className="mx-1 text-gray-400">.</span>
                      <span className="text-gray-600 dark:text-gray-400">{rule.sourceField}</span>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-700 dark:text-gray-300">
                      <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs dark:bg-gray-700">
                        {rule.targetModel}
                      </span>
                      <span className="mx-1 text-gray-400">.</span>
                      <span className="text-gray-600 dark:text-gray-400">{rule.targetField}</span>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600 dark:text-gray-400">
                      {rule.aggregation}({rule.sourceField}) {getOperatorLabel(rule.operator)}
                    </td>
                    <td className="px-4 py-3">{getSeverityBadge(rule.severity)}</td>
                    <td className="px-4 py-3">
                      {rule.enabled ? (
                        <span className="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                          <CheckCircleIcon className="h-4 w-4" />
                          <span className="text-xs">Enabled</span>
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-gray-400">
                          <XCircleIcon className="h-4 w-4" />
                          <span className="text-xs">Disabled</span>
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => handleEdit(rule)}
                          className="p-1 text-gray-400 transition-colors hover:text-indigo-600"
                          title="Edit"
                        >
                          <PencilSquareIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleDelete(rule.id)}
                          className="p-1 text-gray-400 transition-colors hover:text-red-600"
                          title="Delete"
                        >
                          <TrashIcon className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {total > pageSize && (
          <div className="flex items-center justify-between border-t border-gray-200 px-4 py-3 dark:border-gray-700">
            <span className="text-sm text-gray-500 dark:text-gray-400">Total: {total} rules</span>
            <div className="flex gap-2">
              <button
                disabled={page <= 1}
                onClick={() => setPage((p) => p - 1)}
                className="rounded border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50 dark:hover:bg-gray-700"
              >
                Previous
              </button>
              <span className="px-3 py-1 text-sm text-gray-600 dark:text-gray-400">
                Page {page}
              </span>
              <button
                disabled={page * pageSize >= total}
                onClick={() => setPage((p) => p + 1)}
                className="rounded border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50 dark:hover:bg-gray-700"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Create/Edit Modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-black/50 p-4">
          <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl bg-white shadow-xl dark:bg-gray-800">
            <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                {editingId ? 'Edit Rule' : 'Create Rule'}
              </h2>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4 p-6">
              {error && (
                <div className="rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
                  {error}
                </div>
              )}

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Code *
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.code}
                    onChange={(e) => handleFormChange('code', e.target.value)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="e.g. shipment_qty_le_order"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Name
                  </label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={(e) => handleFormChange('name', e.target.value)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="Human-readable rule name"
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Severity
                  </label>
                  <select
                    value={formData.severity}
                    onChange={(e) => handleFormChange('severity', e.target.value)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  >
                    {SEVERITY_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Aggregation
                  </label>
                  <select
                    value={formData.aggregation}
                    onChange={(e) => handleFormChange('aggregation', e.target.value)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  >
                    {AGGREGATION_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Operator
                  </label>
                  <select
                    value={formData.operator}
                    onChange={(e) => handleFormChange('operator', e.target.value)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  >
                    {OPERATOR_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <fieldset className="rounded-lg border border-gray-200 p-4 dark:border-gray-600">
                <legend className="px-2 text-sm font-medium text-gray-700 dark:text-gray-300">
                  Source (aggregated side)
                </legend>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="mb-1 block text-sm text-gray-600 dark:text-gray-400">
                      Model *
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.sourceModel}
                      onChange={(e) => handleFormChange('sourceModel', e.target.value)}
                      className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      placeholder="e.g. shipment_line"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm text-gray-600 dark:text-gray-400">
                      Field *
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.sourceField}
                      onChange={(e) => handleFormChange('sourceField', e.target.value)}
                      className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      placeholder="e.g. quantity"
                    />
                  </div>
                </div>
              </fieldset>

              <fieldset className="rounded-lg border border-gray-200 p-4 dark:border-gray-600">
                <legend className="px-2 text-sm font-medium text-gray-700 dark:text-gray-300">
                  Target (limit side)
                </legend>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="mb-1 block text-sm text-gray-600 dark:text-gray-400">
                      Model *
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.targetModel}
                      onChange={(e) => handleFormChange('targetModel', e.target.value)}
                      className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      placeholder="e.g. order_line"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm text-gray-600 dark:text-gray-400">
                      Field *
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.targetField}
                      onChange={(e) => handleFormChange('targetField', e.target.value)}
                      className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      placeholder="e.g. quantity"
                    />
                  </div>
                </div>
              </fieldset>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Link Field *
                </label>
                <input
                  type="text"
                  required
                  value={formData.linkField}
                  onChange={(e) => handleFormChange('linkField', e.target.value)}
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  placeholder="Field linking source to target (e.g. order_line_id)"
                />
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Message Template
                </label>
                <textarea
                  value={formData.messageTemplate}
                  onChange={(e) => handleFormChange('messageTemplate', e.target.value)}
                  rows={2}
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  placeholder="e.g. Total shipped ({sourceSum}) exceeds order qty ({targetValue})"
                />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                  Available variables: {'{sourceSum}'}, {'{targetValue}'}, {'{operator}'},{' '}
                  {'{ruleCode}'}, {'{ruleName}'}
                </p>
              </div>

              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="enabled"
                  checked={formData.enabled}
                  onChange={(e) => handleFormChange('enabled', e.target.checked)}
                  className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                />
                <label htmlFor="enabled" className="text-sm text-gray-700 dark:text-gray-300">
                  Enabled
                </label>
              </div>

              <div className="flex justify-end gap-3 border-t border-gray-200 pt-4 dark:border-gray-700">
                <button
                  type="button"
                  onClick={() => setShowForm(false)}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-indigo-600 px-4 py-2 text-white hover:bg-indigo-700 disabled:opacity-50"
                >
                  {saving ? 'Saving...' : editingId ? 'Update' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
