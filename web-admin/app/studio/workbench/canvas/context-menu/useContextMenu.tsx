/**
 * useContextMenu Hook
 *
 * Manages context menu state and provides menu items.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useMemo } from 'react';
import type { MenuItem } from './ContextMenu';
import { useDesignerStore } from '~/studio/hooks/store/useDesignerStore';
import { useClipboard } from '~/studio/services/clipboard';

export interface ContextMenuState {
  isOpen: boolean;
  position: { x: number; y: number };
  targetComponentId: string | null;
}

export interface UseContextMenuResult {
  /** Menu state */
  state: ContextMenuState;
  /** Open context menu */
  open: (e: React.MouseEvent, componentId?: string) => void;
  /** Close context menu */
  close: () => void;
  /** Get menu items based on context */
  getMenuItems: () => MenuItem[];
}

/**
 * Context menu hook
 */
export function useContextMenu(): UseContextMenuResult {
  const [state, setState] = useState<ContextMenuState>({
    isOpen: false,
    position: { x: 0, y: 0 },
    targetComponentId: null,
  });

  const { selectedComponentId, selectComponent, removeComponent } = useDesignerStore();

  const { copy, cut, paste, duplicate, hasContent } = useClipboard();

  // Open menu
  const open = useCallback(
    (e: React.MouseEvent, componentId?: string) => {
      e.preventDefault();
      e.stopPropagation();

      // Select component if clicking on one
      if (componentId && componentId !== selectedComponentId) {
        selectComponent(componentId);
      }

      setState({
        isOpen: true,
        position: { x: e.clientX, y: e.clientY },
        targetComponentId: componentId || null,
      });
    },
    [selectedComponentId, selectComponent],
  );

  // Close menu
  const close = useCallback(() => {
    setState((prev) => ({ ...prev, isOpen: false }));
  }, []);

  // Get menu items
  const getMenuItems = useCallback((): MenuItem[] => {
    const hasSelection = !!selectedComponentId;
    const items: MenuItem[] = [];

    // Edit actions
    items.push(
      {
        id: 'copy',
        label: '复制',
        shortcut: '⌘C',
        disabled: !hasSelection,
        onClick: copy,
        icon: <CopyIcon />,
      },
      {
        id: 'cut',
        label: '剪切',
        shortcut: '⌘X',
        disabled: !hasSelection,
        onClick: cut,
        icon: <CutIcon />,
      },
      {
        id: 'paste',
        label: '粘贴',
        shortcut: '⌘V',
        disabled: !hasContent,
        onClick: () => paste(),
        icon: <PasteIcon />,
      },
      {
        id: 'duplicate',
        label: '原地复制',
        shortcut: '⌘D',
        disabled: !hasSelection,
        onClick: duplicate,
        icon: <DuplicateIcon />,
      },
    );

    items.push({ id: 'divider-1', label: '', divider: true });

    // Delete action
    items.push({
      id: 'delete',
      label: '删除',
      shortcut: 'Delete',
      disabled: !hasSelection,
      danger: true,
      onClick: () => {
        if (selectedComponentId) {
          removeComponent(selectedComponentId);
          selectComponent(null);
        }
      },
      icon: <DeleteIcon />,
    });

    items.push({ id: 'divider-2', label: '', divider: true });

    // Layer actions (only for single selection)
    if (hasSelection) {
      items.push(
        {
          id: 'bring-front',
          label: '移到顶层',
          disabled: !hasSelection,
          icon: <BringFrontIcon />,
        },
        {
          id: 'send-back',
          label: '移到底层',
          disabled: !hasSelection,
          icon: <SendBackIcon />,
        },
      );

      items.push({ id: 'divider-3', label: '', divider: true });
    }

    // Select all
    items.push({
      id: 'select-all',
      label: '全选',
      shortcut: '⌘A',
      icon: <SelectAllIcon />,
    });

    return items;
  }, [
    selectedComponentId,
    hasContent,
    copy,
    cut,
    paste,
    duplicate,
    removeComponent,
    selectComponent,
  ]);

  return {
    state,
    open,
    close,
    getMenuItems,
  };
}

// Icon components
const CopyIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
    />
  </svg>
);

const CutIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M14.121 14.121L19 19m-7-7l7-7m-7 7l-2.879 2.879M12 12L9.121 9.121m0 5.758a3 3 0 10-4.243 4.243 3 3 0 004.243-4.243zm0-5.758a3 3 0 10-4.243-4.243 3 3 0 004.243 4.243z"
    />
  </svg>
);

const PasteIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
    />
  </svg>
);

const DuplicateIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M8 7v8a2 2 0 002 2h6M8 7V5a2 2 0 012-2h4.586a1 1 0 01.707.293l4.414 4.414a1 1 0 01.293.707V15a2 2 0 01-2 2h-2M8 7H6a2 2 0 00-2 2v10a2 2 0 002 2h8a2 2 0 002-2v-2"
    />
  </svg>
);

const DeleteIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
    />
  </svg>
);

const BringFrontIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 15l7-7 7 7" />
  </svg>
);

const SendBackIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 9l-7 7-7-7" />
  </svg>
);

const SelectAllIcon = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M4 5a1 1 0 011-1h14a1 1 0 011 1v14a1 1 0 01-1 1H5a1 1 0 01-1-1V5z"
    />
  </svg>
);

export default useContextMenu;
