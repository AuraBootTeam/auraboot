import { afterEach, describe, expect, it, vi } from 'vitest';

import { auraBotApi } from '../auraBotApi';

function sseResponse(content = 'ok') {
  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(
          new TextEncoder().encode(`event: done\ndata: {"content":"${content}"}\n\n`),
        );
        controller.close();
      },
    }),
    { status: 200, statusText: 'OK' },
  );
}

describe('auraBotApi SSE requests', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('requests SSE for chat streams', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse());
    vi.stubGlobal('fetch', fetchMock);

    await auraBotApi.chatStream(
      { sessionId: 'session-1', message: 'hello' },
      {
        onChunk: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
      },
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/ai/aurabot/chat/stream',
      expect.objectContaining({
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
      }),
    );
  });

  it('requests SSE when confirming a pending tool execution', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse());
    vi.stubGlobal('fetch', fetchMock);

    await auraBotApi.executeStream(
      { sessionId: 'session-1', toolId: 'tool-1', confirmed: true },
      {
        onChunk: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
      },
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/ai/aurabot/execute',
      expect.objectContaining({
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
      }),
    );
  });
});

// =============================================================================
// D.2 — provider warnings → onWarning callback (or default `aura:toast` fallback)
// =============================================================================

function warningThenDoneSse(payload: string) {
  // Backend wire shape: SseResponseSink.onWarnings sends sendJsonString("warning", {warnings:[..]})
  // which lands as `data: <json-string>` on the wire. The processSSEStream
  // helper parses that with JSON.parse so the test mirrors the exact bytes.
  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(
          new TextEncoder().encode(
            `event: warning\ndata: ${payload}\n\n` +
              'event: done\ndata: {"content":"ok"}\n\n',
          ),
        );
        controller.close();
      },
    }),
    { status: 200, statusText: 'OK' },
  );
}

describe('auraBotApi SSE warnings (D.2)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('invokes onWarning when an event:warning frame arrives', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      warningThenDoneSse(
        JSON.stringify({
          warnings: ['Extended Thinking budget auto-extended max_tokens to 14000.'],
        }),
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const onWarning = vi.fn();
    await auraBotApi.chatStream(
      { sessionId: 'session-1', message: 'hello' },
      {
        onChunk: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
        onWarning,
      },
    );

    expect(onWarning).toHaveBeenCalledTimes(1);
    expect(onWarning).toHaveBeenCalledWith([
      'Extended Thinking budget auto-extended max_tokens to 14000.',
    ]);
  });

  it('falls back to dispatching aura:toast events when onWarning is not provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      warningThenDoneSse(
        JSON.stringify({
          warnings: ['warn-A', 'warn-B'],
        }),
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const dispatched: Array<{ message: string; variant: string }> = [];
    const handler = (e: Event) => {
      const ce = e as CustomEvent<{ message: string; variant: string }>;
      dispatched.push({ message: ce.detail.message, variant: ce.detail.variant });
    };
    window.addEventListener('aura:toast', handler);
    try {
      await auraBotApi.chatStream(
        { sessionId: 'session-1', message: 'hello' },
        {
          onChunk: vi.fn(),
          onDone: vi.fn(),
          onError: vi.fn(),
          // no onWarning — default behaviour must dispatch one toast per warning
        },
      );
    } finally {
      window.removeEventListener('aura:toast', handler);
    }

    expect(dispatched).toEqual([
      { message: 'warn-A', variant: 'warning' },
      { message: 'warn-B', variant: 'warning' },
    ]);
  });

  it('does not invoke onWarning when warnings array is empty', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(warningThenDoneSse(JSON.stringify({ warnings: [] })));
    vi.stubGlobal('fetch', fetchMock);

    const onWarning = vi.fn();
    await auraBotApi.chatStream(
      { sessionId: 'session-1', message: 'hello' },
      {
        onChunk: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
        onWarning,
      },
    );
    expect(onWarning).not.toHaveBeenCalled();
  });
});
