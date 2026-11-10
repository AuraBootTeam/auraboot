import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfirmCard } from '../ConfirmCard';
import { ChatBiResultCard } from '../ChatBiResultCard';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get }));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ getDictLabel: (code: string, value: string) => code === 'task_status' && value === 'todo' ? '待处理' : undefined }),
}));
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => false }));
vi.mock('../../components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { isLoading: false }, sendMessage: vi.fn() }),
}));
const project = '01M6J9RKY4ZSHS7RJ3ZKSK2JP3';
const fields = [
  { code: 'tpm_ts_title', displayName: '任务标题' },
  { code: 'tpm_ts_assignee', displayName: '负责人' },
  { code: 'tpm_ts_status', displayName: '状态', dictCode: 'task_status' },
  { code: 'tpm_ts_project_id', displayName: '所属项目', refTarget: { targetEntity: 'tpm_project', targetField: 'tpm_pj_name', valueField: 'pid' } },
];
function configured() {
  get.mockImplementation(async (path: string) => {
    if (path === '/api/agent/tools/registry') return { code: '0', data: { tools: [{ toolCode: 'cmd:tpm:create_task', toolName: '新建任务', modelCode: 'tpm_task' }] } };
    if (path === '/api/dynamic/tpm_task/field-meta') return { code: '0', data: fields };
    if (path === `/api/dynamic/tpm_project/${project}`) return { code: '0', data: { tpm_pj_name: '华辰门户交付' } };
    throw new Error('Unexpected metadata read');
  });
}
afterEach(() => { cleanup(); vi.resetAllMocks(); });
describe('business tool presentation', () => {
  it('hides raw parameters and prevents confirmation until field and reference metadata have loaded', async () => {
    type Response = {code: string; data: unknown};
    let resolveFields!: (response: Response) => void;
    let resolveReference!: (response: Response) => void;
    const pendingFields = new Promise<Response>(resolve => { resolveFields = resolve; });
    const pendingReference = new Promise<Response>(resolve => { resolveReference = resolve; });
    get.mockImplementation((path: string) => {
      if (path === '/api/agent/tools/registry') return Promise.resolve({code: '0', data: {tools: [{toolCode: 'cmd:tpm:create_task', toolName: '新建任务', modelCode: 'tpm_task'}]}});
      if (path === '/api/dynamic/tpm_task/field-meta') return pendingFields;
      if (path === `/api/dynamic/tpm_project/${project}`) return pendingReference;
      throw new Error('Unexpected metadata request');
    });
    const confirm = vi.fn(), cancel = vi.fn();
    const input = Object.freeze({tpm_ts_assignee: '林晓', tpm_ts_project_id: project});
    render(<ConfirmCard toolId="pending" toolName="cmd_tpm_create_task" description="" input={input} onConfirm={confirm} onCancel={cancel} />);
    const card = screen.getByTestId('aurabot-confirm-card');
    expect(screen.getByRole('status')).toHaveTextContent('正在读取业务参数');
    expect(card).not.toHaveTextContent(/Tpm Ts|tpm_|01M6J/);
    expect(screen.getByTestId('aurabot-confirm-approve')).toBeDisabled();
    expect(screen.getByTestId('aurabot-confirm-cancel')).toBeEnabled();
    resolveFields({code: '0', data: fields});
    await waitFor(() => expect(get).toHaveBeenCalledWith(`/api/dynamic/tpm_project/${project}`));
    expect(screen.getByTestId('aurabot-confirm-approve')).toBeDisabled();
    expect(card).not.toHaveTextContent(project);
    resolveReference({code: '0', data: {tpm_pj_name: '华辰门户交付'}});
    await screen.findByText('华辰门户交付');
    expect(card).toHaveTextContent('负责人');
    expect(screen.getByTestId('aurabot-confirm-approve')).toBeEnabled();
    fireEvent.click(screen.getByTestId('aurabot-confirm-approve'));
    expect(confirm).toHaveBeenCalledExactlyOnceWith('pending');
    expect(input.tpm_ts_project_id).toBe(project);
  });

  it('keeps cancellation available and refuses blind confirmation when business metadata is denied', async () => {
    get.mockImplementation(async (path: string) => path === '/api/agent/tools/registry'
      ? {code: '0', data: {tools: [{toolCode: 'cmd:tpm:create_task', modelCode: 'tpm_task'}]}}
      : {code: '403', data: null});
    const confirm = vi.fn(), cancel = vi.fn();
    render(<ConfirmCard toolId="denied" toolName="cmd_tpm_create_task" description="" input={{tpm_ts_project_id: project}} onConfirm={confirm} onCancel={cancel} />);
    await screen.findByRole('alert');
    expect(screen.getByTestId('aurabot-confirm-card')).not.toHaveTextContent(project);
    expect(screen.getByTestId('aurabot-confirm-approve')).toBeDisabled();
    fireEvent.click(screen.getByTestId('aurabot-confirm-approve'));
    expect(confirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('aurabot-confirm-cancel'));
    expect(cancel).toHaveBeenCalledExactlyOnceWith('denied');
  });
  it('reads actual tool and field metadata while confirming the original operation once', async () => {
    configured();
    const input = Object.freeze({ tpm_ts_title: '交付材料复核', tpm_ts_assignee: '林晓', tpm_ts_project_id: project });
    const confirm = vi.fn();
    render(<ConfirmCard toolId="original-pending" toolName="cmd_tpm_create_task" description="Execute cmd_tpm_create_task" input={input} onConfirm={confirm} onCancel={() => {}} />);
    const card = screen.getByTestId('aurabot-confirm-card');
    await waitFor(() => expect(card).toHaveTextContent('华辰门户交付'));
    expect(card).toHaveTextContent('新建任务');
    expect(card).toHaveTextContent('负责人');
    expect(card).not.toHaveTextContent(/Tpm Ts|tpm_|01M6J/);
    fireEvent.click(screen.getByTestId('aurabot-confirm-approve'));
    expect(confirm).toHaveBeenCalledExactlyOnceWith('original-pending');
    expect(input.tpm_ts_project_id).toBe(project);
  });
  it('uses business labels, dictionaries and companion reference values without altering raw query data', async () => {
    configured();
    const row = Object.freeze({ tpm_ts_title: '接口联调', tpm_ts_status: 'todo', tpm_ts_project_id: project, tpm_ts_project_id_display: '华辰门户交付' });
    render(<ChatBiResultCard result={{ modelCode: 'tpm_task', chartType: 'table', records: [row], total: 1 }} />);
    await screen.findByRole('columnheader', { name: '任务标题' });
    expect(screen.getByRole('cell', { name: '待处理' })).toBeVisible();
    expect(screen.getAllByRole('cell', { name: '华辰门户交付' })).toHaveLength(1);
    expect(screen.queryByRole('cell', { name: project })).not.toBeInTheDocument();
    expect(row.tpm_ts_status).toBe('todo');
    expect(get).not.toHaveBeenCalledWith(`/api/dynamic/tpm_project/${project}`);
  });
  it('keeps cancellation available when presentation metadata is denied', async () => {
    get.mockResolvedValue({ code: '403', data: null });
    const cancel = vi.fn();
    render(<ConfirmCard toolId="pending-denied" toolName="cmd_tpm_create_task" description="" input={{ title: '交付提案' }} onConfirm={() => {}} onCancel={cancel} />);
    fireEvent.click(screen.getByTestId('aurabot-confirm-cancel'));
    expect(cancel).toHaveBeenCalledExactlyOnceWith('pending-denied');
    await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
  });
});
