/**
 * Preview Panel Types
 *
 * Types for page preview functionality.
 *
 * @since 3.2.0
 */

/**
 * Preview mode
 */
export type PreviewMode = 'split' | 'fullscreen' | 'panel';

/**
 * Preview state
 */
export interface PreviewState {
  /** Whether preview is active */
  isActive: boolean;
  /** Current preview mode */
  mode: PreviewMode;
  /** Mock data for preview */
  mockData: Record<string, unknown>;
  /** Whether to show component outlines */
  showOutlines: boolean;
  /** Whether to show data bindings */
  showBindings: boolean;
  /** Whether to enable interactions */
  enableInteractions: boolean;
  /** Current device ID */
  deviceId: string;
  /** Zoom level */
  zoom: number;
}

/**
 * Mock data configuration
 */
export interface MockDataConfig {
  /** Field path */
  path: string;
  /** Mock value */
  value: unknown;
  /** Value type */
  type: 'static' | 'random' | 'sequence';
  /** Random generator config */
  random?: {
    min?: number;
    max?: number;
    pattern?: string;
    options?: unknown[];
  };
}

/**
 * Preview action
 */
export interface PreviewAction {
  /** Action type */
  type: 'submit' | 'navigate' | 'api' | 'custom';
  /** Action config */
  config: Record<string, unknown>;
  /** Mock response */
  mockResponse?: unknown;
  /** Delay in ms */
  delay?: number;
}

/**
 * Preview event log
 */
export interface PreviewEventLog {
  /** Event ID */
  id: string;
  /** Timestamp */
  timestamp: number;
  /** Event type */
  type: 'action' | 'binding' | 'validation' | 'navigation';
  /** Event name */
  name: string;
  /** Event data */
  data: unknown;
  /** Component ID */
  componentId?: string;
}
