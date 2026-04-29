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
