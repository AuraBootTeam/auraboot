import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TemplateImportDialog} from '../TemplateImportDialog';
const st = (value: any) => typeof value === 'object' ? value.zh : value;
vi.mock('~/utils/i18n', () => ({useSmartText: () => st}));
vi.mock('~/ui/smart/picker/RelationField', () => ({RelationField: ({label, onChange}: any) => <button onClick={() => onChange('OWN-1')}>{label}</button>}));
const document: any = {name: '任务归档', description: '会覆盖任务描述', flowConfig: {nodes: [], edges: []}, templateParameters: [{key: 'target', kind: 'record', modelCode: 'demo_task', displayField: 'title', label: '演示任务'}]};
describe('Template import confirmation', () => {
  beforeEach(() => vi.restoreAllMocks());
  it('requires a selected record and preserves default disabled until the server confirms it', async () => {
    const fetched = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({code: '0', data: {pid: 'copy', enabled: false}})));
    const imported = vi.fn();
    render(<TemplateImportDialog document={document} headers={{}} onCancel={vi.fn()} onImported={imported} />);
    expect(screen.getByRole('button', {name: '导入停用副本'})).toBeDisabled();
    expect(screen.getByText('会覆盖任务描述')).toBeVisible();
    fireEvent.click(screen.getByRole('button', {name: '演示任务'}));
    fireEvent.click(screen.getByRole('button', {name: '导入停用副本'}));
    await waitFor(() => expect(imported).toHaveBeenCalledOnce());
    const body = JSON.parse(fetched.mock.calls[0][1]!.body as string);
    expect(body).toMatchObject({enabled: false, templateValues: {target: 'OWN-1'}});
  });
  it('keeps the dialog and entered values when permission validation fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', {status: 403}));
    const imported = vi.fn(); render(<TemplateImportDialog document={document} headers={{}} onCancel={vi.fn()} onImported={imported} />);
    fireEvent.click(screen.getByRole('button', {name: '演示任务'})); fireEvent.click(screen.getByRole('button', {name: '导入停用副本'}));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('导入失败'));
    expect(imported).not.toHaveBeenCalled(); expect(screen.getByRole('button', {name: '导入停用副本'})).toBeEnabled();
  });
  it('cancel does not create an automation', () => {
    const cancel = vi.fn(); const fetched = vi.spyOn(globalThis, 'fetch');
    render(<TemplateImportDialog document={document} headers={{}} onCancel={cancel} onImported={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', {name: '取消'})); expect(cancel).toHaveBeenCalledOnce(); expect(fetched).not.toHaveBeenCalled();
  });
});
