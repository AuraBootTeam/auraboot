import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  listConversations,
  createConversation,
  ask,
} from '../chatbiApi';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('chatbi v2 API — bare (un-enveloped) responses', () => {
  it('listConversations reads a bare array', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse([{ pid: 'c1', title: 'first' }]),
      ),
    );
    const list = await listConversations();
    expect(list).toHaveLength(1);
    expect(list[0].pid).toBe('c1');
  });

  it('createConversation returns the bare conversationPid', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ conversationPid: 'c9' })),
    );
    expect(await createConversation()).toBe('c9');
  });

  it('ask returns the ChatBiAnswer including a FAILED status', async () => {
    // Without an LLM provider the backend answers with status=FAILED (HTTP 200).
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({
          answerPid: 'a1',
          status: 'FAILED',
          errorMessage: 'Query execution failed',
        }),
      ),
    );
    const a = await ask('c1', '按状态统计');
    expect(a.status).toBe('FAILED');
    expect(a.errorMessage).toMatch(/failed/i);
  });

  it('ask returns a SUCCESS answer with rows', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({
          answerPid: 'a2',
          status: 'SUCCESS',
          rows: [{ status: 'active', cnt: 2 }],
          rowCount: 1,
          confidence: 0.9,
          sql: 'SELECT ...',
        }),
      ),
    );
    const a = await ask('c1', 'q');
    expect(a.status).toBe('SUCCESS');
    expect(a.rows?.[0].cnt).toBe(2);
  });

  it('surfaces a 403 as an error with the backend message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({ message: 'Access forbidden' }, 403),
      ),
    );
    await expect(createConversation()).rejects.toThrow(/forbidden/i);
  });
});
