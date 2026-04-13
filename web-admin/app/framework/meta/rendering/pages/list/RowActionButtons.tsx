/**
 * RowActionButtons — Row-level action buttons for list tables.
 * Renders primary action as a link and remaining actions in a compact "..." dropdown menu.
 * Extracted from ListPageContent.tsx (behavior-preserving refactor).
 */

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

/**
 * DropdownMenu — Portal-rendered dropdown for row actions.
 * Positioned absolutely relative to the trigger button to avoid
 * being clipped by table overflow-x-auto containers.
 */
function DropdownMenu({
  menuRef,
  moreButtons,
  record,
  resolveButtonLabel,
  handleAction,
  setOpen,
}: {
  menuRef: React.RefObject<HTMLDivElement | null>;
  moreButtons: ButtonConfig[];
  record: any;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: any) => void;
  setOpen: (v: boolean) => void;
}) {
  const [pos, setPos] = useState<{ top: number; left: number }>({ top: 0, left: 0 });

  useEffect(() => {
    if (!menuRef.current) return;
    const rect = menuRef.current.getBoundingClientRect();
    setPos({
      top: rect.bottom + 4,
      left: rect.right - 120, // min-w-[120px] aligned to right edge
    });
  }, [menuRef]);

  return (
    <div
      className="fixed z-[9999] min-w-[120px] rounded-md border border-gray-200 bg-white py-1 shadow-lg"
      style={{ top: pos.top, left: Math.max(0, pos.left) }}
      data-testid="row-action-dropdown"
    >
      {moreButtons.map((btn) => (
        <button
          type="button"
          key={btn.code}
          data-testid={`row-action-${btn.code}`}
          onClick={(e) => {
            e.stopPropagation();
            setOpen(false);
            handleAction(btn, record);
          }}
          className={`block w-full px-3 py-1.5 text-left text-sm transition-colors ${
            btn.danger ? 'text-red-600 hover:bg-red-50' : 'text-gray-700 hover:bg-gray-50'
          }`}
        >
          {resolveButtonLabel(btn)}
        </button>
      ))}
    </div>
  );
}

/**
 * RowActionButtons — Renders primary action as a link and remaining actions
 * in a compact "..." dropdown menu. Improves UX over flat text buttons.
 */
export function RowActionButtons({
  buttons,
  record,
  evaluateVisibleWhen,
  resolveButtonLabel,
  handleAction,
}: {
  buttons: ButtonConfig[];
  record: any;
  evaluateVisibleWhen: (expr: string | undefined, record: any) => boolean;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: any) => void;
}) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click (check both trigger and portaled dropdown)
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (menuRef.current && menuRef.current.contains(target)) return;
      // Check if click is inside the portaled dropdown
      const dropdown = document.querySelector('[data-testid="row-action-dropdown"]');
      if (dropdown && dropdown.contains(target)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const visibleButtons = buttons.filter((button) =>
    evaluateVisibleWhen(button.visibleWhen, record),
  );

  if (visibleButtons.length === 0) return null;

  // If only 1 button, render it directly
  if (visibleButtons.length === 1) {
    const btn = visibleButtons[0];
    return (
      <div className="inline-flex items-center justify-start">
        <button
          type="button"
          data-testid={`row-action-${btn.code}`}
          onClick={(e) => {
            e.stopPropagation();
            handleAction(btn, record);
          }}
          className={`rounded-md px-2 py-1 text-sm font-medium transition-colors ${
            btn.danger
              ? 'text-red-600 hover:bg-red-50 hover:text-red-800'
              : 'text-blue-600 hover:bg-blue-50 hover:text-blue-800'
          }`}
        >
          {resolveButtonLabel(btn)}
        </button>
      </div>
    );
  }

  // Primary = first non-danger button; rest go into dropdown
  const primaryBtn = visibleButtons[0];
  const moreButtons = visibleButtons.slice(1);

  return (
    <div className="inline-flex items-center justify-start gap-1">
      {/* Primary action as link */}
      <button
        type="button"
        data-testid={`row-action-${primaryBtn.code}`}
        onClick={(e) => {
          e.stopPropagation();
          handleAction(primaryBtn, record);
        }}
        className="rounded-md px-2 py-1 text-sm font-medium text-blue-600 transition-colors hover:bg-blue-50 hover:text-blue-800"
      >
        {resolveButtonLabel(primaryBtn)}
      </button>

      {/* More actions dropdown — rendered via Portal to avoid overflow clipping */}
      {moreButtons.length > 0 && (
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            data-testid="row-action-more"
            onClick={(e) => {
              e.stopPropagation();
              setOpen(!open);
            }}
            className="rounded-md p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
            aria-label="More actions"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <circle cx="12" cy="12" r="1" />
              <circle cx="12" cy="5" r="1" />
              <circle cx="12" cy="19" r="1" />
            </svg>
          </button>
          {open &&
            createPortal(
              <DropdownMenu
                menuRef={menuRef}
                moreButtons={moreButtons}
                record={record}
                resolveButtonLabel={resolveButtonLabel}
                handleAction={handleAction}
                setOpen={setOpen}
              />,
              document.body,
            )}
        </div>
      )}
    </div>
  );
}
