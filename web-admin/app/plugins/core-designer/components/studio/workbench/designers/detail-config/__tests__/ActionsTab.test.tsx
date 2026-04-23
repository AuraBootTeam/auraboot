import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ActionsTab } from '../ActionsTab';
import type { DetailViewModel } from '../mapper';

vi.mock('~/plugins/core-designer/components/studio/services/command/CommandActionService', () => ({
  commandActionService: {
    listByModelCode: vi.fn().mockResolvedValue([
      {
        pid: 'cmd1',
        code: 'showcase:approve_record',
        displayName: 'Approve Record',
        description: 'Approve showcase record',
      },
    ]),
  },
}));

function StatefulActionsTab() {
  const [vm, setVm] = React.useState<DetailViewModel>({
    sections: [],
    actions: {
      presets: [],
      customButtons: [{ label: 'Approve', command: '' }],
    },
    passthroughBlocks: [],
  });

  return (
    <ActionsTab
      vm={vm}
      setVm={setVm}
      capabilities={{ create: true, update: true, delete: true, read: true } as any}
      modelCode="showcase_all_fields"
    />
  );
}

describe('ActionsTab', () => {
  it('updates list summary when command code changes in inspector', () => {
    render(<StatefulActionsTab />);

    fireEvent.click(screen.getByTestId('detail-custom-button-0'));
    fireEvent.change(screen.getByTestId('detail-command-code-input'), {
      target: { value: 'showcase:approve_record' },
    });

    expect(screen.getByTestId('detail-command-code-input')).toHaveValue('showcase:approve_record');
    expect(screen.getByTestId('detail-custom-button-0')).toHaveTextContent('showcase:approve_record');
  });

  it('preserves custom label when binding a command from selector', async () => {
    render(<StatefulActionsTab />);

    fireEvent.click(screen.getByTestId('detail-custom-button-0'));
    fireEvent.change(screen.getByRole('textbox', { name: /按钮文字/i }), {
      target: { value: 'Approve' },
    });

    fireEvent.click(await screen.findByRole('button', { name: /选择命令|Approve Record/i }));
    fireEvent.click(await screen.findByRole('button', { name: /showcase:approve_record/i }));

    expect(screen.getByTestId('detail-custom-button-0')).toHaveTextContent('Approve');
    expect(screen.getByTestId('detail-custom-button-0')).toHaveTextContent('showcase:approve_record');
  });
});
