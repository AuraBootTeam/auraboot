/**
 * SSE streaming client for Aura CLI.
 * Consumes Server-Sent Events and streams content to the terminal.
 */
export interface SseOptions {
  url: string;
  token: string;
  body: any;
  onContent: (text: string) => void;
  onDone: () => void;
  onError: (error: string) => void;
}

/**
 * Stream a POST SSE endpoint, calling callbacks for each event.
 * Uses native fetch with streaming reader (no external dependency).
 */
export async function streamSse(options: SseOptions): Promise<void> {
  let resp: Response;
  try {
    resp = await fetch(options.url, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${options.token}`,
        'Accept': 'text/event-stream',
      },
      body: JSON.stringify(options.body),
    });
  } catch (err) {
    const msg = (err as Error).message;
    if (msg.includes('ECONNREFUSED') || msg.includes('fetch failed')) {
      options.onError(`Cannot connect to server. Is it running?`);
    } else {
      options.onError(`Network error: ${msg}`);
    }
    return;
  }

  if (resp.status === 401) {
    options.onError('Session expired. Run: aura login');
    return;
  }

  if (!resp.ok) {
    const text = await resp.text();
    options.onError(`Request failed (${resp.status}): ${text}`);
    return;
  }

  if (!resp.body) {
    options.onError('No response body');
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (data === '[DONE]') {
            options.onDone();
            return;
          }
          try {
            const parsed = JSON.parse(data);
            // Handle different SSE event formats from AuraBot
            const content = parsed.content || parsed.delta?.content || parsed.text || '';
            if (content) {
              options.onContent(content);
            }
          } catch {
            // Non-JSON data line — treat as raw content
            if (data) options.onContent(data);
          }
        }
      }
    }
    options.onDone();
  } catch (err) {
    options.onError((err as Error).message);
  }
}
