/**
 * Designer Router
 *
 * Routes to the appropriate designer based on page kind.
 * - list   -> ListConfigPanel (P2B stub, structured config in P3)
 * - detail -> DetailConfigPanel (P2B stub, structured config in P3)
 * - form   -> BlocksDesigner (V2 flat schema.blocks, unchanged)
 *
 * PageKind is narrowed to 'list' | 'form' | 'detail' (Task 3.1).
 * The default case is an exhaustive never-check that will surface at compile
 * time if a new kind is added without updating this router.
 *
 */

import React from 'react';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { BlocksDesigner } from './BlocksDesigner';
import { ListConfigPanel } from './ListConfigPanel';
import { DetailConfigPanel } from './DetailConfigPanel';

export interface DesignerRouterProps {
  /**
   * The V2 PageSchema to edit
   */
  schema: PageSchema;

  /**
   * Callback when schema is modified
   */
  onSchemaChange: (schema: PageSchema) => void;

  /**
   * Callback to save schema
   */
  onSave?: (schema: PageSchema) => Promise<void>;

  /**
   * Model code for field lookup
   */
  modelCode?: string;

  /**
   * Read-only mode
   */
  readonly?: boolean;

  /**
   * Preview mode
   */
  previewMode?: boolean;

  /**
   * Whether the page uses a custom API data source instead of a model
   */
  isCustomApiMode?: boolean;

  /**
   * Device preview width in pixels (null = default 980px)
   */
  deviceWidth?: number | null;
}

/**
 * Main designer router component
 */
export function DesignerRouter({
  schema,
  onSchemaChange,
  onSave,
  modelCode,
  readonly = false,
  previewMode = false,
  isCustomApiMode,
  deviceWidth,
}: DesignerRouterProps) {
  switch (schema.kind) {
    case 'list':
      return (
        <ListConfigPanel
          schema={schema}
          onSchemaChange={onSchemaChange}
          onSave={onSave}
          modelCode={modelCode || schema.modelCode}
          readonly={readonly}
          previewMode={previewMode}
        />
      );

    case 'detail':
      return (
        <DetailConfigPanel
          schema={schema}
          onSchemaChange={onSchemaChange}
          onSave={onSave}
          modelCode={modelCode || schema.modelCode}
          readonly={readonly}
          previewMode={previewMode}
        />
      );

    case 'form':
      return (
        <BlocksDesigner
          schema={schema}
          onSchemaChange={onSchemaChange}
          onSave={onSave}
          modelCode={modelCode || schema.modelCode}
          readonly={readonly}
          previewMode={previewMode}
          isCustomApiMode={isCustomApiMode}
        />
      );

    default: {
      // Exhaustive check — PageKind is narrowed to list | form | detail.
      // TypeScript will flag this if a new kind is added without handling it here.
      const _never: never = schema.kind;
      throw new Error(`DesignerRouter: unsupported kind '${_never}'`);
    }
  }
}

export default DesignerRouter;
