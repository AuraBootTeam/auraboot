/**
 * Designer Router
 *
 * Routes to the appropriate designer based on page kind.
 * - list/form -> AreasDesigner (understands filters/toolbar/main)
 * - detail/home -> FloorsDesigner (understands floors[])
 */

import React from 'react';
import type { DslV4Schema, PageKind } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { AreasDesigner } from './AreasDesigner';
import { FloorsDesigner } from './FloorsDesigner';
import { CanvasEditor } from './canvas/CanvasEditor';

export interface DesignerRouterProps {
  /**
   * The DSL V4 schema to edit
   */
  dsl: DslV4Schema;

  /**
   * Callback when DSL is modified
   */
  onDslChange: (dsl: DslV4Schema) => void;

  /**
   * Callback to save DSL
   */
  onSave?: (dsl: DslV4Schema) => Promise<void>;

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
 * Get designer name for a page kind
 */
function getDesignerName(kind: PageKind): string {
  switch (kind) {
    case 'list':
    case 'form':
      return 'AreasDesigner';
    case 'detail':
    case 'home':
      return 'FloorsDesigner';
    case 'composite':
      return 'CanvasEditor';
    default:
      return 'AreasDesigner';
  }
}

/**
 * Main designer router component
 */
export const DesignerRouter: React.FC<DesignerRouterProps> = ({
  dsl,
  onDslChange,
  onSave,
  modelCode,
  readonly = false,
  previewMode = false,
  isCustomApiMode,
  deviceWidth,
}) => {
  const designerName = getDesignerName(dsl.kind);

  // Route based on page kind
  switch (dsl.kind) {
    case 'list':
    case 'form':
      return (
        <AreasDesigner
          dsl={dsl}
          onDslChange={onDslChange}
          onSave={onSave}
          modelCode={modelCode || dsl.modelCode}
          readonly={readonly}
          previewMode={previewMode}
          isCustomApiMode={isCustomApiMode}
        />
      );

    case 'detail':
    case 'home':
      return (
        <FloorsDesigner
          dsl={dsl}
          onDslChange={onDslChange}
          onSave={onSave}
          modelCode={modelCode || dsl.modelCode}
          readonly={readonly}
          previewMode={previewMode}
        />
      );

    case 'composite':
      return (
        <CanvasEditor
          dsl={dsl}
          onDslChange={onDslChange}
          onSave={onSave}
          modelCode={modelCode || dsl.modelCode}
          readonly={readonly}
          previewMode={previewMode}
          deviceWidth={deviceWidth}
        />
      );

    default:
      // Fallback to AreasDesigner
      return (
        <AreasDesigner
          dsl={dsl}
          onDslChange={onDslChange}
          onSave={onSave}
          modelCode={modelCode || dsl.modelCode}
          readonly={readonly}
          previewMode={previewMode}
          isCustomApiMode={isCustomApiMode}
        />
      );
  }
};

/**
 * Placeholder for unimplemented designers
 */
interface PlaceholderDesignerProps {
  designerName: string;
  kind: PageKind;
  message: string;
}

const PlaceholderDesigner: React.FC<PlaceholderDesignerProps> = ({
  designerName,
  kind,
  message,
}) => {
  return (
    <div className="flex flex-1 items-center justify-center bg-gray-50">
      <div className="max-w-md p-8 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-gray-100">
          <svg
            className="h-8 w-8 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
            />
          </svg>
        </div>
        <h3 className="mb-2 text-lg font-medium text-gray-900">{designerName}</h3>
        <p className="mb-4 text-sm text-gray-500">{message}</p>
        <div className="inline-flex items-center rounded-full bg-blue-50 px-3 py-1 text-sm text-blue-700">
          <span className="mr-2">Page Kind:</span>
          <code className="font-mono">{kind}</code>
        </div>
        <p className="mt-4 text-xs text-gray-400">This designer is under development</p>
      </div>
    </div>
  );
};

export default DesignerRouter;
