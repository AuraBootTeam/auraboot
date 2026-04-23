import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DetailConfigPanel } from '../DetailConfigPanel';

vi.mock('~/shared/hooks/useModelCapabilities', () => ({
  useModelCapabilities: () => ({ data: undefined }),
}));

describe('DetailConfigPanel', () => {
  it('does not emit schema changes on mount for complex detail pages', async () => {
    const onSchemaChange = vi.fn();
    const schema = {
      schemaVersion: 2,
      kind: 'detail',
      id: 'p1',
      pageKey: 'wd_leave_request_detail',
      modelCode: 'wd_leave_request',
      title: { 'zh-CN': '请假申请详情', en: 'Leave Request Detail' },
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'wd_leave_request_detail_toolbar',
          blockType: 'toolbar',
          buttons: [
            { label: 'edit', action: { type: 'navigate', to: 'wd_leave_request_form' } },
            { label: 'execute', command: 'wd:submit_leave_request', primary: true },
          ],
        },
        {
          id: 'wd_leave_request_tabs',
          blockType: 'tabs',
          tabs: [{ key: 'workflow_diagram', label: { 'zh-CN': '流程图' } }],
        },
      ],
    } as any;

    render(<DetailConfigPanel schema={schema} onSchemaChange={onSchemaChange} />);

    expect(screen.getByTestId('detail-designer-summary')).toBeInTheDocument();
    expect(screen.getByTestId('detail-designer-workspace')).toBeInTheDocument();

    await waitFor(() => {
      expect(onSchemaChange).not.toHaveBeenCalled();
    });
  });
});
