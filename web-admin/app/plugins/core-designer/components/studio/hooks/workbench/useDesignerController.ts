import { useState, useEffect, useCallback } from 'react';
import type { DragStartEvent, DragEndEvent } from '@dnd-kit/core';
import type { FormSchema, Component, Position } from '~/plugins/core-designer/components/studio/domain/schema/types';
import { useCanvasEditorState, getDesignerSDK } from '~/plugins/core-designer/components/studio/sdk';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import { eventDomainManager } from '~/plugins/core-designer/components/studio/services/actions/event/EventDomainManager';
import { globalShortcutManager } from '~/plugins/core-designer/components/studio/services/actions/event/GlobalShortcutManager';
import type { DesignerWorkflowProps } from '~/plugins/core-designer/components/studio/workbench/components/DesignerWorkflow';
import { createDefaultSchema } from '~/plugins/core-designer/components/studio/workbench/utils/schemaUtils';

const designerSDK = getDesignerSDK();
const { componentRegistry, pageStateManager: stateManager } = designerSDK;

export interface DesignerControllerOptions {
  pageId: string;
  initialSchema?: FormSchema;
  previewMode?: boolean;
  readonly?: boolean;
  collaborationConfig?: DesignerWorkflowProps['collaborationConfig'];
  onSchemaChange?: (schema: FormSchema) => void;
  onSave?: (schema: FormSchema) => Promise<void>;
  onPublish?: (schema: FormSchema) => Promise<void>;
}

export interface DraggedComponentPreview {
  type: string;
  name: string;
  icon: string;
  /** Whether this is a field being dragged */
  isField?: boolean;
  /** Original field code if dragging from field library */
  fieldCode?: string;
}

export interface DesignerControllerResult {
  loading: boolean;
  error: string | null;
  schema: FormSchema;
  activeDragId: string | null;
  draggedComponent: DraggedComponentPreview | null;
  handleSchemaChange: (schema: FormSchema) => void;
  handleDragStart: (event: DragStartEvent) => void;
  handleDragEnd: (event: DragEndEvent) => void;
  saveSchema: () => Promise<void>;
  publishSchema: () => Promise<void>;
}

export const DEFAULT_DESIGNER_SCHEMA: FormSchema = createDefaultSchema();

export function useDesignerController(
  options: DesignerControllerOptions,
): DesignerControllerResult {
  const {
    pageId,
    initialSchema = DEFAULT_DESIGNER_SCHEMA,
    onSchemaChange,
    onSave,
    onPublish,
  } = options;

  const [currentSchema, setCurrentSchema] = useState<FormSchema>(initialSchema);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [draggedComponent, setDraggedComponent] = useState<DraggedComponentPreview | null>(null);

  const { selectComponent } = useCanvasEditorState();

  // 初始化设计器状态
  useEffect(() => {
    setCurrentSchema(initialSchema);
  }, [initialSchema]);

  useEffect(() => {
    const initializeDesigner = async () => {
      try {
        setLoading(true);
        setError(null);

        await stateManager.initialize({
          id: pageId,
          name: currentSchema.title || '未命名页面',
          schema: {
            version: currentSchema.version,
            components: currentSchema.components || [],
            layout: currentSchema.layout,
          },
        });
      } catch (err) {
        console.error('Designer initialization failed:', err);
        setError(err instanceof Error ? err.message : '初始化失败');
      } finally {
        setLoading(false);
      }
    };

    initializeDesigner();
  }, [pageId, currentSchema]);

  useEffect(() => {
    globalShortcutManager.initialize();
    eventDomainManager.initialize();

    return () => {
      globalShortcutManager.destroy();
      eventDomainManager.destroy();
    };
  }, []);

  const handleSchemaChange = useCallback(
    (schema: FormSchema) => {
      setCurrentSchema(schema);
      stateManager.setState({ schema }, 'handleSchemaChange');
      onSchemaChange?.(schema);
    },
    [onSchemaChange],
  );

  const saveSchema = useCallback(async () => {
    if (onSave) {
      await onSave(currentSchema);
    }
  }, [currentSchema, onSave]);

  const publishSchema = useCallback(async () => {
    if (onPublish) {
      await onPublish(currentSchema);
    }
  }, [currentSchema, onPublish]);

  const handleDragStart = useCallback((event: DragStartEvent) => {
    const { active } = event;
    const activeIdStr = String(active.id);
    setActiveId(activeIdStr);

    if (active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
      // Check if component config is already provided (from field library)
      const providedConfig = active.data.current?.component;
      const isField = activeIdStr.startsWith('field-');

      if (providedConfig) {
        // Extract field code from props if available
        const fieldCode = providedConfig.props?._fieldMeta?.code || providedConfig.props?.name;
        setDraggedComponent({
          type: providedConfig.type,
          name: providedConfig.name,
          icon: providedConfig.icon || '📋',
          isField,
          fieldCode,
        });
        return;
      }

      // Fall back to registry lookup (from component palette)
      const componentType = activeIdStr.replace('palette-', '');
      const componentConfig = componentRegistry.getComponent(componentType);

      if (componentConfig) {
        setDraggedComponent({
          type: componentConfig.type,
          name: componentConfig.name,
          icon: componentConfig.icon,
          isField: false,
        });
      }
    }
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over, delta } = event;

      if (!over) {
        setActiveId(null);
        setDraggedComponent(null);
        return;
      }

      const isDrag = Math.abs(delta.x) > 5 || Math.abs(delta.y) > 5;
      if (!isDrag && active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
        setActiveId(null);
        setDraggedComponent(null);
        return;
      }

      if (
        active.data.current?.type === DRAG_TYPES.COMPONENT &&
        over.data.current?.type === DRAG_TYPES.GRID_CELL
      ) {
        const dragged = active.data.current.component as Component;
        const targetPosition = over.data.current.position as Position;

        const targetComponent = (currentSchema.components || []).find(
          (comp: Component) =>
            comp.position?.row === targetPosition.row &&
            comp.position?.column === targetPosition.column,
        );

        if (targetComponent && targetComponent.id !== dragged.id) {
          // Swap positions immutably
          const updatedComponents = (currentSchema.components || []).map((comp: Component) => {
            if (comp.id === dragged.id) {
              return { ...comp, position: targetComponent.position };
            }
            if (comp.id === targetComponent.id) {
              return { ...comp, position: dragged.position };
            }
            return comp;
          });

          handleSchemaChange({ ...currentSchema, components: updatedComponents });
        } else {
          // Move to target position
          const updatedComponents = (currentSchema.components || []).map((comp: Component) => {
            if (comp.id === dragged.id) {
              return { ...comp, position: targetPosition };
            }
            return comp;
          });

          handleSchemaChange({ ...currentSchema, components: updatedComponents });
        }
      }

      if (
        active.data.current?.type === DRAG_TYPES.PALETTE_ITEM &&
        over.data.current?.type === DRAG_TYPES.GRID_CELL
      ) {
        const { row, column } = over.data.current.position;

        // Priority 1: Use pre-computed component config from drag data (field library)
        let componentConfig = active.data.current?.component;

        // Priority 2: Fall back to registry lookup (component palette)
        if (!componentConfig) {
          const activeIdStr = String(active.id);
          const componentType = activeIdStr.replace('palette-', '');
          const registryConfig = componentRegistry.getComponent(componentType);

          if (registryConfig) {
            componentConfig = {
              type: registryConfig.type,
              name: registryConfig.name,
              props: registryConfig.defaultProps || {},
              span: registryConfig.defaultProps?.width || 1,
            };
          }
        }

        if (!componentConfig) {
          console.warn(`Component config not found for drag item: ${active.id}`);
          setActiveId(null);
          setDraggedComponent(null);
          return;
        }

        const componentWidth = componentConfig.span || componentConfig.props?.width || 1;
        let adjustedColumn = column;

        if (componentWidth === 12) {
          adjustedColumn = 0;
        } else {
          const maxColumn = 12 - componentWidth;
          if (column > maxColumn) {
            adjustedColumn = maxColumn;
          }
          if (adjustedColumn + componentWidth > 12) {
            adjustedColumn = Math.max(0, 12 - componentWidth);
          }
        }

        const newComponent: Component = {
          id: `comp_${Date.now()}`,
          type: componentConfig.type,
          name: componentConfig.name,
          props: componentConfig.props || {},
          position: { row, column: adjustedColumn },
          size: {
            width: componentWidth,
            height: componentConfig.props?.height || 1,
            span: componentWidth,
          },
          span: componentWidth,
          children: [],
        };

        selectComponent(newComponent.id);

        handleSchemaChange({
          ...currentSchema,
          components: [...(currentSchema.components || []), newComponent],
        });
      }

      setActiveId(null);
      setDraggedComponent(null);
    },
    [currentSchema, selectComponent, handleSchemaChange],
  );

  return {
    loading,
    error,
    schema: currentSchema,
    activeDragId: activeId,
    draggedComponent,
    handleSchemaChange,
    handleDragStart,
    handleDragEnd,
    saveSchema,
    publishSchema,
  };
}
