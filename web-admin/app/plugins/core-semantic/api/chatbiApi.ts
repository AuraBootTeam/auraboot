/**
 * ChatBI v2 API client — the conversational-analytics REST surface
 * ({@code /api/chatbi/v2/**}, guarded by meta.chatbi.use).
 *
 * These endpoints return their payloads *bare* (the controller returns
 * ResponseEntity<T>, not the ApiResponse envelope), so this client reads the
 * raw JSON and keys off the HTTP status + the answer's own {@code status} field.
 *
 * Configured LLM providers are the primary NL translator. With no provider the
 * router downgrades to a catalog-bound deterministic parser: questions that
 * explicitly name a real metric/dimension remain usable, while unmatched or
 * ambiguous questions fail honestly instead of inventing a query.
 */

export interface ChatBiConversation {
  pid: string;
  tenantId?: number;
  title?: string;
  semanticModelPid?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface DisambiguationCandidate {
  type: string;
  code: string;
  label: string;
  score: number;
}

export interface Disambiguation {
  ambiguousTerm: string;
  candidates: DisambiguationCandidate[];
  disambiguationLogPid?: string;
}

export interface ChatBiAnswer {
  answerPid: string;
  conversationPid?: string;
  status: 'SUCCESS' | 'DISAMBIGUATION' | 'FAILED' | string;
  errorMessage?: string;
  nlQuery?: string;
  confidence?: number;
  suggestedFollowUps?: string[];
  disambiguation?: Disambiguation;
  rows?: Array<Record<string, unknown>>;
  rowCount?: number;
  durationMs?: number;
  vizType?: string;
  sql?: string;
  llmUsed?: string;
}

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    // Guarded endpoints return 401/403 as the platform error envelope; surface its message.
    let msg = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as {
        message?: string;
        context?: string | { detail?: string };
      };
      const ctx =
        typeof body.context === 'object' ? body.context?.detail : body.context;
      msg = body.message || ctx || msg;
    } catch {
      /* non-JSON body */
    }
    throw new Error(msg);
  }
  return (await res.json()) as T;
}

export async function listConversations(limit = 20): Promise<ChatBiConversation[]> {
  return readJson<ChatBiConversation[]>(
    await fetch(`/api/chatbi/v2/conversations?limit=${limit}`),
  );
}

export async function createConversation(
  semanticModelPid?: string,
): Promise<string> {
  const res = await fetch('/api/chatbi/v2/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(semanticModelPid ? { semanticModelPid } : {}),
  });
  const body = await readJson<{ conversationPid: string }>(res);
  return body.conversationPid;
}

export async function closeConversation(pid: string): Promise<boolean> {
  const body = await readJson<{ closed: boolean }>(
    await fetch(`/api/chatbi/v2/conversations/${encodeURIComponent(pid)}`, {
      method: 'DELETE',
    }),
  );
  return body.closed;
}

export async function ask(
  conversationPid: string,
  question: string,
  semanticModelPid?: string,
): Promise<ChatBiAnswer> {
  const res = await fetch(
    `/api/chatbi/v2/conversations/${encodeURIComponent(conversationPid)}/ask`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, semanticModelPid }),
    },
  );
  return readJson<ChatBiAnswer>(res);
}

export async function resetContext(pid: string): Promise<boolean> {
  const body = await readJson<{ reset: boolean }>(
    await fetch(`/api/chatbi/v2/conversations/${encodeURIComponent(pid)}/reset`, {
      method: 'POST',
    }),
  );
  return body.reset;
}

export async function recordDisambiguation(
  conversationPid: string,
  disambiguationLogPid: string,
  chosenCode: string,
): Promise<boolean> {
  const body = await readJson<{ recorded: boolean }>(
    await fetch(
      `/api/chatbi/v2/conversations/${encodeURIComponent(conversationPid)}/disambiguate`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ disambiguationLogPid, chosenCode }),
      },
    ),
  );
  return body.recorded;
}
