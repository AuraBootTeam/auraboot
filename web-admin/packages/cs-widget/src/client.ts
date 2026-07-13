/**
 * Talks to /api/public/cs/**. No DOM in here, so it can be unit-tested without a browser.
 */

export interface CsSession {
  visitorToken: string;
  token: string;
  conversationPid: string;
  welcomeMessage?: string;
  themeColor?: string;
  handoffEnabled: boolean;
}

export interface CsIdentity {
  /** The host site's own user id. */
  externalUserId: string;
  /**
   * HMAC-SHA256(site identity secret, externalUserId), hex — computed on the HOST SITE'S SERVER.
   * The secret never reaches the browser, which is the whole point: without this, anyone could
   * claim to be any user and read that user's conversation history.
   */
  userHash: string;
}

export interface StreamHandlers {
  onChunk: (text: string) => void;
  onDone: (fullContent: string) => void;
  onError: (message: string) => void;
}

const TOKEN_STORAGE_KEY = 'aura-cs-visitor-token';

export class CsClient {
  private session: CsSession | null = null;

  constructor(
    private readonly apiBase: string,
    private readonly siteKey: string,
    private readonly storage: Storage | null,
  ) {}

  get current(): CsSession | null {
    return this.session;
  }

  /**
   * Open a session. Sends the token this browser already has, if any — that is what makes a
   * returning visitor recognisable, and it is device-local by construction.
   */
  async open(identity?: CsIdentity): Promise<CsSession> {
    const body: Record<string, unknown> = {};
    const stored = this.storage?.getItem(TOKEN_STORAGE_KEY);
    if (stored) body.visitorToken = stored;
    if (identity) {
      body.externalUserId = identity.externalUserId;
      body.userHash = identity.userHash;
    }

    const response = await fetch(`${this.apiBase}/api/public/cs/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Site-Key': this.siteKey },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      throw new Error(await this.describeFailure(response));
    }

    const session = (await response.json()) as CsSession;
    this.storage?.setItem(TOKEN_STORAGE_KEY, session.visitorToken);
    this.session = session;
    return session;
  }

  /**
   * Send a message and stream the answer.
   *
   * EventSource cannot POST, so the stream is read off the fetch body and the SSE frames are
   * parsed here. The frames are the platform's: `event: chunk|done|error` with a JSON `data:` line.
   */
  async send(message: string, handlers: StreamHandlers): Promise<void> {
    if (!this.session) throw new Error('session not open');

    const response = await fetch(`${this.apiBase}/api/public/cs/message`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // fetch() does not add this on its own the way EventSource does, and the server uses it to
        // recognise a stream. Without it the response gets buffered and arrives empty.
        Accept: 'text/event-stream',
        Authorization: `Bearer ${this.session.token}`,
      },
      body: JSON.stringify({
        conversationPid: this.session.conversationPid,
        message,
        clientMsgId: `cs-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      }),
    });

    if (!response.ok || !response.body) {
      handlers.onError(await this.describeFailure(response));
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let sawAnything = false;

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // Frames are separated by a blank line. Keep the trailing partial frame in the buffer.
      const frames = buffer.split('\n\n');
      buffer = frames.pop() ?? '';

      for (const frame of frames) {
        if (this.dispatch(frame, handlers)) sawAnything = true;
      }
    }

    // A 200 that streams nothing at all. It happens when the turn cannot resolve an LLM provider:
    // the server completes the emitter without ever emitting, and the visitor is left watching an
    // empty bubble forever. Say something rather than hang.
    if (!sawAnything) {
      handlers.onError('no_response_from_assistant');
    }
  }

  /** @returns true when the frame was one we understood and acted on. */
  private dispatch(frame: string, handlers: StreamHandlers): boolean {
    let event = 'message';
    const dataLines: string[] = [];

    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) return false;

    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(dataLines.join('\n'));
    } catch {
      return false; // A frame we cannot read is not a frame we should act on.
    }

    if (event === 'chunk' && typeof payload.content === 'string') {
      handlers.onChunk(payload.content);
      return true;
    }
    if (event === 'done') {
      handlers.onDone(typeof payload.content === 'string' ? payload.content : '');
      return true;
    }
    if (event === 'error') {
      handlers.onError(typeof payload.error === 'string' ? payload.error : 'unknown error');
      return true;
    }
    return false;
  }

  private async describeFailure(response: Response): Promise<string> {
    // The server answers with a stable reason code (site_key_invalid, origin_not_allowed,
    // identity_hash_invalid, rate_limited). Surfacing it beats a generic "something went wrong",
    // because every one of them is an integration mistake the site owner can actually fix.
    try {
      const text = await response.text();
      // The platform wraps errors as {code, message, context}. The stable machine-readable reason
      // (origin_not_allowed, site_key_invalid, identity_hash_invalid, rate_limited) lands in
      // `context`; `message` is the generic "Business error". Prefer the one that says something.
      const contextual = /"context"\s*:\s*"([^"]+)"/.exec(text);
      if (contextual) return contextual[1];
      const match = /"(?:message|error|reason)"\s*:\s*"([^"]+)"/.exec(text);
      if (match) return match[1];
      if (text) return text.slice(0, 200);
    } catch {
      // fall through
    }
    return `request failed (${response.status})`;
  }
}

export { TOKEN_STORAGE_KEY };
