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

export interface WidgetOptions {
  apiBase: string;
  siteKey: string;
  identity?: CsIdentity;
}

export class CsWidget {
  private readonly host: HTMLElement;
  private readonly root: ShadowRoot;
  private readonly client: CsClient;

  private panel!: HTMLElement;
  private messages!: HTMLElement;
  private input!: HTMLTextAreaElement;
  private sendButton!: HTMLButtonElement;
  private open = false;
  private busy = false;
  private started = false;

  constructor(private readonly options: WidgetOptions) {
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
        <header class="header" data-testid="cs-header">Chat</header>
        <div class="messages" data-testid="cs-messages"></div>
        <div class="composer">
          <textarea data-testid="cs-input" placeholder="Type a message"></textarea>
          <button class="send" data-testid="cs-send">Send</button>
        </div>
      </section>
    `;

    const launcher = this.root.querySelector<HTMLButtonElement>('.launcher')!;
    this.panel = this.root.querySelector<HTMLElement>('.panel')!;
    this.messages = this.root.querySelector<HTMLElement>('.messages')!;
    this.input = this.root.querySelector<HTMLTextAreaElement>('textarea')!;
    this.sendButton = this.root.querySelector<HTMLButtonElement>('button.send')!;

    launcher.addEventListener('click', () => void this.toggle());
    this.sendButton.addEventListener('click', () => void this.submit());
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
      if (session.welcomeMessage) this.append('agent', session.welcomeMessage);
    } catch (error) {
      // A failure here is nearly always a misconfiguration the site owner can fix — an origin that
      // is not on the allowlist, a bad key, an identity hash that does not verify. Say which.
      this.append('system', error instanceof Error ? error.message : 'could not start chat');
      this.started = false;
    }
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
        bubble.textContent = streamed;
        this.scrollToEnd();
      },
      onDone: (full) => {
        // The final frame carries the whole answer. Prefer it over the accumulated chunks so a
        // dropped chunk cannot leave a silently truncated reply on screen.
        bubble.textContent = full || streamed;
        this.setBusy(false);
        this.scrollToEnd();
      },
      onError: (message) => {
        bubble.remove();
        this.append('system', message);
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
    element.textContent = text;
    this.messages.appendChild(element);
    this.scrollToEnd();
    return element;
  }

  private scrollToEnd(): void {
    this.messages.scrollTop = this.messages.scrollHeight;
  }
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
