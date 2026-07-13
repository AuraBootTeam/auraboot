import { CsClient, type CsIdentity, type CsSession } from './client';

/**
 * The visible widget: a launcher bubble and a chat panel.
 *
 * Everything lives inside a Shadow DOM. This runs on someone else's website, whose CSS we have
 * never seen and must not be affected by — a stray `* { box-sizing: content-box }` or a global
 * `button { }` rule would otherwise wreck the panel, and our styles would leak back into their
 * page. The shadow root is the only reliable boundary in a browser.
 */

const PANEL_WIDTH = 360;
const DEFAULT_ACCENT = '#2563eb';

/**
 * The widget's own chrome, in whatever language the host site speaks.
 *
 * The conversation itself is already the site's: the welcome message comes from its configuration
 * and the AI answers in the visitor's language. Only the frame around it — the button labels — is
 * ours, and a Chinese shop should not be forced to show an English "Send" any more than a German one
 * should be forced to show a Chinese "发送". Defaults are English because that is what M1 shipped and
 * a silent change of language on upgrade would be worse than a plain default.
 */
export interface WidgetStrings {
  title: string;
  send: string;
  placeholder: string;
  handoff: string;
  /** Shown after asking for a person, when someone is online to take it. */
  handoffQueued: string;
  /** Shown after asking for a person, when nobody is. Honest rather than reassuring. */
  handoffQueuedNoSeats: string;
  handoffTaken: string;
  closed: string;
}

export const DEFAULT_STRINGS: WidgetStrings = {
  title: 'Chat',
  send: 'Send',
  placeholder: 'Type a message',
  handoff: 'Talk to a human',
  handoffQueued: 'Connecting you to an agent…',
  handoffQueuedNoSeats: 'You are in the queue. An agent will reply as soon as one is available.',
  handoffTaken: 'An agent has joined the conversation.',
  closed: 'This conversation has ended.',
};

export interface WidgetOptions {
  apiBase: string;
  siteKey: string;
  identity?: CsIdentity;
  /** Overrides for any of the chrome strings. Anything omitted keeps its English default. */
  strings?: Partial<WidgetStrings>;
}

export class CsWidget {
  private readonly host: HTMLElement;
  private readonly root: ShadowRoot;
  private readonly client: CsClient;
  private readonly text: WidgetStrings;

  private panel!: HTMLElement;
  private messages!: HTMLElement;
  private input!: HTMLTextAreaElement;
  private sendButton!: HTMLButtonElement;
  private handoffButton!: HTMLButtonElement;
  private open = false;
  /** Set once a seat has the conversation: the AI is no longer answering, and the UI must say so. */
  private handedOver = false;
  private stopListening: (() => void) | null = null;
  private busy = false;
  private started = false;

  constructor(private readonly options: WidgetOptions) {
    this.text = { ...DEFAULT_STRINGS, ...(options.strings ?? {}) };
    this.client = new CsClient(options.apiBase, options.siteKey, safeLocalStorage());

    this.host = document.createElement('div');
    this.host.setAttribute('data-aura-cs', 'root');
    this.root = this.host.attachShadow({ mode: 'open' });
    document.body.appendChild(this.host);

    this.render(DEFAULT_ACCENT);
  }

  private render(accent: string): void {
    this.root.innerHTML = `
      <style>
        :host { all: initial; }
        .launcher {
          position: fixed; right: 20px; bottom: 20px; z-index: 2147483000;
          width: 56px; height: 56px; border-radius: 50%; border: 0; cursor: pointer;
          background: ${accent}; color: #fff; font-size: 24px; line-height: 1;
          box-shadow: 0 6px 20px rgba(0,0,0,.18);
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
        }
        .launcher:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
        .panel {
          position: fixed; right: 20px; bottom: 88px; z-index: 2147483000;
          width: ${PANEL_WIDTH}px; max-width: calc(100vw - 40px);
          height: 480px; max-height: calc(100vh - 120px);
          display: none; flex-direction: column;
          background: #fff; color: #111827; border-radius: 12px; overflow: hidden;
          box-shadow: 0 12px 40px rgba(0,0,0,.22);
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif; font-size: 14px;
        }
        .panel[data-open="true"] { display: flex; }
        .header { padding: 12px 16px; background: ${accent}; color: #fff; font-weight: 600; }
        .messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
        .msg { padding: 8px 12px; border-radius: 10px; max-width: 80%; white-space: pre-wrap; word-break: break-word; }
        .msg[data-from="visitor"] { align-self: flex-end; background: ${accent}; color: #fff; }
        .msg[data-from="agent"] { align-self: flex-start; background: #f3f4f6; color: #111827; }
        .msg[data-from="system"] { align-self: center; background: #fef2f2; color: #991b1b; font-size: 12px; }
        .composer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #e5e7eb; }
        textarea {
          flex: 1; resize: none; height: 40px; padding: 8px 10px; font: inherit; color: inherit;
          border: 1px solid #d1d5db; border-radius: 8px; background: #fff;
        }
        textarea:focus-visible { outline: 2px solid ${accent}; outline-offset: -1px; border-color: ${accent}; }
        button.send {
          height: 40px; padding: 0 16px; border: 0; border-radius: 8px; cursor: pointer;
          background: ${accent}; color: #fff; font: inherit; font-weight: 600;
        }
        button.send:disabled { opacity: .5; cursor: not-allowed; }
        button.send:focus-visible { outline: 2px solid ${accent}; outline-offset: 2px; }
      </style>
      <button class="launcher" part="launcher" data-testid="cs-launcher" aria-label="Chat">&#128172;</button>
      <section class="panel" part="panel" data-testid="cs-panel" data-open="false" role="dialog" aria-label="Chat">
        <header class="header" data-testid="cs-header">
          <span>${escapeHtml(this.text.title)}</span>
          <button class="handoff" data-testid="cs-handoff" hidden>${escapeHtml(this.text.handoff)}</button>
        </header>
        <div class="messages" data-testid="cs-messages"></div>
        <div class="composer">
          <textarea data-testid="cs-input" placeholder="${escapeHtml(this.text.placeholder)}"></textarea>
          <button class="send" data-testid="cs-send">${escapeHtml(this.text.send)}</button>
        </div>
      </section>
    `;

    const launcher = this.root.querySelector<HTMLButtonElement>('.launcher')!;
    this.panel = this.root.querySelector<HTMLElement>('.panel')!;
    this.messages = this.root.querySelector<HTMLElement>('.messages')!;
    this.input = this.root.querySelector<HTMLTextAreaElement>('textarea')!;
    this.sendButton = this.root.querySelector<HTMLButtonElement>('button.send')!;
    this.handoffButton = this.root.querySelector<HTMLButtonElement>('button.handoff')!;

    launcher.addEventListener('click', () => void this.toggle());
    this.sendButton.addEventListener('click', () => void this.submit());
    this.handoffButton.addEventListener('click', () => void this.requestHuman());
    this.input.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        void this.submit();
      }
    });
  }

  private async toggle(): Promise<void> {
    this.open = !this.open;
    this.panel.setAttribute('data-open', String(this.open));
    if (this.open && !this.started) {
      this.started = true;
      await this.start();
    }
  }

  private async start(): Promise<void> {
    try {
      const session: CsSession = await this.client.open(this.options.identity);
      if (session.themeColor) this.retheme(session.themeColor);
      if (session.handoffEnabled) this.handoffButton.hidden = false;
      if (session.welcomeMessage) this.append('agent', session.welcomeMessage);
      this.listen();
    } catch (error) {
      // Two audiences, two messages. The visitor is a customer of someone else's shop and must not
      // be shown "origin_not_allowed"; they get a sentence. The site owner is the one who can
      // actually fix it, and they are looking at the console, so the raw reason goes there.
      const reason = error instanceof Error ? error.message : 'unknown';
      console.error(`[AuraCS] could not start chat: ${reason}`);
      this.append('system', humanize(reason));
      this.started = false;
    }
  }

  /**
   * Ask for a person.
   *
   * <p>Independent of the AI on purpose: a visitor who has given up on the bot should not have to
   * talk the bot into letting them go.
   */
  private async requestHuman(): Promise<void> {
    this.handoffButton.disabled = true;
    try {
      const result = await this.client.escalate();
      this.append('system', result.seatsAvailable > 0
        ? this.text.handoffQueued
        : this.text.handoffQueuedNoSeats);
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'unknown';
      console.error(`[AuraCS] handoff failed: ${reason}`);
      this.append('system', humanize(reason));
      this.handoffButton.disabled = false;
    }
  }

  /**
   * Hear what we did not ask for.
   *
   * <p>Everything in M1 arrived on the response to a question. A human answers on a request of their
   * own, minutes later, and this stream is the only reason the visitor ever sees it.
   */
  private listen(): void {
    this.stopListening?.();
    this.stopListening = this.client.listen({
      onMessage: (message) => {
        // Our own message came back to us on the stream we are holding. We already drew it when the
        // visitor pressed enter; drawing it again would show it twice.
        if (message.senderType === 'visitor') return;
        // Likewise the AI's answer, which the visitor watched stream into its own bubble.
        if (message.senderType === 'agent' && !this.handedOver) return;

        // A seat's reply and the AI's answer both render on the company side of the panel. The
        // visitor does not need a different bubble to know a person is now typing — the system line
        // already told them, and two shades of the same side is noise.
        this.append('agent', message.content);
        this.scrollToEnd();
      },
      onState: (state) => {
        if (state.state === 'human_active') {
          this.handedOver = true;
          this.handoffButton.hidden = true;
          this.append('system', this.text.handoffTaken);
        } else if (state.state === 'closed') {
          this.append('system', this.text.closed);
        }
        this.scrollToEnd();
      },
    });
  }

  /** Re-render with the site's accent colour once the server has told us what it is. */
  private retheme(accent: string): void {
    const existing = this.messages ? [...this.messages.children].map((el) => ({
      from: (el as HTMLElement).dataset.from ?? 'agent',
      text: el.textContent ?? '',
    })) : [];
    this.render(/^#[0-9a-fA-F]{3,8}$/.test(accent) ? accent : DEFAULT_ACCENT);
    this.panel.setAttribute('data-open', String(this.open));
    for (const message of existing) this.append(message.from, message.text);
  }

  private async submit(): Promise<void> {
    const text = this.input.value.trim();
    if (!text || this.busy) return;

    this.input.value = '';
    this.append('visitor', text);
    this.setBusy(true);

    const bubble = this.append('agent', '');
    let streamed = '';

    await this.client.send(text, {
      onChunk: (chunk) => {
        streamed += chunk;
        this.setText(bubble, streamed);
        this.scrollToEnd();
      },
      onDone: (full) => {
        // The final frame carries the whole answer. Prefer it over the accumulated chunks so a
        // dropped chunk cannot leave a silently truncated reply on screen.
        this.setText(bubble, full || streamed);
        this.setBusy(false);
        this.scrollToEnd();
      },
      onError: (message) => {
        console.error(`[AuraCS] message failed: ${message}`);
        bubble.remove();
        this.append('system', humanize(message));
        this.setBusy(false);
      },
    });
  }

  private setBusy(busy: boolean): void {
    this.busy = busy;
    this.sendButton.disabled = busy;
  }

  private append(from: string, text: string): HTMLElement {
    const element = document.createElement('div');
    element.className = 'msg';
    element.dataset.from = from;
    element.setAttribute('data-testid', `cs-msg-${from}`);
    this.setText(element, text);
    this.messages.appendChild(element);
    this.scrollToEnd();
    return element;
  }

  /**
   * Models answer in markdown, so a bubble that renders plain text shows a visitor literal
   * asterisks around the very words the model was trying to emphasise. Only **bold** is rendered,
   * and the text is HTML-escaped first — this content comes from a language model quoting a
   * knowledge base, which is exactly the kind of input you do not hand to innerHTML unescaped.
   */
  private setText(element: HTMLElement, text: string): void {
    const escaped = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
    element.innerHTML = escaped.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  }

  private scrollToEnd(): void {
    this.messages.scrollTop = this.messages.scrollHeight;
  }
}

/**
 * Turn a server reason code into something a shopper can read. Anything unmapped falls back to a
 * neutral sentence rather than leaking an internal code onto a customer's website.
 */
/**
 * Escape a string that is about to be interpolated into the panel's markup.
 *
 * The strings come from the host site's own configuration, so this is not defending us from them —
 * it is defending them from themselves. A shop that puts an apostrophe in its button label should get
 * an apostrophe, and one that accidentally puts a `<` in should not get a broken panel.
 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function humanize(reason: string): string {
  const messages: Record<string, string> = {
    origin_not_allowed: 'Chat is not enabled for this website yet.',
    site_key_invalid: 'Chat is not available right now.',
    identity_hash_required: 'Chat is not available right now.',
    identity_hash_invalid: 'We could not verify your sign-in. Please reload the page.',
    rate_limited: 'Too many messages just now — please try again in a moment.',
    session_closed: 'This conversation has ended. Reload the page to start a new one.',
    no_response_from_assistant: 'The assistant did not respond. Please try again.',
  };
  return messages[reason] ?? 'Sorry, something went wrong. Please try again.';
}

/** Storage throws in some embedded/privacy contexts; a widget that cannot remember still works. */
function safeLocalStorage(): Storage | null {
  try {
    const probe = '__aura_cs__';
    window.localStorage.setItem(probe, '1');
    window.localStorage.removeItem(probe);
    return window.localStorage;
  } catch {
    return null;
  }
}
