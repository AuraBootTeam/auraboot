import { useState, useEffect, useCallback } from 'react';
import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';

// ============================================================================
// Types
// ============================================================================

interface LabelRecord {
  pid: string;
  pm_label_name: string;
  pm_label_color: string;
  pm_label_description?: string;
  [key: string]: unknown;
}

interface ProjectSettingsProps {
  projectId: string;
  projectData: {
    pm_project_name?: string;
    pm_project_description?: string;
    pm_project_start_date?: string;
    pm_project_end_date?: string;
    pm_project_owner_user_id?: string;
    pm_description?: string;
    pm_start_date?: string;
    pm_end_date?: string;
    [key: string]: unknown;
  };
  onProjectUpdate: () => void;
}

const LABEL_COLORS = [
  { name: 'red', bg: 'bg-red-500', ring: 'ring-red-500' },
  { name: 'orange', bg: 'bg-orange-500', ring: 'ring-orange-500' },
  { name: 'yellow', bg: 'bg-yellow-500', ring: 'ring-yellow-500' },
  { name: 'green', bg: 'bg-green-500', ring: 'ring-green-500' },
  { name: 'blue', bg: 'bg-blue-500', ring: 'ring-blue-500' },
  { name: 'purple', bg: 'bg-purple-500', ring: 'ring-purple-500' },
  { name: 'gray', bg: 'bg-gray-500', ring: 'ring-gray-500' },
];

const COLOR_SWATCH: Record<string, string> = {
  RED: 'bg-red-500',
  ORANGE: 'bg-orange-500',
  YELLOW: 'bg-yellow-500',
  GREEN: 'bg-green-500',
  BLUE: 'bg-blue-500',
  PURPLE: 'bg-purple-500',
  GRAY: 'bg-gray-500',
};

// ============================================================================
// Component
// ============================================================================

export default function ProjectSettings({
  projectId,
  projectData,
  onProjectUpdate,
}: ProjectSettingsProps) {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  // ------ Project form state ------

  const [name, setName] = useState(projectData.pm_project_name || '');
  const [description, setDescription] = useState(
    projectData.pm_project_description || projectData.pm_description || '',
  );
  const [startDate, setStartDate] = useState(
    projectData.pm_project_start_date || projectData.pm_start_date || '',
  );
  const [endDate, setEndDate] = useState(
    projectData.pm_project_end_date || projectData.pm_end_date || '',
  );
  const [ownerUserId, setOwnerUserId] = useState(projectData.pm_project_owner_user_id || '');
  const [saving, setSaving] = useState(false);

  // ------ Label state ------

  const [labels, setLabels] = useState<LabelRecord[]>([]);
  const [labelsLoading, setLabelsLoading] = useState(true);
  const [showLabelForm, setShowLabelForm] = useState(false);
  const [labelName, setLabelName] = useState('');
  const [labelColor, setLabelColor] = useState('blue');
  const [labelDesc, setLabelDesc] = useState('');
  const [labelSubmitting, setLabelSubmitting] = useState(false);
  const [deletingLabelId, setDeletingLabelId] = useState<string | null>(null);

  // ------ Save project ------

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      const result = await post<unknown>('/api/meta/commands/execute/pm:update_project', {
        payload: {
          pm_project_name: name,
          pm_description: description,
          pm_start_date: startDate,
          pm_end_date: endDate,
          pm_project_owner_user_id: ownerUserId,
        },
        targetRecordId: projectId,
        operationType: 'update',
      });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(l('项目已更新', 'Project updated'));
        onProjectUpdate();
      } else {
        showErrorToast(result.message || l('更新失败', 'Update failed'));
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('更新失败', 'Update failed');
      showErrorToast(msg);
    } finally {
      setSaving(false);
    }
  }, [
    projectId,
    name,
    description,
    startDate,
    endDate,
    ownerUserId,
    l,
    showSuccessToast,
    showErrorToast,
    onProjectUpdate,
  ]);

  // ------ Load labels ------

  const loadLabels = useCallback(async () => {
    setLabelsLoading(true);
    try {
      const result = await get<{ records: LabelRecord[] }>('/api/dynamic/pm-label/list', {
        filters: JSON.stringify([
          { fieldName: 'pm_label_project_id', operator: 'EQ', value: projectId },
        ]),
        pageSize: '200',
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setLabels(result.data.records);
      } else {
        setLabels([]);
      }
    } catch {
      setLabels([]);
    } finally {
      setLabelsLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadLabels();
  }, [loadLabels]);

  // ------ Create label ------

  const handleCreateLabel = useCallback(async () => {
    if (!labelName.trim()) return;
    setLabelSubmitting(true);
    try {
      const result = await post<unknown>('/api/meta/commands/execute/pm:create_label', {
        payload: {
          pm_label_project_id: projectId,
          pm_label_name: labelName.trim(),
          pm_label_color: labelColor,
          pm_label_description: labelDesc.trim(),
        },
        operationType: 'create',
      });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(l('标签已创建', 'Label created'));
        setLabelName('');
        setLabelColor('blue');
        setLabelDesc('');
        setShowLabelForm(false);
        loadLabels();
      } else {
        showErrorToast(result.message || l('创建失败', 'Failed to create label'));
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('创建失败', 'Failed to create label');
      showErrorToast(msg);
    } finally {
      setLabelSubmitting(false);
    }
  }, [
    projectId,
    labelName,
    labelColor,
    labelDesc,
    l,
    showSuccessToast,
    showErrorToast,
    loadLabels,
  ]);

  // ------ Delete label ------

  const handleDeleteLabel = useCallback(
    async (labelPid: string) => {
      setDeletingLabelId(labelPid);
      try {
        const result = await post<unknown>('/api/meta/commands/execute/pm:delete_label', {
          targetRecordId: labelPid,
          operationType: 'delete',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('标签已删除', 'Label deleted'));
          loadLabels();
        } else {
          showErrorToast(result.message || l('删除失败', 'Failed to delete label'));
        }
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : l('删除失败', 'Failed to delete label');
        showErrorToast(msg);
      } finally {
        setDeletingLabelId(null);
      }
    },
    [l, showSuccessToast, showErrorToast, loadLabels],
  );

  // ------ Render ------

  return (
    <div className="max-w-3xl space-y-8" data-testid="project-settings">
      {/* ===== Project Info Form ===== */}
      <div className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800">
        <h3 className="mb-6 text-lg font-semibold text-gray-900 dark:text-white">
          {l('项目信息', 'Project Info')}
        </h3>

        <div className="space-y-4">
          {/* Name */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('项目名称', 'Project Name')}
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              data-testid="settings-name-input"
            />
          </div>

          {/* Description */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('项目描述', 'Description')}
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="w-full resize-none rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              data-testid="settings-description-input"
            />
          </div>

          {/* Date row */}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {l('开始日期', 'Start Date')}
              </label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="settings-start-date"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {l('结束日期', 'End Date')}
              </label>
              <input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="settings-end-date"
              />
            </div>
          </div>

          {/* Owner */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('负责人 ID', 'Owner User ID')}
            </label>
            <input
              type="text"
              value={ownerUserId}
              onChange={(e) => setOwnerUserId(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              data-testid="settings-owner-input"
            />
          </div>
        </div>

        <div className="mt-6 flex justify-end">
          <button
            onClick={handleSave}
            disabled={saving}
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="settings-save-btn"
          >
            {saving ? l('保存中...', 'Saving...') : l('保存', 'Save')}
          </button>
        </div>
      </div>

      {/* ===== Label Manager ===== */}
      <div className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            {l('标签管理', 'Label Manager')}
            <span className="ml-2 text-sm font-normal text-gray-500 dark:text-gray-400">
              ({labels.length})
            </span>
          </h3>
          <button
            onClick={() => setShowLabelForm(!showLabelForm)}
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            data-testid="add-label-btn"
          >
            {showLabelForm ? l('取消', 'Cancel') : l('添加标签', 'Add Label')}
          </button>
        </div>

        {/* Add label form */}
        {showLabelForm && (
          <div
            className="mb-4 rounded-lg border border-gray-200 p-4 dark:border-gray-700"
            data-testid="add-label-form"
          >
            <div className="space-y-3">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {l('标签名称', 'Label Name')}
                </label>
                <input
                  type="text"
                  value={labelName}
                  onChange={(e) => setLabelName(e.target.value)}
                  placeholder={l('输入标签名称', 'Enter label name')}
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  data-testid="label-name-input"
                />
              </div>

              {/* Color picker */}
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {l('颜色', 'Color')}
                </label>
                <div className="flex gap-2" data-testid="label-color-picker">
                  {LABEL_COLORS.map((c) => (
                    <button
                      key={c.name}
                      type="button"
                      onClick={() => setLabelColor(c.name)}
                      className={`h-7 w-7 rounded-full ${c.bg} transition-all ${
                        labelColor === c.name
                          ? `ring-2 ${c.ring} scale-110 ring-offset-2 dark:ring-offset-gray-800`
                          : 'hover:scale-105'
                      }`}
                      title={c.name}
                      data-testid={`label-color-${c.name.toLowerCase()}`}
                    />
                  ))}
                </div>
              </div>

              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {l('描述', 'Description')}
                </label>
                <input
                  type="text"
                  value={labelDesc}
                  onChange={(e) => setLabelDesc(e.target.value)}
                  placeholder={l('可选', 'Optional')}
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  data-testid="label-desc-input"
                />
              </div>
            </div>

            <div className="mt-3">
              <button
                onClick={handleCreateLabel}
                disabled={labelSubmitting || !labelName.trim()}
                className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="submit-label-btn"
              >
                {labelSubmitting ? l('创建中...', 'Creating...') : l('创建标签', 'Create Label')}
              </button>
            </div>
          </div>
        )}

        {/* Labels table */}
        {labelsLoading ? (
          <div className="flex items-center justify-center py-8">
            <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
          </div>
        ) : labels.length === 0 ? (
          <div
            className="py-8 text-center text-sm text-gray-500 dark:text-gray-400"
            data-testid="labels-empty"
          >
            {l('暂无标签', 'No labels yet')}
          </div>
        ) : (
          <table className="w-full text-sm" data-testid="labels-table">
            <thead>
              <tr className="border-b border-gray-200 dark:border-gray-700">
                <th className="px-3 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('名称', 'Name')}
                </th>
                <th className="px-3 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('颜色', 'Color')}
                </th>
                <th className="px-3 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('描述', 'Description')}
                </th>
                <th className="px-3 py-2 text-right font-medium text-gray-600 dark:text-gray-400">
                  {l('操作', 'Actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {labels.map((label) => (
                <tr
                  key={label.pid}
                  className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  data-testid={`label-row-${label.pid}`}
                >
                  <td className="px-3 py-2.5 font-medium text-gray-900 dark:text-white">
                    {label.pm_label_name}
                  </td>
                  <td className="px-3 py-2.5">
                    <span
                      className={`inline-block h-4 w-4 rounded-full ${COLOR_SWATCH[label.pm_label_color] || 'bg-gray-400'}`}
                      title={label.pm_label_color}
                    />
                  </td>
                  <td className="px-3 py-2.5 text-gray-600 dark:text-gray-400">
                    {label.pm_label_description || '-'}
                  </td>
                  <td className="px-3 py-2.5 text-right">
                    <button
                      onClick={() => handleDeleteLabel(label.pid)}
                      disabled={deletingLabelId === label.pid}
                      className="text-xs text-red-600 hover:text-red-700 disabled:opacity-50 dark:text-red-400 dark:hover:text-red-300"
                      data-testid={`delete-label-${label.pid}`}
                    >
                      {deletingLabelId === label.pid
                        ? l('删除中...', 'Deleting...')
                        : l('删除', 'Delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
