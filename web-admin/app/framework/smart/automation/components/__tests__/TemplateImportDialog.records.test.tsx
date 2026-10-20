import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {TemplateImportDialog} from '../TemplateImportDialog';
const st = (value: any) => typeof value === 'object' ? value.zh : value;
vi.mock('~/utils/i18n', () => ({useSmartText: () => st}));
const template: any = {name: '任务归档', flowConfig: {nodes: [], edges: []}, templateParameters: [{key: 'target', kind: 'record', modelCode: 'demo_task', displayField: 'title', label: '演示任务'}]};
afterEach(() => vi.restoreAllMocks());
describe('Real template record-picker states', () => {
  it('keeps a genuine empty search distinct from read failures', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({code:'0',data:{records:[]}})));
    render(<TemplateImportDialog document={template} headers={{}} onCancel={vi.fn()} onImported={vi.fn()} />);
    fireEvent.click(document.getElementById('target')!);
    await waitFor(() => expect(screen.getByText('暂无可选记录')).toBeVisible());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button',{name:'导入停用副本'})).toBeDisabled();
  });
  it('shows permission failure in the open dropdown and prevents import', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({code:'403'}),{status:403}));
    vi.spyOn(console,'error').mockImplementation(() => {});
    render(<TemplateImportDialog document={template} headers={{}} onCancel={vi.fn()} onImported={vi.fn()} />);
    fireEvent.click(document.getElementById('target')!);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('无法读取记录。请检查当前空间和数据读取权限后重试。'));
    expect(screen.queryByText('暂无可选记录')).not.toBeInTheDocument();
    expect(screen.getByRole('button',{name:'导入停用副本'})).toBeDisabled();
  });
});
