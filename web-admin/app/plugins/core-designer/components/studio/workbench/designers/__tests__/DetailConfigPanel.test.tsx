import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DetailConfigPanel } from '../DetailConfigPanel';

vi.mock('~/shared/hooks/useModelCapabilities', () => ({
  useModelCapabilities: () => ({ data: undefined }),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async (url: string) => {
    if (url.includes('/api/meta/models/code/')) {
      return { code: '0', data: { pid: 'model_showcase' } };
    }
    if (url.includes('/api/meta/models/model_showcase/fields')) {
      return {
        code: '0',
        data: [
          { code: 'sc_name', displayName: 'Name', dataType: 'string' },
          { code: 'sc_quantity', displayName: 'Quantity', dataType: 'integer' },
        ],
      };
    }
    return { code: '0', data: undefined };
  }),
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

  it('exposes detail section authoring and pushes section blocks through schema changes', async () => {
    const onSchemaChange = vi.fn();
    const schema = {
      schemaVersion: 2,
      kind: 'detail',
      id: 'p2',
      pageKey: 'showcase_detail',
      modelCode: 'showcase_all_fields',
      title: 'Showcase Detail',
      layout: { type: 'stack' },
      blocks: [],
    } as any;

    render(<DetailConfigPanel schema={schema} onSchemaChange={onSchemaChange} />);

    fireEvent.click(screen.getByTestId('detail-tab-sections'));
    expect(screen.getByTestId('add-section-btn')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('add-section-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('section-item-0')).toHaveTextContent('分组 1');
      expect(onSchemaChange).toHaveBeenCalled();
    });

    const latestSchema = onSchemaChange.mock.calls.at(-1)?.[0];
    expect(latestSchema.blocks).toMatchObject([
      {
        blockType: 'detail-section',
        title: '分组 1',
        columns: 2,
        fields: [],
      },
    ]);
  });
});
