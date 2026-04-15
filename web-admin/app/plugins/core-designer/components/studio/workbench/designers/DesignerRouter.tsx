/**
 * Designer Router
 *
 * Routes to the appropriate designer based on page kind.
 * - list/form -> AreasDesigner (understands filters/toolbar/main)
 * - detail    -> FloorsDesigner (understands floors[])
 *
 * PageKind is narrowed to 'list' | 'form' | 'detail' (Task 3.1).
 * home and composite branches have been removed — the default case
 * is an exhaustive never-check that will surface at compile time if
 * new kinds are added without updating this router.
 */

import React from 'react';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { AreasDesigner } from './AreasDesigner';
import { FloorsDesigner } from './FloorsDesigner';

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
    case 'form':
      return (
        // TODO: Task 4.4 — update AreasDesigner props to accept schema/onSchemaChange directly
        <AreasDesigner
          dsl={schema}
          onDslChange={onSchemaChange}
          onSave={onSave}
          modelCode={modelCode || schema.modelCode}
          readonly={readonly}
          previewMode={previewMode}
          isCustomApiMode={isCustomApiMode}
        />
      );

    case 'detail':
      return (
        // TODO: Task 4.5 — update FloorsDesigner props to accept schema/onSchemaChange directly
        <FloorsDesigner
          dsl={schema}
          onDslChange={onSchemaChange}
          onSave={onSave}
          modelCode={modelCode || schema.modelCode}
          readonly={readonly}
          previewMode={previewMode}
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
