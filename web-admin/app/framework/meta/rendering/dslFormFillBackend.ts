/**
 * Injection boundary for the DSL AI form-fill feature.
 *
 * The framework owns the draft-mutation boundary and the extraction safety
 * rules (evidence validation, locked fields, busy/target guards); the actual
 * agent conversation streaming is provided by the AI plugin at boot. The
 * framework must not import plugin internals (2026-11-14 boundary cleanup),
 * so the plugin registers its backend implementation here.
 */
import type { FormFillTarget } from './formFill';

export interface DslFormFillStreamHandlers {
  onChunk: () => void;
  onDone: () => void;
  onError: (error: string) => void;
  onConfirmRequired: () => void;
  onToolResult: (id: string, result: any, success: boolean) => void;
}

export interface DslFormFillExtractRequest {
  message: string;
  formFill: unknown;
  pageContext: Record<string, unknown>;
}

export interface DslFormFillBackend {
  /**
   * Registers an active form-fill target with the agent UI (when mounted).
   * Returns an unregister function, or undefined when no agent surface is
   * present — matching the previous "provider not mounted" behavior.
   */
  registerTarget: (target: FormFillTarget) => (() => void) | undefined;

  /**
   * Streams an extraction request through the agent conversation API.
   * Conversation lifecycle management is the backend's concern.
   */
  extract: (request: DslFormFillExtractRequest, handlers: DslFormFillStreamHandlers) => Promise<void>;
}

let backend: DslFormFillBackend | null = null;

export function setDslFormFillBackend(value: DslFormFillBackend | null) {
  backend = value;
}

export function getDslFormFillBackend(): DslFormFillBackend | null {
  return backend;
}
