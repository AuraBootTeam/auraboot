/**
 * Property editor for UserTask nodes.
 */

import { AssigneeType } from '~/bpmn-designer/types';
import type { UserTaskConfig, AssigneeConfig } from '~/bpmn-designer/types';
import { MultiInstanceSection, FormBindingSection, HookConfigSection } from './shared';

export function UserTaskEditor({
  config,
  onChange,
}: {
  config?: UserTaskConfig;
  onChange: (config: UserTaskConfig) => void;
}) {
  const handleChange = (field: keyof UserTaskConfig, value: any) => {
    onChange({ ...config, [field]: value } as UserTaskConfig);
  };

  const handleAssigneeChange = (field: keyof AssigneeConfig, value: any) => {
    onChange({
      ...config,
      assignee: { ...config?.assignee, [field]: value } as AssigneeConfig,
    } as UserTaskConfig);
  };

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">人员分配类型</label>
        <select
          value={config?.assignee?.type || AssigneeType.USER}
          onChange={(e) => handleAssigneeChange('type', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        >
          <option value={AssigneeType.USER}>指定用户</option>
          <option value={AssigneeType.ROLE}>指定角色</option>
          <option value={AssigneeType.DEPT}>指定部门</option>
          <option value={AssigneeType.STARTER}>流程发起人</option>
          <option value={AssigneeType.EXPRESSION}>表达式</option>
        </select>
      </div>

      {config?.assignee?.type === AssigneeType.USER && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">用户ID（逗号分隔）</label>
          <input
            type="text"
            value={config?.assignee?.userIds?.join(',') || ''}
            onChange={(e) =>
              handleAssigneeChange('userIds', e.target.value.split(',').filter(Boolean))
            }
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="user1,user2"
          />
        </div>
      )}

      {config?.assignee?.type === AssigneeType.ROLE && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">角色ID（逗号分隔）</label>
          <input
            type="text"
            value={config?.assignee?.roleIds?.join(',') || ''}
            onChange={(e) =>
              handleAssigneeChange('roleIds', e.target.value.split(',').filter(Boolean))
            }
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="role1,role2"
          />
        </div>
      )}

      {config?.assignee?.type === AssigneeType.DEPT && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">部门ID（逗号分隔）</label>
          <input
            type="text"
            value={config?.assignee?.deptIds?.join(',') || ''}
            onChange={(e) =>
              handleAssigneeChange('deptIds', e.target.value.split(',').filter(Boolean))
            }
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            placeholder="dept1,dept2"
          />
        </div>
      )}

      {config?.assignee?.type === AssigneeType.EXPRESSION && (
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">表达式</label>
          <textarea
            value={config?.assignee?.expression || ''}
            onChange={(e) => handleAssigneeChange('expression', e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
            rows={2}
            placeholder="${user.manager}"
          />
        </div>
      )}

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">审批模式</label>
        <select
          value={config?.assignee?.assigneeMode || 'single'}
          onChange={(e) => handleAssigneeChange('assigneeMode', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        >
          <option value="single">单人审批</option>
          <option value="multi">会签（所有人）</option>
          <option value="sequential">依次审批</option>
        </select>
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">优先级</label>
        <input
          type="number"
          value={config?.priority || 50}
          onChange={(e) => handleChange('priority', parseInt(e.target.value))}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          min="0"
          max="100"
        />
      </div>

      <div className="mb-4">
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={config?.skipable || false}
            onChange={(e) => handleChange('skipable', e.target.checked)}
            className="mr-2"
          />
          <span className="text-sm font-medium text-gray-700">允许跳过</span>
        </label>
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
