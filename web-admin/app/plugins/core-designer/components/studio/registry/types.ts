/**
 * Widget & Block Registry — Core Types
 *
 * WidgetDefinition: a form field component (text, select, date, reference, ...)
 * BlockDefinition:  a page layout block (table, form-section, chart, toolbar, ...)
 *
 * @since 4.3.0
 * @spec docs/plans/2026-03/2026-04-06-widget-block-registry-design.md
 */

import type { PropertySchema } from '~/shared/designer/types';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';

// ──────────────────────────────────────────────────────────────────────────────
// Widget (form field component)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Definition of a single form-field widget type (e.g. "select", "date").
 *
 * `schema` contains only the properties *specific* to this widget.
 * Common properties (field, label, required, readOnly, colSpan, conditions)
 * are defined in `COMMON_FIELD_SCHEMA` and merged at render time.
 */
export interface WidgetDefinition {
  /** Unique component type key, e.g. "select", "date", "reference" */
  component: string;
  /** Human-readable name shown in the component picker */
  name: string;
  /** Single emoji or icon character shown in the palette */
  icon: string;
  /** Palette category, e.g. "input", "selection", "advanced" */
  category: string;
  /** Optional short description shown in the palette tooltip */
  description?: string;
  /** Widget-specific PropertySchema entries (NOT including common fields) */
  schema: PropertySchema<string>[];
}

// ──────────────────────────────────────────────────────────────────────────────
// Block (page layout block)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Definition of a page layout block type (e.g. "table", "form-section").
 *
 * `preview` is an optional React component rendered in the canvas body
 * for rich drag-and-drop previews (replaces the switch/case in CanvasBody).
 */
export interface BlockDefinition {
  /** Unique block type key, e.g. "table", "form-section", "stat-card" */
  type: string;
  /** Human-readable name shown in the block palette */
  name: string;
  /** Single emoji or icon character */
  icon: string;
  /** Short description shown in the palette tooltip */
  description: string;
  /** Semantic category for palette grouping */
  category: 'data' | 'layout' | 'form' | 'display';
  /** Default column span when dropped onto the canvas (1-12) */
  defaultColSpan: number;
  /** Block-level PropertySchema for the right-panel config editor */
  schema: PropertySchema<string>[];
  /** Optional rich preview rendered in the canvas drag area */
  preview?: React.FC<{
    block: CanvasBlock;
    selectedFieldIndex?: number | null;
    onSelectField?: (index: number | null) => void;
  }>;
}
