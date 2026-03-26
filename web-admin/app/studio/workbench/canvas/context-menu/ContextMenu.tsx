/**
 * Context Menu Component
 *
 * Right-click context menu for canvas and components.
 *
 * @since 3.2.0
 */

import React, { useEffect, useRef } from 'react';

export interface MenuItem {
  id: string;
  label: string;
  icon?: React.ReactNode;
  shortcut?: string;
  disabled?: boolean;
  danger?: boolean;
  divider?: boolean;
  onClick?: () => void;
}

export interface ContextMenuProps {
  /** Menu position */
  position: { x: number; y: number };
  /** Menu items */
  items: MenuItem[];
  /** Close handler */
  onClose: () => void;
}

/**
 * Context Menu Component
 */
export const ContextMenu: React.FC<ContextMenuProps> = ({ position, items, onClose }) => {
  const menuRef = useRef<HTMLDivElement>(null);

  // Close on click outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    };

    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [onClose]);

  // Adjust position to stay within viewport
  useEffect(() => {
    if (!menuRef.current) return;

    const menu = menuRef.current;
    const rect = menu.getBoundingClientRect();
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;

    let adjustedX = position.x;
    let adjustedY = position.y;

    if (rect.right > viewportWidth) {
      adjustedX = viewportWidth - rect.width - 8;
    }
    if (rect.bottom > viewportHeight) {
      adjustedY = viewportHeight - rect.height - 8;
    }

    if (adjustedX !== position.x || adjustedY !== position.y) {
      menu.style.left = `${adjustedX}px`;
      menu.style.top = `${adjustedY}px`;
    }
  }, [position]);

  const handleItemClick = (item: MenuItem) => {
    if (item.disabled) return;
    item.onClick?.();
    onClose();
  };

  return (
    <div
      ref={menuRef}
      className="animate-in fade-in zoom-in-95 fixed z-50 min-w-[180px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg duration-100"
      style={{ left: position.x, top: position.y }}
    >
      {items.map((item, index) => {
        if (item.divider) {
          return <div key={`divider-${index}`} className="my-1 border-t border-gray-100" />;
        }

        return (
          <button
            key={item.id}
            type="button"
            onClick={() => handleItemClick(item)}
            disabled={item.disabled}
            className={`flex w-full items-center justify-between gap-4 px-3 py-1.5 text-left text-sm transition-colors ${
              item.disabled
                ? 'cursor-not-allowed text-gray-400'
                : item.danger
                  ? 'text-red-600 hover:bg-red-50'
                  : 'text-gray-700 hover:bg-gray-100'
            } `}
          >
            <span className="flex items-center gap-2">
              {item.icon && (
                <span className="flex h-4 w-4 items-center justify-center text-gray-500">
                  {item.icon}
                </span>
              )}
              <span>{item.label}</span>
            </span>
            {item.shortcut && <span className="text-xs text-gray-400">{item.shortcut}</span>}
          </button>
        );
      })}
    </div>
  );
};

export default ContextMenu;
