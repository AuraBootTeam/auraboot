import React, { useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuraBotProvider, useAuraBot } from '../AuraBotProvider';
import { auraBotApi } from '../../services/auraBotApi';
import { DslFormFillProvider } from '~/framework/meta/rendering/DslFormFillContext';
import { useFormFill } from '~/framework/meta/rendering/useFormFill';

const router = vi.hoisted(() => ({ params: {}, location: { pathname: '/p/task/new', search: '', hash: '', state: null, key: 'test' } }));
vi.mock('react-router', async () => ({ ...await vi.importActual('react-router'), useLocation: () => router.location, useParams: () => router.params }));
vi.mock('~/contexts/ApplicationCapabilitiesContext', () => ({ useApplicationCapabilities: () => ({ agent: 'installed' }) }));
vi.mock('../../services/auraBotApi', () => ({ auraBotApi: {
  ensureConversation: vi.fn(), listConversations: vi.fn(), chatStream: vi.fn(),
  getConversationExecution: vi.fn().mockResolvedValue({ state: 'idle' }),
} }));

import { setDslFormFillBackend } from '~/framework/meta/rendering/dslFormFillBackend';
import { dslFormFillBackend } from '../../formFillBackend';

const instruction = '请根据纪要填写当前表单：名称：材料复核；说明：检查清单；日期未确定。';
function Form() {
  const [values, setValues] = useState<Record<string, unknown>>({ name: '', description: '', project: 'selected-project' });
  const fill = useFormFill('task-draft', 'task', [
    { code: 'name', label: '名称', type: 'string' },
    { code: 'description', label: '说明', type: 'string' },
    { code: 'date', label: '日期', type: 'string', format: 'date' },
    { code: 'project', label: '项目', type: 'string', locked: true },
  ], values, setValues, true);
  return <DslFormFillProvider target={fill.target}>
    <input aria-label="Name" value={String(values.name)} onChange={e => setValues({ ...values, name: e.target.value })} />
    <input aria-label="Description" value={String(values.description)} onChange={e => setValues({ ...values, description: e.target.value })} />
    <output data-testid="values">{JSON.stringify(values)}</output>
    <output data-testid="inline-receipt">{fill.receipt ? 'present' : 'absent'}</output>
  </DslFormFillProvider>;
}
function Shell() {
  const c = useAuraBot();
  const [form, showForm] = useState(true);
  return <>
    {form && <Form />}
    <button onClick={() => void c.sendMessage(instruction)}>Fill</button>
    <button onClick={() => void c.sendMessage('请解释这些字段，不要填写当前表单')}>Ask</button>
    <button onClick={() => showForm(false)}>Leave</button>
    <button onClick={() => c.setSelectedAgent('delivery-agent')}>Named agent</button>
    <button onClick={() => c.undoFormFill?.()}>Undo</button>
    <output data-testid="receipt">{JSON.stringify(c.formFillReceipt?.result ?? null)}</output>
    <output data-testid="notice">{c.formFillNotice}</output>
    <output data-testid="chat">{JSON.stringify(c.state.messages)}</output>
  </>;
}
let callbacks: any;
let complete!: () => void;
const output = () => ({ action: 'form_fill', fields: { name: '材料复核', description: '检查清单' }, reviews: {
  name: { status: 'supported', quote: '材料复核' },
  description: { status: 'supported', quote: '检查清单' },
  date: { status: 'ambiguous', quote: '日期未确定', reason: 'uncertain_date' },
} });
async function begin(label = 'Fill') {
  render(<AuraBotProvider><Shell /></AuraBotProvider>);
  fireEvent.click(screen.getByText(label));
  await waitFor(() => expect(auraBotApi.chatStream).toHaveBeenCalled());
}
async function finish(result = output()) {
  await act(async () => { callbacks.onToolResult('fill', result, true); callbacks.onDone('Draft extracted'); complete(); });
}

describe('Bot and real controlled form draft boundary', () => {
  beforeEach(() => {
    localStorage.clear(); vi.clearAllMocks();
    vi.mocked(auraBotApi.ensureConversation).mockResolvedValue({ conversationId: 90, agentCode: 'aurabot', title: 'Draft' } as any);
    vi.mocked(auraBotApi.listConversations).mockResolvedValue([]);
    vi.mocked(auraBotApi.chatStream).mockImplementation((_request, c) => { callbacks = c; return new Promise<void>(resolve => { complete = resolve; }); });
  });
  beforeEach(() => {
    setDslFormFillBackend(dslFormFillBackend);
  });

  it('sends only editable fields and applies atomically after stream success', async () => {
    await begin();
    expect(vi.mocked(auraBotApi.chatStream).mock.calls[0][0]).toMatchObject({ agentCode: 'aurabot', formFill: { modelCode: 'task' } });
    expect(vi.mocked(auraBotApi.chatStream).mock.calls[0][0].formFill?.fields.map(f => f.code)).toEqual(['name', 'description', 'date']);
    act(() => { callbacks.onResultContract({ skillCode: 'platform.fill_form', data: { action: 'form_fill', instruction: 'private protocol' } }); callbacks.onToolResult('fill', output(), true); });
    expect(screen.getByTestId('chat')).not.toHaveTextContent('private protocol');
    expect(screen.getByLabelText('Name')).toHaveValue('');
    await act(async () => { callbacks.onDone('Draft extracted'); complete(); });
    expect(screen.getByLabelText('Name')).toHaveValue('材料复核');
    expect(screen.getByTestId('values')).toHaveTextContent('selected-project');
    expect(screen.getByTestId('values')).not.toHaveTextContent('"date":');
    expect(screen.getByTestId('inline-receipt')).toHaveTextContent('absent');
    expect(screen.getByTestId('receipt')).toHaveTextContent('uncertain_date');
  });
  it('does not mutate from a model form result during ordinary consultation', async () => {
    await begin('Ask');
    expect(vi.mocked(auraBotApi.chatStream).mock.calls[0][0].formFill).toBeUndefined();
    await finish();
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByTestId('receipt')).toHaveTextContent('null');
  });
  it('keeps existing values and undo preserves later human changes', async () => {
    await begin();
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Human note' } });
    await finish();
    expect(screen.getByLabelText('Description')).toHaveValue('Human note');
    expect(screen.getByTestId('receipt')).toHaveTextContent('conflicts');
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Human correction' } });
    fireEvent.click(screen.getByText('Undo'));
    expect(screen.getByLabelText('Name')).toHaveValue('Human correction');
    expect(screen.getByLabelText('Description')).toHaveValue('Human note');
    expect(screen.getByTestId('receipt')).toHaveTextContent('null');
  });
  it('undoes unedited AI values without calling the model again', async () => {
    await begin(); await finish();
    fireEvent.click(screen.getByText('Undo'));
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByLabelText('Description')).toHaveValue('');
    expect(auraBotApi.chatStream).toHaveBeenCalledTimes(1);
  });
  it('undoes only the latest fill and keeps an older fill intact', async () => {
    await begin(); await finish();
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: '' } });
    fireEvent.click(screen.getByText('Fill'));
    await waitFor(() => expect(auraBotApi.chatStream).toHaveBeenCalledTimes(2));
    await finish();
    fireEvent.click(screen.getByText('Undo'));
    expect(screen.getByLabelText('Name')).toHaveValue('材料复核');
    expect(screen.getByLabelText('Description')).toHaveValue('');
    expect(auraBotApi.chatStream).toHaveBeenCalledTimes(2);
  });
  it('rejects fabricated source evidence without applying any fields', async () => {
    await begin(); const value = output(); value.reviews.name.quote = 'Not in the source'; await finish(value);
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByTestId('notice')).toHaveTextContent('未填写草稿');
  });
  it('does not leave a partial draft when the stream fails after a tool result', async () => {
    await begin();
    await act(async () => { callbacks.onToolResult('fill', output(), true); callbacks.onError('Provider failed'); complete(); });
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByTestId('notice')).toHaveTextContent('未填写草稿');
  });
  it('discards an in-flight result after leaving the target form', async () => {
    await begin(); fireEvent.click(screen.getByText('Leave')); await finish();
    expect(screen.getByTestId('receipt')).toHaveTextContent('null');
    expect(screen.getByTestId('notice')).toHaveTextContent('未填入');
  });
  it('does not promote form extraction into a business confirmation', async () => {
    await begin();
    await act(async () => { callbacks.onConfirmRequired('bad', 'create', 'Unexpected', {}, 'pending'); callbacks.onDone(''); complete(); });
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByTestId('chat')).not.toHaveTextContent('confirm_card');
  });
  it('keeps named-agent execution scope intact instead of switching identity', async () => {
    render(<AuraBotProvider><Shell /></AuraBotProvider>);
    fireEvent.click(screen.getByText('Named agent')); fireEvent.click(screen.getByText('Fill'));
    await waitFor(() => expect(screen.getByTestId('notice')).toHaveTextContent('AuraBot'));
    expect(auraBotApi.chatStream).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });
});
