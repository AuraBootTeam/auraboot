/**
 * SkillClient — REST + SSE wrapper over the AuraBot Skill SPI
 * (see docs/superpowers/specs/2026-05-08-aurabot-skill-spi-contract.md §2).
 *
 * This module owns the wire format and HTTP plumbing only. Field names
 * mirror SPI §3/§4 verbatim — any rename here is a contract violation.
 *
 * For local dev before C-2 ships the EchoSkill backend, a mock transport
 * lives at `./skillClient.mock.ts`. Mock activation is gated by
 * `import.meta.env.VITE_AURABOT_USE_MOCK === 'true'` so the import is
 * tree-shaken out of production builds.
 */

import type { SkillMeta, SkillRequest, SkillResult } from '../types/skill';

export type SseEventName =
  | 'thinking'
  | 'wizard-progress'
  | 'partial-result'
  | 'done'
  | 'error';

export interface SseHandlers {
  onThinking?: (data: { text: string; tokens?: number }) => void;
  onWizardProgress?: (data: { step: number; total: number; label: string }) => void;
  onPartialResult?: (data: { payload: Record<string, unknown> }) => void;
  onDone?: (data: SkillResult) => void;
  onError?: (data: { errors: Array<{ code: string; message?: string }> }) => void;
}

export interface SkillClient {
  list(): Promise<SkillMeta[]>;
  dryRun(req: SkillRequest): Promise<SkillResult>;
  execute(req: SkillRequest): Promise<SkillResult>;
  undo(undoToken: string): Promise<SkillResult>;
  batchUndo(batchId: string): Promise<SkillResult>;
  /** Returns a disposer that closes the underlying EventSource. */
  attachStream(traceId: string, handlers: SseHandlers): () => void;
}

const BASE_PATH = '/api/aurabot/v2';

/**
 * Platform `ApiResponse<T>` envelope shape (per SPI Contract §2.1).
 * Every backend response is wrapped — FE clients MUST unwrap `.data`.
 */
interface ApiResponse<T> {
  code: string;
  message?: string;
  data: T;
  context?: Record<string, unknown> | null;
  timestamp?: number;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${BASE_PATH}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`SkillClient ${path} failed: ${response.status} ${text}`);
  }
  const envelope = (await response.json()) as ApiResponse<T>;
  return envelope.data;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE_PATH}${path}`, { credentials: 'include' });
  if (!response.ok) {
    throw new Error(`SkillClient ${path} failed: ${response.status}`);
  }
  const envelope = (await response.json()) as ApiResponse<T>;
  return envelope.data;
}

/** Default real-backend implementation. */
export const httpSkillClient: SkillClient = {
  list: () => getJson<SkillMeta[]>('/skills'),
  dryRun: (req) => postJson<SkillResult>('/skill/dry-run', req),
  execute: (req) => postJson<SkillResult>('/skill/execute', req),
  undo: (undoToken) => postJson<SkillResult>('/skill/undo', { undoToken }),
  batchUndo: (batchId) => postJson<SkillResult>('/skill/batch-undo', { batchId }),
  attachStream(traceId, handlers) {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      return () => {
        /* no-op on server */
      };
    }
    const url = `${BASE_PATH}/stream/${encodeURIComponent(traceId)}`;
    const source = new EventSource(url, { withCredentials: true });

    const subscribe = <K extends SseEventName>(name: K, handler?: (data: any) => void) => {
      if (!handler) return;
      source.addEventListener(name, (event: MessageEvent) => {
        try {
          handler(JSON.parse(event.data));
        } catch {
          // Drop malformed payloads — backend should never emit them.
        }
      });
    };

    subscribe('thinking', handlers.onThinking);
    subscribe('wizard-progress', handlers.onWizardProgress);
    subscribe('partial-result', handlers.onPartialResult);
    subscribe('done', handlers.onDone);
    subscribe('error', handlers.onError);

    return () => source.close();
  },
};

/**
 * Returns the active skill client. In development with
 * `VITE_AURABOT_USE_MOCK=true` we lazy-import the mock module so production
 * bundles tree-shake the mock fixture entirely.
 */
export async function resolveSkillClient(): Promise<SkillClient> {
  // Vite swaps `import.meta.env.VITE_AURABOT_USE_MOCK` at build time. In
  // production with `false` (or unset) the if-branch is dead code and the
  // dynamic import is dropped.
  if (import.meta.env.VITE_AURABOT_USE_MOCK === 'true') {
    const mod = await import('./skillClient.mock');
    return mod.mockSkillClient;
  }
  return httpSkillClient;
}
