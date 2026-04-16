/**
 * Hierarchy Layout Management Hook
 *
 * Manages the four-level layout state (TAB → Floor → Block → Field).
 * Provides CRUD operations for each hierarchy level and integrates
 * with the designer store's pageSchema.
 *
 * @since 3.2.0
 */

import { useCallback, useMemo, useState } from 'react';
import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import type {
  TabContainerConfig,
  TabItemConfig,
  FloorConfig,
  BlockConfig,
  FieldCellConfig,
  HierarchySelection,
} from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import {
  DEFAULT_HIERARCHY,
  createDefaultTab,
  createDefaultFloor,
  createDefaultBlock,
  createFieldCell,
} from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';

export interface UseHierarchyLayoutReturn {
  // State
  hierarchy: TabContainerConfig;
  selection: HierarchySelection;
  isHierarchyMode: boolean;

  // Selection
  selectTab: (tabId: string) => void;
  selectFloor: (tabId: string, floorId: string) => void;
  selectBlock: (tabId: string, floorId: string, blockId: string) => void;
  selectField: (tabId: string, floorId: string, blockId: string, fieldId: string) => void;
  clearSelection: () => void;

  // Tab operations
  addTab: (label: string) => void;
  removeTab: (tabId: string) => void;
  updateTab: (tabId: string, updates: Partial<TabItemConfig>) => void;
  moveTab: (tabId: string, direction: 'left' | 'right') => void;

  // Floor operations
  addFloor: (tabId: string, title: string) => void;
  removeFloor: (tabId: string, floorId: string) => void;
  updateFloor: (tabId: string, floorId: string, updates: Partial<FloorConfig>) => void;
  toggleFloorCollapse: (tabId: string, floorId: string) => void;
  moveFloor: (tabId: string, floorId: string, direction: 'up' | 'down') => void;

  // Block operations
  addBlock: (tabId: string, floorId: string) => void;
  removeBlock: (tabId: string, floorId: string, blockId: string) => void;
  updateBlock: (
    tabId: string,
    floorId: string,
    blockId: string,
    updates: Partial<BlockConfig>,
  ) => void;

  // Field operations
  addField: (
    tabId: string,
    floorId: string,
    blockId: string,
    fieldCode: string,
    componentType: string,
    props?: Record<string, any>,
  ) => void;
  removeField: (tabId: string, floorId: string, blockId: string, fieldId: string) => void;
  updateField: (
    tabId: string,
    floorId: string,
    blockId: string,
    fieldId: string,
    updates: Partial<FieldCellConfig>,
  ) => void;
  moveField: (
    tabId: string,
    floorId: string,
    blockId: string,
    fieldId: string,
    direction: 'up' | 'down',
  ) => void;

  // Mode
  enableHierarchyMode: () => void;
  disableHierarchyMode: () => void;
}

export interface UseHierarchyLayoutOptions {
  schema: CanvasSchema;
  onSchemaChange: (next: CanvasSchema) => void;
}

export function useHierarchyLayout({ schema, onSchemaChange }: UseHierarchyLayoutOptions): UseHierarchyLayoutReturn {
  const [selection, setSelection] = useState<HierarchySelection>({});

  const hierarchy = useMemo<TabContainerConfig>(() => {
    return schema?.hierarchy || DEFAULT_HIERARCHY;
  }, [schema?.hierarchy]);

  const isHierarchyMode = !!schema?.hierarchy;

  // Helper to update hierarchy immutably
  const updateHierarchy = useCallback(
    (mutator: (h: TabContainerConfig) => void) => {
      const currentHierarchy: TabContainerConfig = schema?.hierarchy
        ? JSON.parse(JSON.stringify(schema.hierarchy))
        : JSON.parse(JSON.stringify(DEFAULT_HIERARCHY));
      mutator(currentHierarchy);
      onSchemaChange({ ...schema, hierarchy: currentHierarchy });
    },
    [schema, onSchemaChange],
  );

  // --- Selection ---
  const selectTab = useCallback((tabId: string) => {
    setSelection({ tabId });
  }, []);

  const selectFloor = useCallback((tabId: string, floorId: string) => {
    setSelection({ tabId, floorId });
  }, []);

  const selectBlock = useCallback((tabId: string, floorId: string, blockId: string) => {
    setSelection({ tabId, floorId, blockId });
  }, []);

  const selectField = useCallback(
    (tabId: string, floorId: string, blockId: string, fieldId: string) => {
      setSelection({ tabId, floorId, blockId, fieldId });
    },
    [],
  );

  const clearSelection = useCallback(() => {
    setSelection({});
  }, []);

  // --- Tab Operations ---
  const addTab = useCallback(
    (label: string) => {
      updateHierarchy((h) => {
        h.tabs.push(createDefaultTab(label));
      });
    },
    [updateHierarchy],
  );

  const removeTab = useCallback(
    (tabId: string) => {
      updateHierarchy((h) => {
        if (h.tabs.length <= 1) return; // Keep at least one tab
        h.tabs = h.tabs.filter((t) => t.id !== tabId);
        if (h.activeTab === tabId) {
          h.activeTab = h.tabs[0]?.id;
        }
      });
    },
    [updateHierarchy],
  );

  const updateTab = useCallback(
    (tabId: string, updates: Partial<TabItemConfig>) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        if (tab) Object.assign(tab, updates);
      });
    },
    [updateHierarchy],
  );

  const moveTab = useCallback(
    (tabId: string, direction: 'left' | 'right') => {
      updateHierarchy((h) => {
        const idx = h.tabs.findIndex((t) => t.id === tabId);
        if (idx === -1) return;
        const targetIdx = direction === 'left' ? idx - 1 : idx + 1;
        if (targetIdx < 0 || targetIdx >= h.tabs.length) return;
        [h.tabs[idx], h.tabs[targetIdx]] = [h.tabs[targetIdx], h.tabs[idx]];
      });
    },
    [updateHierarchy],
  );

  // --- Floor Operations ---
  const addFloor = useCallback(
    (tabId: string, title: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        if (tab) tab.floors.push(createDefaultFloor(title));
      });
    },
    [updateHierarchy],
  );

  const removeFloor = useCallback(
    (tabId: string, floorId: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        if (tab && tab.floors.length > 1) {
          tab.floors = tab.floors.filter((f) => f.id !== floorId);
        }
      });
    },
    [updateHierarchy],
  );

  const updateFloor = useCallback(
    (tabId: string, floorId: string, updates: Partial<FloorConfig>) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        if (floor) Object.assign(floor, updates);
      });
    },
    [updateHierarchy],
  );

  const toggleFloorCollapse = useCallback(
    (tabId: string, floorId: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        if (floor) floor.collapsed = !floor.collapsed;
      });
    },
    [updateHierarchy],
  );

  const moveFloor = useCallback(
    (tabId: string, floorId: string, direction: 'up' | 'down') => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        if (!tab) return;
        const idx = tab.floors.findIndex((f) => f.id === floorId);
        if (idx === -1) return;
        const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
        if (targetIdx < 0 || targetIdx >= tab.floors.length) return;
        [tab.floors[idx], tab.floors[targetIdx]] = [tab.floors[targetIdx], tab.floors[idx]];
      });
    },
    [updateHierarchy],
  );

  // --- Block Operations ---
  const addBlock = useCallback(
    (tabId: string, floorId: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        if (floor) floor.blocks.push(createDefaultBlock());
      });
    },
    [updateHierarchy],
  );

  const removeBlock = useCallback(
    (tabId: string, floorId: string, blockId: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        if (floor && floor.blocks.length > 1) {
          floor.blocks = floor.blocks.filter((b) => b.id !== blockId);
        }
      });
    },
    [updateHierarchy],
  );

  const updateBlock = useCallback(
    (tabId: string, floorId: string, blockId: string, updates: Partial<BlockConfig>) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        const block = floor?.blocks.find((b) => b.id === blockId);
        if (block) Object.assign(block, updates);
      });
    },
    [updateHierarchy],
  );

  // --- Field Operations ---
  const addField = useCallback(
    (
      tabId: string,
      floorId: string,
      blockId: string,
      fieldCode: string,
      componentType: string,
      props: Record<string, any> = {},
    ) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        const block = floor?.blocks.find((b) => b.id === blockId);
        if (block) {
          block.fields.push(createFieldCell(fieldCode, componentType, props));
        }
      });
    },
    [updateHierarchy],
  );

  const removeField = useCallback(
    (tabId: string, floorId: string, blockId: string, fieldId: string) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        const block = floor?.blocks.find((b) => b.id === blockId);
        if (block) {
          block.fields = block.fields.filter((f) => f.id !== fieldId);
        }
      });
    },
    [updateHierarchy],
  );

  const updateField = useCallback(
    (
      tabId: string,
      floorId: string,
      blockId: string,
      fieldId: string,
      updates: Partial<FieldCellConfig>,
    ) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        const block = floor?.blocks.find((b) => b.id === blockId);
        const field = block?.fields.find((f) => f.id === fieldId);
        if (field) Object.assign(field, updates);
      });
    },
    [updateHierarchy],
  );

  const moveField = useCallback(
    (
      tabId: string,
      floorId: string,
      blockId: string,
      fieldId: string,
      direction: 'up' | 'down',
    ) => {
      updateHierarchy((h) => {
        const tab = h.tabs.find((t) => t.id === tabId);
        const floor = tab?.floors.find((f) => f.id === floorId);
        const block = floor?.blocks.find((b) => b.id === blockId);
        if (!block) return;
        const idx = block.fields.findIndex((f) => f.id === fieldId);
        if (idx === -1) return;
        const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
        if (targetIdx < 0 || targetIdx >= block.fields.length) return;
        [block.fields[idx], block.fields[targetIdx]] = [block.fields[targetIdx], block.fields[idx]];
      });
    },
    [updateHierarchy],
  );

  // --- Mode ---
  const enableHierarchyMode = useCallback(() => {
    if (!schema.hierarchy) {
      onSchemaChange({ ...schema, hierarchy: JSON.parse(JSON.stringify(DEFAULT_HIERARCHY)) });
    }
  }, [schema, onSchemaChange]);

  const disableHierarchyMode = useCallback(() => {
    const { hierarchy: _, ...rest } = schema as CanvasSchema & { hierarchy?: TabContainerConfig };
    onSchemaChange(rest as CanvasSchema);
  }, [schema, onSchemaChange]);

  return {
    hierarchy,
    selection,
    isHierarchyMode,
    selectTab,
    selectFloor,
    selectBlock,
    selectField,
    clearSelection,
    addTab,
    removeTab,
    updateTab,
    moveTab,
    addFloor,
    removeFloor,
    updateFloor,
    toggleFloorCollapse,
    moveFloor,
    addBlock,
    removeBlock,
    updateBlock,
    addField,
    removeField,
    updateField,
    moveField,
    enableHierarchyMode,
    disableHierarchyMode,
  };
}
