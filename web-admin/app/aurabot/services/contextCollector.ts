/**
 * ContextCollector — assembles `AgentContext` for the SkillRequest.
 *
 * SPI §3 freezes the field names — keep them in sync verbatim. We accept the
 * route as input rather than calling `useLocation()` here so this stays a
 * pure function (callable from outside React, easy to unit-test).
 */

import type { AgentContext } from '../types/skill';

export interface ContextCollectorInput {
  /** Current pathname (e.g. from useLocation().pathname). */
  route: string;
  /** Model code if a /p/:modelCode route is active. */
  modelCode?: string | null;
  /** Page id if the current route resolves to a page schema. */
  pageId?: string | null;
  selectedElement?: unknown;
  recentOperations?: unknown[];
  lastCreatedResources?: unknown[];
}

export function collectContext(input: ContextCollectorInput): AgentContext {
  return {
    route: input.route,
    modelCode: input.modelCode ?? null,
    pageId: input.pageId ?? null,
    selectedElement: input.selectedElement ?? null,
    recentOperations: input.recentOperations ?? [],
    lastCreatedResources: input.lastCreatedResources ?? [],
  };
}

/**
 * Best-effort extraction of `modelCode` from `/p/:modelCode` style routes.
 * Returns null when the route does not match.
 */
export function inferModelCodeFromRoute(route: string): string | null {
  const match = route.match(/^\/p\/([^/?#]+)/);
  return match ? match[1] : null;
}
