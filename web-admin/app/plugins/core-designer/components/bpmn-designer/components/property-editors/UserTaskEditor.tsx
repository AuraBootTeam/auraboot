/**
 * Property editor for UserTask nodes.
 * Uses AssigneePicker for user/role/dept selection instead of raw text inputs.
 */

import { AssigneeType } from '~/plugins/core-designer/components/bpmn-designer/types';
import type { UserTaskConfig, AssigneeConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { AssigneePicker } from './AssigneePicker';
import { MultiInstanceSection, FormBindingSection, HookConfigSection } from './shared';
import { useI18n } from '~/contexts/I18nContext';

export function UserTaskEditor({
  config,
  onChange,
}: {
  config?: UserTaskConfig;
  onChange: (config: UserTaskConfig) => void;
}) {
  const { t } = useI18n();

  const handleChange = (field: keyof UserTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as UserTaskConfig);
  };

  const handleAssigneeChange = (field: keyof AssigneeConfig, value: any) => {
    onChange({
      ...config,
      assignee: { ...config?.assignee, [field]: value } as AssigneeConfig,
    } as UserTaskConfig);
  };

  const assigneeType = config?.assignee?.type || AssigneeType.USER;

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
          data-testid="usertask-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.assigneeType')}</label>
        <select
          value={assigneeType}
          onChange={(e) => handleAssigneeChange('type', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="usertask-assignee-type"
        >
          <option value={AssigneeType.USER}>{t('bpmn.prop.usertask.assignUser')}</option>
          <option value={AssigneeType.ROLE}>{t('bpmn.prop.usertask.assignRole')}</option>
          <option value={AssigneeType.DEPT}>{t('bpmn.prop.usertask.assignDept')}</option>
          <option value={AssigneeType.STARTER}>{t('bpmn.prop.usertask.assignStarter')}</option>
          <option value={AssigneeType.EXPRESSION}>{t('bpmn.prop.usertask.assignExpression')}</option>
        </select>
      </div>

      {assigneeType === AssigneeType.USER && (
        <div className="mb-4">
          <AssigneePicker
            type="user"
            value={config?.assignee?.userIds || []}
            onChange={(ids) => handleAssigneeChange('userIds', ids)}
            placeholder={t('bpmn.prop.usertask.searchUser')}
          />
        </div>
      )}

      {assigneeType === AssigneeType.ROLE && (
        <div className="mb-4">
          <AssigneePicker
            type="role"
            value={config?.assignee?.roleIds || []}
            onChange={(ids) => handleAssigneeChange('roleIds', ids)}
            placeholder={t('bpmn.prop.usertask.searchRole')}
          />
        </div>
      )}

      {assigneeType === AssigneeType.DEPT && (
        <div className="mb-4">
          <AssigneePicker
            type="dept"
            value={config?.assignee?.deptIds || []}
            onChange={(ids) => handleAssigneeChange('deptIds', ids)}
            placeholder={t('bpmn.prop.usertask.searchDept')}
          />
        </div>
      )}

      {assigneeType === AssigneeType.STARTER && (
        <div className="mb-4">
          <p className="text-xs text-gray-500">{t('bpmn.prop.usertask.starterHint')}</p>
        </div>
      )}

      {assigneeType === AssigneeType.EXPRESSION && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.expressionLabel')}</label>
          <textarea
            value={config?.assignee?.expression || ''}
            onChange={(e) => handleAssigneeChange('expression', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            rows={2}
            placeholder="${user.manager}"
            data-testid="usertask-expression"
          />
          <p className="mt-1 text-xs text-gray-400">
            {t('bpmn.prop.usertask.expressionHint')}
          </p>
        </div>
      )}

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.approvalMode')}</label>
        <select
          value={config?.assignee?.assigneeMode || 'single'}
          onChange={(e) => handleAssigneeChange('assigneeMode', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          data-testid="usertask-approval-mode"
        >
          <option value="single">{t('bpmn.prop.usertask.modeSingle')}</option>
          <option value="multi">{t('bpmn.prop.usertask.modeMulti')}</option>
          <option value="sequential">{t('bpmn.prop.usertask.modeSequential')}</option>
        </select>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.priority')}</label>
        <input
          type="number"
          value={config?.priority || 50}
          onChange={(e) => handleChange('priority', parseInt(e.target.value))}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          min="0"
          max="100"
          data-testid="usertask-priority"
        />
      </div>

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={config?.skipable || false}
            onChange={(e) => handleChange('skipable', e.target.checked)}
            className="mr-2"
            data-testid="usertask-skipable"
          />
          <span className="text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.skipable')}</span>
        </label>
      </div>

      {/* Due date expression */}
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.usertask.dueDate')}</label>
        <input
          type="text"
          value={config?.dueDate || ''}
          onChange={(e) => handleChange('dueDate', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder={t('bpmn.prop.usertask.dueDatePlaceholder')}
          data-testid="usertask-duedate"
        />
        <p className="mt-1 text-xs text-gray-400">{t('bpmn.prop.usertask.dueDateHint')}</p>
      </div>

      {/* Multi-instance configuration */}
      <MultiInstanceSection
        config={config?.multiInstance}
        onChange={(multiInstance) => handleChange('multiInstance', multiInstance)}
      />

      {/* Form bindings configuration */}
      <FormBindingSection
        bindings={config?.formBindings || []}
        onChange={(formBindings) => handleChange('formBindings', formBindings)}
      />

      {/* Hook configuration */}
      <HookConfigSection
        hooks={config?.hooks || []}
        onChange={(hooks) => handleChange('hooks', hooks)}
      />
    </>
  );
}
