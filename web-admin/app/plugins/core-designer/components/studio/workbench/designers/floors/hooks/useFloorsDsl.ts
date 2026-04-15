/**
 * useFloorsDsl Hook
 *
 * Manages CRUD operations on floors DSL structure.
 * Provides functions to add/remove/update/move floors, tabs, and components.
 */

import { useCallback } from 'react';
import type { PageSchema, DslFloor, DslComponent, DslTab } from '~/plugins/core-designer/components/studio/domain/dsl/types';

const generateId = () => `floor_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
const generateComponentId = () => `comp_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;

export function useFloorsDsl(dsl: PageSchema, onDslChange: (dsl: PageSchema) => void) {
  const floors = dsl.floors || [];

  const updateFloors = useCallback(
    (newFloors: DslFloor[]) => {
      onDslChange({ ...dsl, floors: newFloors });
    },
    [dsl, onDslChange],
  );

  // ---- Floor operations ----

  const addFloor = useCallback(
    (title: string, insertIndex?: number) => {
      const floor: DslFloor = { id: generateId(), title, components: [] };
      const newFloors = [...floors];
      if (insertIndex !== undefined) {
        newFloors.splice(insertIndex, 0, floor);
      } else {
        newFloors.push(floor);
      }
      updateFloors(newFloors);
      return floor.id;
    },
    [floors, updateFloors],
  );

  const removeFloor = useCallback(
    (floorId: string) => {
      updateFloors(floors.filter((f) => f.id !== floorId));
    },
    [floors, updateFloors],
  );

  const updateFloor = useCallback(
    (floorId: string, updates: Partial<DslFloor>) => {
      updateFloors(floors.map((f) => (f.id === floorId ? { ...f, ...updates } : f)));
    },
    [floors, updateFloors],
  );

  const moveFloor = useCallback(
    (oldIndex: number, newIndex: number) => {
      if (oldIndex === newIndex) return;
      const newFloors = [...floors];
      const [moved] = newFloors.splice(oldIndex, 1);
      newFloors.splice(newIndex, 0, moved);
      updateFloors(newFloors);
    },
    [floors, updateFloors],
  );

  // ---- TabsFloor operations ----

  const addTabToFloor = useCallback(
    (floorId: string, tab: DslTab) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return { ...f, type: 'TabsFloor' as const, tabs: [...(f.tabs || []), tab] };
        }),
      );
    },
    [floors, updateFloors],
  );

  const removeTabFromFloor = useCallback(
    (floorId: string, tabKey: string) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          const newTabs = (f.tabs || []).filter((t) => t.key !== tabKey);
          return { ...f, tabs: newTabs };
        }),
      );
    },
    [floors, updateFloors],
  );

  const updateTab = useCallback(
    (floorId: string, tabKey: string, updates: Partial<DslTab>) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return {
            ...f,
            tabs: (f.tabs || []).map((t) => (t.key === tabKey ? { ...t, ...updates } : t)),
          };
        }),
      );
    },
    [floors, updateFloors],
  );

  const convertToTabsFloor = useCallback(
    (floorId: string) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return {
            ...f,
            type: 'TabsFloor' as const,
            tabs: [
              {
                key: 'tab1',
                label: 'Tab 1',
                content: { id: generateComponentId(), type: 'container', config: {} },
              },
            ],
          };
        }),
      );
    },
    [floors, updateFloors],
  );

  const convertToNormalFloor = useCallback(
    (floorId: string) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          const { type: _type, tabs: _tabs, ...rest } = f;
          return rest;
        }),
      );
    },
    [floors, updateFloors],
  );

  // ---- Component operations ----

  const addComponent = useCallback(
    (floorId: string, component: Omit<DslComponent, 'id'>) => {
      const id = generateComponentId();
      const newComponent: DslComponent = { ...component, id } as DslComponent;
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return { ...f, components: [...(f.components || []), newComponent] };
        }),
      );
      return id;
    },
    [floors, updateFloors],
  );

  const removeComponent = useCallback(
    (floorId: string, componentId: string) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return { ...f, components: (f.components || []).filter((c) => c.id !== componentId) };
        }),
      );
    },
    [floors, updateFloors],
  );

  const updateComponent = useCallback(
    (floorId: string, componentId: string, updates: Partial<DslComponent>) => {
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          return {
            ...f,
            components: (f.components || []).map((c) =>
              c.id === componentId ? { ...c, ...updates } : c,
            ),
          };
        }),
      );
    },
    [floors, updateFloors],
  );

  const reorderComponents = useCallback(
    (floorId: string, oldIndex: number, newIndex: number) => {
      if (oldIndex === newIndex) return;
      updateFloors(
        floors.map((f) => {
          if (f.id !== floorId) return f;
          const comps = [...(f.components || [])];
          const [moved] = comps.splice(oldIndex, 1);
          comps.splice(newIndex, 0, moved);
          return { ...f, components: comps };
        }),
      );
    },
    [floors, updateFloors],
  );

  const moveComponent = useCallback(
    (fromFloorId: string, toFloorId: string, componentId: string) => {
      let movedComponent: DslComponent | undefined;
      const withRemoved = floors.map((f) => {
        if (f.id !== fromFloorId) return f;
        movedComponent = (f.components || []).find((c) => c.id === componentId);
        return { ...f, components: (f.components || []).filter((c) => c.id !== componentId) };
      });
      if (!movedComponent) return;
      updateFloors(
        withRemoved.map((f) => {
          if (f.id !== toFloorId) return f;
          return { ...f, components: [...(f.components || []), movedComponent!] };
        }),
      );
    },
    [floors, updateFloors],
  );

  return {
    floors,
    addFloor,
    removeFloor,
    updateFloor,
    moveFloor,
    addTabToFloor,
    removeTabFromFloor,
    updateTab,
    convertToTabsFloor,
    convertToNormalFloor,
    addComponent,
    removeComponent,
    updateComponent,
    moveComponent,
    reorderComponents,
  };
}
