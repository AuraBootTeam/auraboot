import type { ApiResponse } from '../client/api-client.js';
import type { ToolResult } from './registry.js';

/**
 * Standard kinds the MCP server uses when surfacing a backend failure to
 * the LLM. Each kind picks the LLM's likely next action:
 *
 *   - `session_expired` → ask the user to re-login
 *   - `permission_denied` → ask the user / admin to grant the missing role
 *   - `not_found` → re-discover via query_existing_models / query_page_schemas
 *   - `conflict` → rename the resource and retry
 *   - `validation` → fix the violated field and retry
 *   - `backend_error` → unknown 4xx; surface verbatim
 *   - `server_error` → 5xx; surface verbatim, do not retry rapidly
 */
export type ErrorKind =
  | 'session_expired'
  | 'permission_denied'
  | 'not_found'
  | 'conflict'
  | 'validation'
  | 'backend_error'
  | 'server_error';

export interface ClassifiedError {
  kind: ErrorKind;
  status: number;
  message: string;
  /** Concrete next-action hint surfaced to the LLM. */
  suggestion?: string;
  /** When the backend returned structured error context, expose it verbatim. */
  context?: unknown;
}

const CONFLICT_PATTERNS: RegExp[] = [
  /已存在/, // Chinese: "already exists"
  /\balready exists\b/i,
  /\bduplicate\b/i,
];

/**
 * Classify a failed `ApiResponse` into one of the standard `ErrorKind`s.
 *
 * Centralizes the logic that used to live ad-hoc in createModel,
 * createPageSchema, and createCommand — keep this as the single source
 * of truth for "what does this backend status really mean to an LLM?".
 *
 * The caller is expected to have already checked `resp.ok === false`.
 */
export function classifyBackendError(resp: ApiResponse<unknown>): ClassifiedError {
  const message = resp.message ?? `Status ${resp.status}`;

  if (resp.status === 401) {
    return {
      kind: 'session_expired',
      status: 401,
      message,
      suggestion: "Session expired. Run 'aura login' and restart the MCP server.",
    };
  }

  if (resp.status === 403) {
    return {
      kind: 'permission_denied',
      status: 403,
      message,
      suggestion: `Permission denied: ${message}. Ask an admin to grant the required role.`,
    };
  }

  if (resp.status === 404) {
    return {
      kind: 'not_found',
      status: 404,
      message,
      suggestion:
        'Resource not found. Use the matching query_* tool to re-discover the correct identifier.',
    };
  }

  if (resp.status === 409 || CONFLICT_PATTERNS.some((p) => p.test(message))) {
    return {
      kind: 'conflict',
      status: resp.status,
      message,
      suggestion: 'A resource with that code already exists. Rename and retry.',
    };
  }

  if (resp.status === 422) {
    return {
      kind: 'validation',
      status: 422,
      message,
      suggestion:
        'Backend validation rejected the input. Fix the violated field(s) and retry — the message names the offending property.',
    };
  }

  if (resp.status >= 500) {
    return {
      kind: 'server_error',
      status: resp.status,
      message,
      suggestion:
        'Backend returned 5xx. Do not retry rapidly; surface the failure to the user and check server logs.',
    };
  }

  return {
    kind: 'backend_error',
    status: resp.status,
    message,
  };
}

/**
 * Convenience wrapper that converts a classified error into the standard
 * MCP `ToolResult` shape with `isError: true`. Tools call this to surface
 * a non-ok response to the LLM with a uniform JSON envelope:
 *
 *   { kind, status, error, suggestion?, step?, context? }
 *
 * `step` is optional — useful for multi-step tools (createCommand) so the
 * LLM knows which step failed.
 */
export function toolErrorFromBackend(
  resp: ApiResponse<unknown>,
  extra: { step?: string } = {},
): ToolResult {
  const classified = classifyBackendError(resp);
  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify(
          {
            ...(extra.step ? { step: extra.step } : {}),
            kind: classified.kind,
            status: classified.status,
            error: classified.message,
            ...(classified.suggestion ? { suggestion: classified.suggestion } : {}),
            ...(classified.context !== undefined ? { context: classified.context } : {}),
          },
          null,
          2,
        ),
      },
    ],
    isError: true,
  };
}
