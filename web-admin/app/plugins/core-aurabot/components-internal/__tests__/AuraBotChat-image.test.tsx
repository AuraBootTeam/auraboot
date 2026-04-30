/**
 * AuraBotChat-image.test.tsx
 *
 * P1 — Vision: pins the image-attachment UX in AuraBotChat.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code attachedImagePreviewIsShown} — staged file renders as a chip with
 *       the original name and a remove button.</li>
 *   <li>{@code removeButtonClearsAttachment} — chip is dropped after click.</li>
 *   <li>{@code userAttachesImage_messageBodyIncludesBase64ContentBlock} —
 *       sendMessage receives the {@code attachments} array carrying
 *       {@code mediaType + data} (raw base64, no data: prefix).</li>
 *   <li>{@code userSendsTextOnly_messageBodyIsString} — backward compat: no
 *       attachments → sendMessage second arg is undefined.</li>
 *   <li>{@code sendButtonEnabledOnAttachmentEvenWithoutText} — image-only sends
 *       are allowed.</li>
 * </ul>
 *
 * <p>FileReader is jsdom-built-in; we feed it a valid {@code data:image/...;base64,...}
 * URI through the {@code File} ctor and let the component strip the prefix.
 */

import React, { useState } from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

import { AuraBotChat } from '../AuraBotChat';
import {
  AuraBotCtx,
  type ChatImageAttachment,
} from '../../components-shell/AuraBotProvider';

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

// ============================================================================
// Mock context — minimal surface to render AuraBotChat without dragging in
// session/network init from the real provider.
// ============================================================================

const SAMPLE_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';

interface SendCall {
  text: string;
  attachments?: ChatImageAttachment[];
}

/**
 * Tiny harness that owns {@code inputValue} so the textarea behaves like a
 * real controlled input. The component reads {@code state.inputValue} and
 * calls {@code setInputValue} on every keystroke — without the round-trip,
 * fireEvent.change leaves state at "" and the send button stays disabled.
 */
function ChatHarness({ onSend }: { onSend: (call: SendCall) => void }) {
  const [inputValue, setInputValue] = useState('');

  const ctx: any = {
    state: {
      panelState: 'open',
      sessionId: 'test-session',
      currentConversationId: null,
      messages: [],
      isLoading: false,
      pageContext: {},
      inputValue,
      selectedAgentCode: 'default',
      selectedKnowledgeBaseIds: [],
      knowledgeBases: [],
    },
    sessions: [],
    openPanel: vi.fn(),
    closePanel: vi.fn(),
    togglePanel: vi.fn(),
    sendMessage: (content: string, attachments?: ChatImageAttachment[]) => {
      onSend({ text: content, attachments });
    },
    confirmTool: vi.fn(),
    cancelTool: vi.fn(),
    clearMessages: vi.fn(),
    newSession: vi.fn(),
    selectSession: vi.fn(),
    deleteSession: vi.fn(),
    setInputValue,
    setPageContext: vi.fn(),
    setSelectedAgent: vi.fn(),
    toggleKnowledgeBase: vi.fn(),
    registerFormFillHandler: vi.fn(),
    unregisterFormFillHandler: vi.fn(),
  };

  return (
    <AuraBotCtx.Provider value={ctx}>
      <AuraBotChat />
    </AuraBotCtx.Provider>
  );
}

function renderChat(): { sendCalls: SendCall[] } {
  const sendCalls: SendCall[] = [];
  render(<ChatHarness onSend={(c) => sendCalls.push(c)} />);
  return { sendCalls };
}

// Build a File whose FileReader.readAsDataURL output starts with the data:
// URI prefix that AuraBotChat strips before staging.
function makeImageFile(name: string, mediaType: string, base64: string): File {
  // Decode the base64 so the File holds real bytes; FileReader will then
  // re-encode in jsdom and produce a data: URI with the same payload.
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new File([bytes], name, { type: mediaType });
}

// ============================================================================
// Tests
// ============================================================================

describe('AuraBotChat — image attachment (P1 Vision)', () => {
  it('attachedImagePreviewIsShown — chip renders with file name and remove button', async () => {
    renderChat();

    const fileInput = screen.getByTestId('aurabot-file-input') as HTMLInputElement;
    const file = makeImageFile('cat.png', 'image/png', SAMPLE_BASE64);

    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByTestId('aurabot-attachments-preview')).toBeInTheDocument();
    });
    expect(screen.getByTestId('aurabot-attachment-0')).toBeInTheDocument();
    expect(screen.getByText('cat.png')).toBeInTheDocument();
    expect(screen.getByTestId('aurabot-attachment-remove-0')).toBeInTheDocument();
  });

  it('removeButtonClearsAttachment — chip disappears after click', async () => {
    renderChat();

    const fileInput = screen.getByTestId('aurabot-file-input') as HTMLInputElement;
    fireEvent.change(fileInput, {
      target: { files: [makeImageFile('cat.png', 'image/png', SAMPLE_BASE64)] },
    });

    await waitFor(() => screen.getByTestId('aurabot-attachment-0'));

    fireEvent.click(screen.getByTestId('aurabot-attachment-remove-0'));

    await waitFor(() => {
      expect(screen.queryByTestId('aurabot-attachments-preview')).not.toBeInTheDocument();
    });
  });

  it('userAttachesImage_messageBodyIncludesBase64ContentBlock — sendMessage carries attachments', async () => {
    const { sendCalls } = renderChat();

    // 1. Stage an image
    const fileInput = screen.getByTestId('aurabot-file-input') as HTMLInputElement;
    fireEvent.change(fileInput, {
      target: { files: [makeImageFile('cat.png', 'image/png', SAMPLE_BASE64)] },
    });

    await waitFor(() => screen.getByTestId('aurabot-attachment-0'));

    // 2. Type a prompt and send
    const textarea = screen.getByPlaceholderText(/Type a message/i) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'what is in this image?' } });

    fireEvent.click(screen.getByTestId('aurabot-send'));

    // 3. The send-with-attachments path should clear the staged preview
    await waitFor(() => {
      expect(screen.queryByTestId('aurabot-attachments-preview')).not.toBeInTheDocument();
    });

    // 4. sendMessage was invoked with both text and the attachment payload
    expect(sendCalls).toHaveLength(1);
    const call = sendCalls[0];
    expect(call.text).toBe('what is in this image?');
    expect(call.attachments).toBeDefined();
    expect(call.attachments).toHaveLength(1);
    expect(call.attachments![0].mediaType).toBe('image/png');
    // Base64 round-trips through FileReader → strip prefix; payload must match.
    expect(call.attachments![0].data).toBe(SAMPLE_BASE64);
    // Raw base64 has NO data: prefix
    expect(call.attachments![0].data).not.toContain('data:image');
    expect(call.attachments![0].name).toBe('cat.png');
  });

  it('userSendsTextOnly_messageBodyIsString — no attachments → second arg undefined', () => {
    // Backward-compat: when the user types without staging an image, the
    // sendMessage call must look exactly like the pre-P1 path.
    const { sendCalls } = renderChat();

    const textarea = screen.getByPlaceholderText(/Type a message/i) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'plain hello' } });
    fireEvent.click(screen.getByTestId('aurabot-send'));

    expect(sendCalls).toHaveLength(1);
    expect(sendCalls[0].text).toBe('plain hello');
    expect(sendCalls[0].attachments).toBeUndefined();
  });

  it('sendButtonEnabledOnAttachmentEvenWithoutText — image-only send allowed', async () => {
    const { sendCalls } = renderChat();

    const fileInput = screen.getByTestId('aurabot-file-input') as HTMLInputElement;
    fireEvent.change(fileInput, {
      target: { files: [makeImageFile('only.png', 'image/png', SAMPLE_BASE64)] },
    });

    await waitFor(() => screen.getByTestId('aurabot-attachment-0'));

    const sendBtn = screen.getByTestId('aurabot-send') as HTMLButtonElement;
    expect(sendBtn).not.toBeDisabled();

    fireEvent.click(sendBtn);
    await waitFor(() => expect(sendCalls).toHaveLength(1));

    // text is whatever the textarea held (empty), attachments carry the image
    expect(sendCalls[0].attachments).toHaveLength(1);
    expect(sendCalls[0].attachments![0].name).toBe('only.png');
  });

  it('rejectsUnsupportedMimeType — surfaces a validation error and skips staging', async () => {
    renderChat();

    const fileInput = screen.getByTestId('aurabot-file-input') as HTMLInputElement;
    // image/bmp is not in the accepted MIME list
    const bad = new File([new Uint8Array([0x42, 0x4d])], 'bad.bmp', { type: 'image/bmp' });
    fireEvent.change(fileInput, { target: { files: [bad] } });

    await waitFor(() => {
      expect(screen.getByTestId('aurabot-attachment-error')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('aurabot-attachments-preview')).not.toBeInTheDocument();
  });
});
