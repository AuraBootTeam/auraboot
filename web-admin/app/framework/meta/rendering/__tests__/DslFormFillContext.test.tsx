import React, { useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DslFormFillProvider, useDslFormFill } from '../DslFormFillContext';
import { useFormFill } from '../useFormFill';
import type { FormFillTarget } from '../formFill';

const mocks = vi.hoisted(() => ({ register: vi.fn(), chat: vi.fn(), conversation: vi.fn() }));
vi.mock('~/plugins/core-aurabot/hooks/useAuraBotSafe', () => ({
  useAuraBotSafe: () => ({ registerFormFillTarget: mocks.register }),
}));
vi.mock('~/plugins/core-aurabot/services/auraBotApi', () => ({
  auraBotApi: { chatStream: mocks.chat, ensureConversation: mocks.conversation },
}));
let pending: Promise<void>;
function Entry() {
  const api = useDslFormFill();
  return (
    <button
      onClick={() => {
        pending = api.extractFromText('Customer Acme');
      }}
    >
      Extract
    </button>
  );
}
function Form({ identity = 'one' }: { identity?: string }) {
  const [values, setValues] = useState<Record<string, unknown>>({});
  const fill = useFormFill(
    identity,
    'customer',
    [
      { code: 'name', label: 'Name', type: 'string' },
      { code: 'secret', label: 'Secret', type: 'string', locked: true },
    ],
    values,
    setValues,
    true,
  );
  return (
    <DslFormFillProvider target={fill.target}>
      <input
        aria-label="Name"
        value={String(values.name ?? '')}
        onChange={(e) => setValues({ ...values, name: e.target.value })}
      />
      <Entry />
      <button onClick={fill.undo}>Undo</button>
    </DslFormFillProvider>
  );
}

describe('real form state fill boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.register.mockReturnValue(vi.fn());
    mocks.conversation.mockResolvedValue({ conversationId: 42 });
  });
  it('registered chat target updates controlled inputs and preserves later human edits', () => {
    render(<Form />);
    const target: FormFillTarget = mocks.register.mock.calls[0][0];
    act(() => {
      target.apply({ name: 'Acme', secret: 'no' });
    });
    expect(screen.getByLabelText('Name')).toHaveValue('Acme');
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Customer correction' } });
    fireEvent.click(screen.getByText('Undo'));
    expect(screen.getByLabelText('Name')).toHaveValue('Customer correction');
  });
  it('sends only editable fields through the canonical conversation endpoint', async () => {
    mocks.chat.mockImplementation(async (_request, callbacks) => {
      callbacks.onToolResult(
        'tool-1',
        {
          action: 'form_fill',
          fields: { name: 'Acme' },
          reviews: { name: { status: 'supported', quote: 'Acme' } },
        },
        true,
      );
      callbacks.onToolResult('tool-1', { action: 'form_fill', fields: { name: 'Repeated' } }, true);
    });
    render(<Form />);
    fireEvent.click(screen.getByText('Extract'));
    await act(async () => {
      await pending;
    });
    expect(screen.getByLabelText('Name')).toHaveValue('Acme');
    expect(mocks.chat.mock.calls[0][0]).toMatchObject({
      conversationId: 42,
      agentCode: 'aurabot',
      formFill: { fields: [{ code: 'name' }] },
    });
    fireEvent.click(screen.getByText('Undo'));
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });
  it('rejects a response after changing the target form', async () => {
    let complete!: () => void;
    mocks.chat.mockImplementation(
      (_request, callbacks) =>
        new Promise<void>((resolve) => {
          complete = () => {
            callbacks.onToolResult(
              'tool-1',
              { action: 'form_fill', fields: { name: 'Old form' } },
              true,
            );
            resolve();
          };
        }),
    );
    const view = render(<Form />);
    fireEvent.click(screen.getByText('Extract'));
    await waitFor(() => expect(mocks.chat).toHaveBeenCalled());
    view.rerender(<Form identity="two" />);
    await act(async () => {
      complete();
      await expect(pending).rejects.toThrow('ai.fill.target_changed');
    });
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });
  it('keeps the original draft when the stream fails after a tool result', async () => {
    mocks.chat.mockImplementation(async (_request, callbacks) => {
      callbacks.onToolResult(
        'tool-1',
        { action: 'form_fill', fields: { name: 'Partial result' } },
        true,
      );
      callbacks.onError('network failure');
    });
    render(<Form />);
    fireEvent.click(screen.getByText('Extract'));
    await act(async () => {
      await expect(pending).rejects.toThrow('network failure');
    });
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });
});
