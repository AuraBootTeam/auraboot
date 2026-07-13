import { describe, expect, it, vi, beforeEach } from 'vitest';
import { CsClient, TOKEN_STORAGE_KEY } from '../client';

/**
 * The frame parser and the failure paths. Both bit us for real: a stream that returned 200 with an
 * empty body left the widget showing an empty bubble forever, and a refusal came back as the
 * platform's generic "Business error" while the reason the site owner needed was in another field.
 */

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => void map.set(key, value),
    removeItem: (key: string) => void map.delete(key),
    clear: () => map.clear(),
    key: () => null,
    length: 0,
  } as unknown as Storage;
}

/** A fetch whose body streams the given SSE text, split at the given byte boundaries. */
function streamingFetch(sse: string, chunkAt: number[] = []) {
  const encoder = new TextEncoder();
  const pieces: Uint8Array[] = [];
  let start = 0;
  for (const at of [...chunkAt, sse.length]) {
    pieces.push(encoder.encode(sse.slice(start, at)));
    start = at;
  }
  let i = 0;
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: () =>
          Promise.resolve(i < pieces.length ? { done: false, value: pieces[i++] } : { done: true, value: undefined }),
      }),
    },
  });
}

const session = {
  visitorToken: 'vt_1',
  token: 'jwt_1',
  conversationPid: 'conv_1',
  handoffEnabled: true,
};

describe('CsClient.open', () => {
  let storage: Storage;
  beforeEach(() => {
    storage = memoryStorage();
  });

  it('sends the site key and remembers the visitor token it gets back', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => session });
    vi.stubGlobal('fetch', fetchMock);

    const client = new CsClient('https://api.test', 'csk_abc', storage);
    await client.open();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['X-Site-Key']).toBe('csk_abc');
    expect(storage.getItem(TOKEN_STORAGE_KEY)).toBe('vt_1');
  });

  it('sends the stored token back on a return visit — that is what makes the visitor recognisable', async () => {
    storage.setItem(TOKEN_STORAGE_KEY, 'vt_existing');
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => session });
    vi.stubGlobal('fetch', fetchMock);

    await new CsClient('https://api.test', 'csk_abc', storage).open();

    expect(JSON.parse(fetchMock.mock.calls[0][1].body).visitorToken).toBe('vt_existing');
  });

  it('forwards a signed identity, and never anything resembling a secret', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => session });
    vi.stubGlobal('fetch', fetchMock);

    await new CsClient('https://api.test', 'csk_abc', storage).open({
      externalUserId: 'alice',
      userHash: 'deadbeef',
    });

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).toEqual({ externalUserId: 'alice', userHash: 'deadbeef' });
    expect(JSON.stringify(body)).not.toContain('identitySecret');
  });

  it('surfaces the reason code the server put in `context`, not the generic message', async () => {
    // The platform's error envelope: the useful part is in `context`; `message` is boilerplate.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        text: async () => '{"code":"40000","message":"Business error","context":"origin_not_allowed"}',
      }),
    );

    await expect(new CsClient('https://api.test', 'csk_abc', storage).open()).rejects.toThrow('origin_not_allowed');
  });
});

describe('CsClient.send — SSE frames', () => {
  const handlers = () => ({ onChunk: vi.fn(), onDone: vi.fn(), onError: vi.fn() });

  async function open(fetchMock: ReturnType<typeof vi.fn>) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => session }));
    const client = new CsClient('https://api.test', 'csk_abc', memoryStorage());
    await client.open();
    vi.stubGlobal('fetch', fetchMock);
    return client;
  }

  it('assembles chunks and prefers the full content on done', async () => {
    const sse =
      'event:chunk\ndata:{"content":"Hel"}\n\n' +
      'event:chunk\ndata:{"content":"lo"}\n\n' +
      'event:done\ndata:{"content":"Hello there"}\n\n';
    const h = handlers();
    const client = await open(streamingFetch(sse));

    await client.send('hi', h);

    expect(h.onChunk.mock.calls.map((c) => c[0])).toEqual(['Hel', 'lo']);
    expect(h.onDone).toHaveBeenCalledWith('Hello there');
    expect(h.onError).not.toHaveBeenCalled();
  });

  it('handles a frame split across two network reads', async () => {
    const sse = 'event:chunk\ndata:{"content":"split"}\n\nevent:done\ndata:{"content":"split"}\n\n';
    const h = handlers();
    // Cut mid-JSON, which is exactly what a real socket does.
    const client = await open(streamingFetch(sse, [20]));

    await client.send('hi', h);

    expect(h.onChunk).toHaveBeenCalledWith('split');
    expect(h.onDone).toHaveBeenCalledWith('split');
  });

  it('reports an error frame instead of leaving the bubble empty', async () => {
    const h = handlers();
    const client = await open(streamingFetch('event:error\ndata:{"error":"rate_limited"}\n\n'));

    await client.send('hi', h);

    expect(h.onError).toHaveBeenCalledWith('rate_limited');
    expect(h.onDone).not.toHaveBeenCalled();
  });

  it('a 200 that streams nothing is an error, not a silent hang', async () => {
    // The exact shape of the SqlCountFilter bug: the server answers 200, the body is empty, and
    // the visitor would otherwise watch an empty bubble forever with no way to know.
    const h = handlers();
    const client = await open(streamingFetch(''));

    await client.send('hi', h);

    expect(h.onError).toHaveBeenCalledWith('no_response_from_assistant');
  });

  it('asks for an event stream — fetch does not add the header on its own', async () => {
    const fetchMock = streamingFetch('event:done\ndata:{"content":"ok"}\n\n');
    const client = await open(fetchMock);

    await client.send('hi', handlers());

    expect(fetchMock.mock.calls[0][1].headers.Accept).toBe('text/event-stream');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer jwt_1');
  });

  it('ignores frames it cannot parse rather than throwing at the visitor', async () => {
    const h = handlers();
    const client = await open(
      streamingFetch('event:chunk\ndata:{not json}\n\nevent:done\ndata:{"content":"fine"}\n\n'),
    );

    await client.send('hi', h);

    expect(h.onChunk).not.toHaveBeenCalled();
    expect(h.onDone).toHaveBeenCalledWith('fine');
  });
});

// ---------------------------------------------------------------------------
// M2 — asking for a human, and hearing one
// ---------------------------------------------------------------------------

describe('CsClient.escalate', () => {
  it('asks for a human without going through the AI', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ state: 'pending_handoff', seatsAvailable: 2 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const client = new CsClient('https://api.example.com', 'csk_1', memoryStorage());
    await openSession(client, fetchMock);

    const result = await client.escalate('refund on order 12345');

    expect(result).toEqual({ state: 'pending_handoff', seatsAvailable: 2 });
    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(url).toBe('https://api.example.com/api/public/cs/escalate');
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Bearer jwt-token');
    expect(JSON.parse(init.body)).toMatchObject({
      conversationPid: 'conv-1',
      reason: 'refund on order 12345',
    });
  });

  it('refuses before a session is open rather than posting a request with no identity', async () => {
    const client = new CsClient('https://api.example.com', 'csk_1', memoryStorage());
    await expect(client.escalate()).rejects.toThrow('session not open');
  });
});

describe('CsClient.listen — hearing a seat', () => {
  it('🚨 delivers a seat reply the visitor never asked for', async () => {
    const sse =
      'event: state\ndata: {"type":"state","state":"human_active"}\n\n' +
      'event: message\ndata: {"type":"message","seq":4,"senderType":"human",' +
      '"senderName":"Alice","content":"I can refund that for you."}\n\n';

    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const client = new CsClient('https://api.example.com', 'csk_1', memoryStorage());
    await openSession(client, fetchMock);
    fetchMock.mockImplementation(streamingFetch(sse));

    const messages: any[] = [];
    const states: any[] = [];
    const stop = client.listen({
      onMessage: (m) => messages.push(m),
      onState: (s) => states.push(s),
    });

    await vi.waitFor(() => expect(messages).toHaveLength(1));
    stop();

    expect(states[0].state).toBe('human_active');
    expect(messages[0]).toMatchObject({
      seq: 4,
      senderType: 'human',
      senderName: 'Alice',
      content: 'I can refund that for you.',
    });
  });

  it('sends the token in a header, never in the URL — it grants access to a person’s history', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const client = new CsClient('https://api.example.com', 'csk_1', memoryStorage());
    await openSession(client, fetchMock);
    fetchMock.mockImplementation(streamingFetch(''));

    const stop = client.listen({ onMessage: () => {} });
    await vi.waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(1));
    stop();

    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(url).not.toContain('jwt-token');           // not in the query string
    expect(url).toContain('conversationPid=conv-1');
    expect(init.headers.Authorization).toBe('Bearer jwt-token');
    expect(init.headers.Accept).toBe('text/event-stream');
  });

  it('stops listening when told to, instead of reconnecting forever', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const client = new CsClient('https://api.example.com', 'csk_1', memoryStorage());
    await openSession(client, fetchMock);
    fetchMock.mockImplementation(streamingFetch(''));

    const stop = client.listen({ onMessage: () => {} });
    await vi.waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(1));
    const callsWhenStopped = fetchMock.mock.calls.length;
    stop();

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(fetchMock.mock.calls.length).toBe(callsWhenStopped);
  });
});

/** Open a session so the client has a token and a conversation to work with. */
async function openSession(client: CsClient, fetchMock: any): Promise<void> {
  fetchMock.mockResolvedValueOnce({
    ok: true,
    json: async () => ({
      visitorToken: 'vt-1',
      token: 'jwt-token',
      conversationPid: 'conv-1',
      handoffEnabled: true,
    }),
  });
  await client.open();
}
