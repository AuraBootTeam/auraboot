/**
 * 设计器主组件
 *
 * 集成所有设计器功能的主入口组件
 */

import React from 'react';
import { DndContext, DragOverlay, closestCenter } from '@dnd-kit/core';
import { DesignerWorkflow } from '~/studio/workbench/components/DesignerWorkflow';
import { DragPreview } from '~/studio/workbench/canvas/drag/DragPreview';
import { DesignerWorkbench } from '~/studio/workbench/DesignerWorkbench';
import {
  useDesignerController,
  type DesignerControllerOptions,
} from '~/studio/hooks/workbench/useDesignerController';
import type { PageSchema } from '~/studio/domain/schema/types';

/**
 * 设计器属性
 */
export type DesignerProps = DesignerControllerOptions;

/**
 * 默认Schema
 */
const DEFAULT_SCHEMA: PageSchema = {
  id: 'default',
  kind: 'form',
  title: 'New Page',
  description: 'Page created with AuraBoot Designer',
  version: '1.0.0',
  components: [],
  layout: {
    type: 'grid',
    columns: 12,
    spacing: 16,
    padding: 24,
  },
  theme: {
    primaryColor: '#3B82F6',
    backgroundColor: '#FFFFFF',
    textColor: '#1F2937',
    borderRadius: 8,
  },
  metadata: {
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    createdBy: 'system',
    tags: [],
  },
};

/**
 * 设计器主组件
 */
export const Designer: React.FC<DesignerProps> = (options) => {
  const { previewMode = false, readonly = false, collaborationConfig } = options;

  const {
    loading,
    error,
    schema,
    activeDragId,
    draggedComponent,
    handleSchemaChange,
    handleDragStart,
    handleDragEnd,
    saveSchema,
    publishSchema,
  } = useDesignerController({
    initialSchema: options.initialSchema ?? DEFAULT_SCHEMA,
    ...options,
  });

  // 加载状态
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="text-center">
          <div className="mx-auto mb-4 h-12 w-12 animate-spin rounded-full border-b-2 border-blue-600"></div>
          <p className="text-gray-600">正在初始化设计器...</p>
        </div>
      </div>
    );
  }

  // 错误状态
  if (error) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="max-w-md text-center">
          <div className="mb-4 rounded border border-red-400 bg-red-100 px-4 py-3 text-red-700">
            <strong className="font-bold">初始化失败！</strong>
            <span className="block sm:inline"> {error}</span>
          </div>
          <button
            onClick={() => window.location.reload()}
            className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
          >
            重新加载
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen overflow-hidden bg-gray-50" data-domain="designer">
      <DndContext
        collisionDetection={closestCenter}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        <DesignerWorkflow
          pageId={options.pageId}
          schema={schema}
          onSchemaChange={handleSchemaChange}
          onPublish={publishSchema}
          previewMode={previewMode}
          readonly={readonly}
          collaborationConfig={collaborationConfig}
        >
          <DesignerWorkbench schema={schema} previewMode={previewMode} readonly={readonly} />
        </DesignerWorkflow>

        {/* 拖拽预览 */}
        <DragOverlay>
          {activeDragId && draggedComponent ? (
            <DragPreview
              type={draggedComponent.type}
              name={draggedComponent.name}
              icon={draggedComponent.icon}
              isField={draggedComponent.isField}
              fieldCode={draggedComponent.fieldCode}
            />
          ) : null}
        </DragOverlay>
      </DndContext>
    </div>
  );
};
