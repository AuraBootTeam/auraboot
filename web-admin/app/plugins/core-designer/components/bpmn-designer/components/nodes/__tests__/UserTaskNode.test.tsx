import type { ComponentType } from 'react';
import { ReactFlowProvider } from '@xyflow/react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BPMNNodeType } from '~/plugins/core-designer/components/bpmn-designer/types';
import { UserTaskNode } from '../UserTaskNode';

function renderUserTask(data: Record<string, unknown>) {
  const Node = UserTaskNode as ComponentType<any>;
  render(
    <ReactFlowProvider>
      <Node
        id="task_manager_approve"
        type={BPMNNodeType.USER_TASK}
        selected={false}
        dragging={false}
        isConnectable
        zIndex={0}
        xPos={0}
        yPos={0}
        data={data as any}
      />
    </ReactFlowProvider>,
  );
}

describe('UserTaskNode', () => {
  it('renders localized assignee labels in the Chinese designer', () => {
    renderUserTask({
      label: '主管审批',
      config: { assignee: { type: 'role', roleIds: ['wd_manager'] } },
    });

    expect(screen.getByText('主管审批')).toBeInTheDocument();
    expect(screen.getByText('角色: wd_manager')).toBeInTheDocument();
    expect(screen.queryByText('Role: wd_manager')).not.toBeInTheDocument();
  });
});
