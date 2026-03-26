/**
 * 用户偏好设置页面
 * 管理用户记忆、投资偏好等信息
 */

import { useState, useEffect } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
type MetaArgs = Record<string, unknown>;
import { userPreferenceService } from '~/services/userPreferenceService';
import TimezoneSelect from '~/components/TimezoneSelect';
import {
  type UserMemory,
  type MemoryType,
  type CreateMemoryRequest,
  getUserMemories,
  createUserMemory,
  deleteUserMemory,
  getMemoryTypeText,
  getMemoryTypeColor,
} from '~/services/userMemoryService';

export function meta({}: MetaArgs) {
  return [
    { title: '用户偏好设置' },
    { name: 'description', content: '管理您的投资偏好和记忆信息' },
  ];
}

export default function UserPreferencesPage() {
  const { showErrorToast, showSuccessToast } = useToastContext();
  const [memories, setMemories] = useState<UserMemory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [filterType, setFilterType] = useState<MemoryType | 'all'>('all');
  const [datetimeFormat, setDatetimeFormat] = useState('YYYY-MM-DD HH:mm:ss');
  const [timezone, setTimezone] = useState('');
  const [formatSaving, setFormatSaving] = useState(false);
  const [timezoneSaving, setTimezoneSaving] = useState(false);

  const loadMemories = async () => {
    try {
      setLoading(true);
      setError(null);
      const params = filterType !== 'all' ? { memory_type: filterType } : {};
      const result = await getUserMemories(params);
      setMemories(result.memories);
    } catch (err: any) {
      console.error('Failed to load memories:', err);
      setError(err.message || '加载记忆失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMemories();
  }, [filterType]);

  useEffect(() => {
    userPreferenceService
      .get<string>('ui.datetime.format')
      .then((value) => {
        if (value && value.trim()) {
          setDatetimeFormat(value);
        }
      })
      .catch(() => {
        // ignore loading errors, keep default
      });
    userPreferenceService
      .get<string>('ui.timezone')
      .then((value) => {
        if (value && value.trim()) {
          setTimezone(value);
        }
      })
      .catch(() => {
        // ignore loading errors
      });
  }, []);

  const handleAdd = async (request: CreateMemoryRequest) => {
    try {
      const newMemory = await createUserMemory(request);
      setMemories((prev) => [newMemory, ...prev]);
      setShowAddForm(false);
    } catch (err: any) {
      console.error('Failed to create memory:', err);
      showErrorToast('创建记忆失败：' + (err.message || '未知错误'));
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('确定要删除这条记忆吗？')) {
      return;
    }

    try {
      await deleteUserMemory(id);
      setMemories((prev) => prev.filter((m) => m.id !== id));
    } catch (err: any) {
      console.error('Failed to delete memory:', err);
      showErrorToast('删除记忆失败：' + (err.message || '未知错误'));
    }
  };

  const handleSaveDateTimeFormat = async () => {
    try {
      setFormatSaving(true);
      await userPreferenceService.set('ui.datetime.format', datetimeFormat.trim());
      showSuccessToast('Datetime format saved');
    } catch (err: any) {
      showErrorToast(`Failed to save datetime format: ${err?.message || 'Unknown error'}`);
    } finally {
      setFormatSaving(false);
    }
  };

  const handleSaveTimezone = async () => {
    try {
      setTimezoneSaving(true);
      await userPreferenceService.set('ui.timezone', timezone);
      showSuccessToast('Timezone preference saved');
    } catch (err: any) {
      showErrorToast(`Failed to save timezone: ${err?.message || 'Unknown error'}`);
    } finally {
      setTimezoneSaving(false);
    }
  };

  const handleClearTimezone = async () => {
    try {
      setTimezoneSaving(true);
      await userPreferenceService.set('ui.timezone', '');
      setTimezone('');
      showSuccessToast('Timezone override cleared — system default will be used');
    } catch (err: any) {
      showErrorToast(`Failed to clear timezone: ${err?.message || 'Unknown error'}`);
    } finally {
      setTimezoneSaving(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="border-b bg-white">
        <div className="mx-auto max-w-5xl px-6 py-6">
          <h1 className="text-2xl font-bold text-gray-900">用户偏好设置</h1>
          <p className="mt-1 text-sm text-gray-500">管理您的投资偏好、关注行业和其他记忆信息</p>
        </div>
      </div>

      {/* Main Content */}
      <div className="mx-auto max-w-5xl px-6 py-8">
        <div className="mb-6 rounded-lg bg-white p-4 shadow-sm">
          <h3 className="mb-2 text-base font-semibold text-gray-900">Datetime Display Format</h3>
          <p className="mb-3 text-sm text-gray-500">
            User preference overrides system preference. Example: YYYY-MM-DD HH:mm:ss / YYYY/MM/DD
            HH:mm
          </p>
          <div className="flex flex-col gap-3 md:flex-row md:items-center">
            <input
              type="text"
              value={datetimeFormat}
              onChange={(e) => setDatetimeFormat(e.target.value)}
              data-testid="user-datetime-format-input"
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={handleSaveDateTimeFormat}
              disabled={formatSaving || !datetimeFormat.trim()}
              data-testid="user-datetime-format-save"
              className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {formatSaving ? 'Saving...' : 'Save Format'}
            </button>
          </div>
        </div>

        <div className="mb-6 rounded-lg bg-white p-4 shadow-sm">
          <h3 className="mb-2 text-base font-semibold text-gray-900">Timezone Override</h3>
          <p className="mb-3 text-sm text-gray-500">
            Override the system timezone for your account. Leave empty to use the system default.
          </p>
          <div className="flex flex-col gap-3 md:flex-row md:items-start">
            <div className="min-w-0 flex-1">
              <TimezoneSelect
                value={timezone || Intl.DateTimeFormat().resolvedOptions().timeZone}
                onChange={setTimezone}
                data-testid="user-timezone-select"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleSaveTimezone}
                disabled={timezoneSaving || !timezone}
                data-testid="user-timezone-save"
                className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {timezoneSaving ? 'Saving...' : 'Save'}
              </button>
              {timezone && (
                <button
                  type="button"
                  onClick={handleClearTimezone}
                  disabled={timezoneSaving}
                  data-testid="user-timezone-clear"
                  className="rounded-lg border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                >
                  Clear
                </button>
              )}
            </div>
          </div>
          {!timezone && (
            <p className="mt-2 text-xs text-gray-400">
              Currently using: {Intl.DateTimeFormat().resolvedOptions().timeZone} (browser default
              or system preference)
            </p>
          )}
        </div>

        {/* Toolbar */}
        <div className="mb-6 rounded-lg bg-white p-4 shadow-sm">
          <div className="flex items-center justify-between">
            {/* Filter */}
            <div className="flex items-center space-x-2">
              <span className="text-sm text-gray-600">筛选：</span>
              <select
                value={filterType}
                onChange={(e) => setFilterType(e.target.value as MemoryType | 'all')}
                className="rounded-lg border border-gray-300 px-3 py-1 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500"
              >
                <option value="all">全部</option>
                <option value="preference">用户偏好</option>
                <option value="fact">已知事实</option>
                <option value="context">上下文信息</option>
              </select>
            </div>

            {/* Add Button */}
            <button
              onClick={() => setShowAddForm(true)}
              className="flex items-center space-x-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
            >
              <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 4v16m8-8H4"
                />
              </svg>
              <span>添加记忆</span>
            </button>
          </div>
        </div>

        {/* Add Form */}
        {showAddForm && (
          <AddMemoryForm onSubmit={handleAdd} onCancel={() => setShowAddForm(false)} />
        )}

        {/* Memory List */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
          </div>
        ) : error ? (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4">
            <div className="text-red-700">{error}</div>
            <button
              onClick={loadMemories}
              className="mt-2 text-sm text-red-600 underline hover:text-red-700"
            >
              重试
            </button>
          </div>
        ) : memories.length === 0 ? (
          <div className="rounded-lg bg-white py-12 text-center">
            <div className="mb-4 text-4xl">🧠</div>
            <div className="text-gray-500">暂无记忆信息</div>
            <button
              onClick={() => setShowAddForm(true)}
              className="mt-4 text-blue-600 underline hover:text-blue-700"
            >
              添加第一条记忆
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {memories.map((memory) => (
              <MemoryCard
                key={memory.id}
                memory={memory}
                onDelete={() => handleDelete(memory.id)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface AddMemoryFormProps {
  onSubmit: (request: CreateMemoryRequest) => void;
  onCancel: () => void;
}

function AddMemoryForm({ onSubmit, onCancel }: AddMemoryFormProps) {
  const { showInfoToast } = useToastContext();
  const [formData, setFormData] = useState<CreateMemoryRequest>({
    memory_type: 'preference',
    key: '',
    value: '',
    importance: 0.5,
    confidence: 0.9,
    compliance_related: false,
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.key || !formData.value) {
      showInfoToast('请填写完整信息');
      return;
    }
    onSubmit(formData);
  };

  return (
    <div className="mb-6 rounded-lg bg-white p-6 shadow-sm">
      <h3 className="mb-4 text-lg font-semibold">添加记忆</h3>
      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Type */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">
            类型 <span className="text-red-500">*</span>
          </label>
          <select
            value={formData.memory_type}
            onChange={(e) =>
              setFormData({ ...formData, memory_type: e.target.value as MemoryType })
            }
            className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
          >
            <option value="preference">用户偏好</option>
            <option value="fact">已知事实</option>
            <option value="context">上下文信息</option>
          </select>
        </div>

        {/* Key */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">
            键 <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={formData.key}
            onChange={(e) => setFormData({ ...formData, key: e.target.value })}
            placeholder="例如：投资风格、风险偏好"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
          />
        </div>

        {/* Value */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">
            值 <span className="text-red-500">*</span>
          </label>
          <textarea
            value={formData.value}
            onChange={(e) => setFormData({ ...formData, value: e.target.value })}
            placeholder="例如：价值投资、稳健型"
            rows={3}
            className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
          />
        </div>

        {/* Importance */}
        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700">
            重要性：{formData.importance?.toFixed(1)}
          </label>
          <input
            type="range"
            min="0"
            max="1"
            step="0.1"
            value={formData.importance}
            onChange={(e) => setFormData({ ...formData, importance: parseFloat(e.target.value) })}
            className="w-full"
          />
        </div>

        {/* Compliance */}
        <div className="flex items-center">
          <input
            type="checkbox"
            id="compliance"
            checked={formData.compliance_related}
            onChange={(e) => setFormData({ ...formData, compliance_related: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <label htmlFor="compliance" className="ml-2 text-sm text-gray-700">
            合规相关（不会被自动删除）
          </label>
        </div>

        {/* Buttons */}
        <div className="flex space-x-3 pt-4">
          <button
            type="submit"
            className="flex-1 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          >
            添加
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-gray-300 px-4 py-2 transition-colors hover:bg-gray-50"
          >
            取消
          </button>
        </div>
      </form>
    </div>
  );
}

interface MemoryCardProps {
  memory: UserMemory;
  onDelete: () => void;
}

function MemoryCard({ memory, onDelete }: MemoryCardProps) {
  return (
    <div className="rounded-lg border bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between">
        <div className="flex-1">
          {/* Type Badge */}
          <span
            className={`inline-block rounded-full px-2 py-1 text-xs font-medium ${getMemoryTypeColor(
              memory.memory_type,
            )}`}
          >
            {getMemoryTypeText(memory.memory_type)}
          </span>

          {/* Key-Value */}
          <div className="mt-2">
            <div className="font-medium text-gray-900">{memory.key}</div>
            <div className="mt-1 text-gray-700">{memory.value}</div>
          </div>

          {/* Metadata */}
          <div className="mt-3 flex items-center space-x-4 text-xs text-gray-500">
            <span>重要性: {(memory.importance * 100).toFixed(0)}%</span>
            <span>置信度: {(memory.confidence * 100).toFixed(0)}%</span>
            {memory.compliance_related && <span className="text-orange-600">🔒 合规相关</span>}
          </div>

          {/* Timestamps */}
          <div className="mt-2 text-xs text-gray-400">
            创建于 {new Date(memory.created_at).toLocaleString('zh-CN')}
          </div>
        </div>

        {/* Delete Button */}
        {!memory.compliance_related && (
          <button
            onClick={onDelete}
            className="ml-4 p-2 text-gray-400 transition-colors hover:text-red-600"
            title="删除"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}
