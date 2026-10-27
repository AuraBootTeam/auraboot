import type { LocalizedText } from './types';

export type SourceStateText = string | LocalizedText;
export type SourceStateTone = 'neutral' | 'success' | 'warning' | 'danger';

/** An explicit business-state codebook, never a raw-code display fallback. */
export interface SourceStateIndicator {
  path: string;
  label: SourceStateText;
  values: Record<string, { label: SourceStateText; tone?: SourceStateTone; hideValue?: boolean }>;
}
export interface SourceStateField {
  path: string;
  label: SourceStateText;
  format?: 'text' | 'datetime';
  valueLabels?: Record<string, SourceStateText>;
  /** Explicitly allow an omitted/null field; it remains visibly unavailable. */
  optional?: boolean;
}
/** Opt-in view over one existing named DMS source. No second transport. */
export interface AuthoritativeSourceConfig {
  mode: 'authoritative';
  identity: ReadonlyArray<{ responsePath: string; contextPath: string }>;
  /** Scope-only inputs, such as a requested range which is not echoed by the API. */
  scopePaths?: readonly string[];
  valuePath?: string;
  valueLabels?: Record<string, SourceStateText>;
  unitPath?: string;
  precision?: number;
  metadata?: readonly SourceStateField[];
  states?: readonly SourceStateIndicator[];
  series?: {
    pointsPath: string;
    timePath: string;
    valuePath: string;
    kind: 'line' | 'scatter';
    identity?: ReadonlyArray<{ pointPath: string; responsePath: string }>;
    gapWhen?: ReadonlyArray<{
      path: string;
      values: readonly (string | number | boolean)[];
      /** Unknown quality/state codes cannot silently become valid measurements. */
      knownValues: readonly (string | number | boolean)[];
    }>;
    domain?: readonly [number, number];
    color?: string;
    height?: number;
  };
}
