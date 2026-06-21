/**
 * Flat camelCase envelope mirroring the server-side BehaviorEventInput DTO.
 * Server source of truth: platform/.../behavior/dto/BehaviorEventInput.java
 */
export interface BehaviorEventInput {
  eventId: string;
  schemaVersion: string;          // "1"
  eventName: string;              // e.g. "page_view" | "element_click"
  eventCategory: string;          // e.g. "navigation" | "ui_interaction"
  source: string;                 // "web"
  occurredAt: string;             // ISO8601
  clientSessionId: string;
  uiElementId?: string;
  appId?: string;
  pageId?: string;
  blockId?: string;
  elementCode?: string;
  identityQuality?: 'stable' | 'heuristic';
  props?: Record<string, unknown>;
}

export interface RawEventInput {
  eventName: string;
  eventCategory: string;
  clientSessionId: string;
  ui?: {
    uiElementId: string;
    appId?: string;
    pageId?: string;
    blockId?: string;
    elementCode?: string;
    identityQuality: 'stable' | 'heuristic';
  };
  props?: Record<string, unknown>;
}
