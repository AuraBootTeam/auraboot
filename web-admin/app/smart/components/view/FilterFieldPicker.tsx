/**
 * FilterFieldPicker — dropdown to pick a model field to add as a filter.
 *
 * Renders via createPortal at the given anchorEl coordinates.
 * Groups fields into "Common Fields" (first 6) and "Other Fields".
 */
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface FilterFieldPickerProps {
  open: boolean;
  anchorEl?: { x: number; y: number };
  fields: Array<{
    fieldCode: string;
    label: string;
    fieldType: string;
    dictCode?: string;
  }>;
  /** Already-added fields — shown with a checkmark */
  activeFieldCodes: string[];
  onSelect: (fieldCode: string) => void;
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Return an icon character based on field type */
function fieldIcon(fieldType: string): string {
  const t = fieldType.toLowerCase();
  if (t === 'number' || t === 'integer' || t === 'decimal') return '\u{1F4CA}'; // 📊
  if (t === 'money' || t === 'currency') return '\u{1F4B0}'; // 💰
  if (t === 'date' || t === 'datetime') return '\u{1F4C5}'; // 📅
  if (t === 'reference' || t === 'user') return '\u{1F464}'; // 👤
  return '\u{1F4DD}'; // 📝
}

/** Short human-readable badge for the field type */
function typeBadge(fieldType: string): string {
  const map: Record<string, string> = {
    text: 'Text',
    number: 'Num',
    integer: 'Int',
    decimal: 'Dec',
    money: 'Money',
    currency: 'Money',
    date: 'Date',
    datetime: 'DateTime',
    boolean: 'Bool',
    enum: 'Enum',
    dict: 'Dict',
    reference: 'Ref',
    user: 'User',
  };
  return map[fieldType.toLowerCase()] ?? fieldType;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function FilterFieldPicker({
  open,
  anchorEl,
  fields,
  activeFieldCodes,
  onSelect,
  onClose,
}: FilterFieldPickerProps) {
  const [search, setSearch] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);

  // Reset search when opened
  useEffect(() => {
    if (open) setSearch('');
  }, [open]);

  // Click-outside detection
  useEffect(() => {
    if (!open) return;
    function handleClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    // Delay listener attachment to avoid the same click that opened the picker
    const id = requestAnimationFrame(() => {
      document.addEventListener('mousedown', handleClick);
    });
    return () => {
      cancelAnimationFrame(id);
      document.removeEventListener('mousedown', handleClick);
    };
  }, [open, onClose]);

  if (!open || !anchorEl) return null;

  const filtered = fields.filter((f) => f.label.toLowerCase().includes(search.toLowerCase()));

  const commonFields = filtered.slice(0, 6);
  const otherFields = filtered.slice(6);
  const activeSet = new Set(activeFieldCodes);

  const renderItem = (f: (typeof fields)[0]) => (
    <button
      key={f.fieldCode}
      type="button"
      className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-gray-100"
      onClick={() => {
        onSelect(f.fieldCode);
        onClose();
      }}
    >
      <span className="flex-shrink-0 text-base leading-none">{fieldIcon(f.fieldType)}</span>
      <span className="flex-1 truncate text-gray-800">{f.label}</span>
      <span className="flex-shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
        {typeBadge(f.fieldType)}
      </span>
      {activeSet.has(f.fieldCode) && <span className="flex-shrink-0 text-blue-500">&#10003;</span>}
    </button>
  );

  const content = (
    <div
      ref={containerRef}
      className="fixed z-[9999] flex max-h-[360px] min-w-[240px] flex-col overflow-hidden rounded-lg border border-gray-200 bg-white shadow-lg"
      style={{ left: anchorEl.x, top: anchorEl.y }}
    >
      {/* Search input */}
      <div className="border-b border-gray-100 p-2">
        <input
          type="text"
          className="w-full rounded border border-gray-200 px-2 py-1 text-sm outline-none placeholder:text-gray-400 focus:border-blue-400"
          placeholder="Search fields..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          autoFocus
        />
      </div>

      {/* Scrollable field list */}
      <div className="flex-1 overflow-y-auto p-1">
        {filtered.length === 0 && (
          <p className="px-2 py-3 text-center text-sm text-gray-400">No fields found</p>
        )}

        {commonFields.length > 0 && (
          <>
            <p className="px-2 pt-1 pb-0.5 text-[10px] font-semibold tracking-wider text-gray-400 uppercase">
              Common Fields
            </p>
            {commonFields.map(renderItem)}
          </>
        )}

        {otherFields.length > 0 && (
          <>
            <p className="mt-1 px-2 pt-1 pb-0.5 text-[10px] font-semibold tracking-wider text-gray-400 uppercase">
              Other Fields
            </p>
            {otherFields.map(renderItem)}
          </>
        )}
      </div>
    </div>
  );

  return createPortal(content, document.body);
}
