/**
 * MemberPicker Component
 *
 * A user/member selection field that searches and displays team members.
 * Supports single and multi-select modes with avatar chips and search popup.
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { Search, X, Plus, Loader2, Users } from 'lucide-react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';
import { getAvatarColor, getInitials } from './avatar-utils';

export interface MemberOption {
  id: string;
  name: string;
  email?: string;
  avatar?: string;
  department?: string;
}

export interface MemberPickerProps {
  /** Current value (single ID or array of IDs) */
  value?: string | string[];
  /** Callback when selection changes */
  onChange?: (value: string | string[] | undefined) => void;
  /** Allow selecting multiple members */
  multiple?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** Whether the field is read-only */
  readOnly?: boolean;
  /** Whether the field is disabled */
  disabled?: boolean;
  /** Custom CSS class */
  className?: string;
}

/**
 * MemberPicker - User/member selection field with avatar chips
 */
export const MemberPicker: React.FC<MemberPickerProps> = ({
  value,
  onChange,
  multiple = false,
  placeholder = 'Select member...',
  readOnly = false,
  disabled = false,
  className,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [options, setOptions] = useState<MemberOption[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<MemberOption[]>([]);
  const [loading, setLoading] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  // Selected IDs
  const selectedIds = Array.isArray(value) ? value : value ? [value] : [];

  // Close dropdown on outside click
  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  // Focus search on open
  useEffect(() => {
    if (open) {
      requestAnimationFrame(() => searchRef.current?.focus());
    }
  }, [open]);

  // Search users
  const searchUsers = useCallback(async (keyword: string) => {
    setLoading(true);
    try {
      // NOTE: the unauthenticated /api/users/search endpoint was removed during the 2026-04
      // security review. The tenant-scoped replacement lives under /api/admin/users/search —
      // it is callable by any tenant member and only returns users within the caller's tenant.
      const response = await fetch(
        `/api/admin/users/search?keyword=${encodeURIComponent(keyword)}&size=20`,
      );
      if (response.ok) {
        const result = await response.json();
        if (ResultHelper.isSuccess(result) && result.data) {
          const users = (result.data.content || result.data || []).map(
            (u: Record<string, unknown>) => ({
              id: String(u.pid || u.id),
              name: String(u.displayName || u.name || u.email || ''),
              email: String(u.email || ''),
              avatar: u.avatar ? String(u.avatar) : undefined,
              department: u.department ? String(u.department) : undefined,
            }),
          );
          setOptions(users);
        }
      }
    } catch {
      // CATCH: non-transactional HTTP call, safe to handle
    } finally {
      setLoading(false);
    }
  }, []);

  // Load users when dropdown opens or search changes
  useEffect(() => {
    if (open) {
      searchUsers(search);
    }
  }, [open, search, searchUsers]);

  // Resolve selected member names on mount
  useEffect(() => {
    if (selectedIds.length > 0 && selectedMembers.length === 0) {
      const resolveMembers = async () => {
        const members: MemberOption[] = [];
        for (const id of selectedIds) {
          try {
            const res = await fetch(`/api/users/${id}`);
            if (res.ok) {
              const result = await res.json();
              if (ResultHelper.isSuccess(result) && result.data) {
                members.push({
                  id: String(result.data.pid || result.data.id),
                  name: String(
                    result.data.displayName || result.data.name || result.data.email || '',
                  ),
                  email: String(result.data.email || ''),
                  department: result.data.department ? String(result.data.department) : undefined,
                });
              }
            }
          } catch {
            // CATCH: non-transactional HTTP call, fallback to ID display
            members.push({ id, name: id });
          }
        }
        setSelectedMembers(members);
      };
      resolveMembers();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSelect = useCallback(
    (member: MemberOption) => {
      if (multiple) {
        const newIds = selectedIds.includes(member.id)
          ? selectedIds.filter((id) => id !== member.id)
          : [...selectedIds, member.id];
        setSelectedMembers((prev) =>
          selectedIds.includes(member.id)
            ? prev.filter((m) => m.id !== member.id)
            : [...prev, member],
        );
        onChange?.(newIds.length > 0 ? newIds : undefined);
      } else {
        setSelectedMembers([member]);
        onChange?.(member.id);
        setOpen(false);
      }
    },
    [multiple, selectedIds, onChange],
  );

  const handleRemove = useCallback(
    (id: string, e?: React.MouseEvent) => {
      e?.stopPropagation();
      const newIds = selectedIds.filter((i) => i !== id);
      setSelectedMembers((prev) => prev.filter((m) => m.id !== id));
      onChange?.(multiple ? (newIds.length > 0 ? newIds : undefined) : undefined);
    },
    [selectedIds, multiple, onChange],
  );

  // Read-only display
  if (readOnly) {
    return (
      <div data-testid="member-picker-readonly" className={cn('flex flex-wrap gap-1.5', className)}>
        {selectedMembers.length > 0 ? (
          selectedMembers.map((m) => (
            <span
              key={m.id}
              data-testid={`member-picker-selected-${m.id}`}
              className="inline-flex items-center gap-1.5 rounded-full bg-gray-50 py-0.5 pr-2.5 pl-1 text-sm text-gray-700"
            >
              <span
                className={cn(
                  'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full text-[10px] font-semibold text-white',
                  getAvatarColor(m.name),
                )}
              >
                {getInitials(m.name)}
              </span>
              {m.name}
            </span>
          ))
        ) : (
          <span className="text-sm text-gray-400">-</span>
        )}
      </div>
    );
  }

  return (
    <div ref={dropdownRef} data-testid="member-picker" className={cn('relative', className)}>
      {/* Selected members display + Add button */}
      <div
        data-testid="member-picker-trigger"
        className={cn(
          'min-h-[38px] rounded-lg border px-2 py-1.5 transition-all',
          disabled
            ? 'cursor-not-allowed border-gray-200 bg-gray-50 opacity-60'
            : open
              ? 'border-blue-500 bg-white ring-2 ring-blue-100'
              : 'border-gray-300 bg-white hover:border-gray-400',
        )}
      >
        <div className="flex flex-wrap items-center gap-1.5">
          {/* Selected member chips */}
          {selectedMembers.map((m) => (
            <span
              key={m.id}
              data-testid={`member-picker-selected-${m.id}`}
              className="inline-flex items-center gap-1 rounded-full bg-gray-100 py-0.5 pr-1 pl-0.5 text-sm text-gray-800 transition-colors hover:bg-gray-200"
            >
              <span
                className={cn(
                  'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full text-[10px] font-semibold text-white',
                  getAvatarColor(m.name),
                )}
              >
                {getInitials(m.name)}
              </span>
              <span className="max-w-[100px] truncate text-xs font-medium">{m.name}</span>
              {!disabled && (
                <button
                  type="button"
                  data-testid={`member-picker-remove-${m.id}`}
                  onClick={(e) => handleRemove(m.id, e)}
                  className="flex h-4 w-4 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-300 hover:text-gray-600"
                >
                  <X className="h-2.5 w-2.5" />
                </button>
              )}
            </span>
          ))}

          {/* Add member button */}
          {!disabled && (
            <button
              type="button"
              data-testid="member-picker-add"
              onClick={() => setOpen(!open)}
              className={cn(
                'inline-flex items-center gap-1 rounded-full border border-dashed px-2 py-0.5 text-xs transition-colors',
                selectedMembers.length === 0
                  ? 'border-gray-300 text-gray-400 hover:border-gray-400 hover:text-gray-500'
                  : 'border-gray-300 text-gray-400 hover:border-blue-400 hover:text-blue-500',
              )}
            >
              <Plus className="h-3 w-3" />
              <span>{selectedMembers.length === 0 ? placeholder : 'Add'}</span>
            </button>
          )}
        </div>
      </div>

      {/* Search popup dropdown */}
      {open && !disabled && (
        <div
          data-testid="member-picker-popup"
          className="absolute top-full right-0 left-0 z-50 mt-1.5 overflow-hidden rounded-lg border border-gray-200 bg-white shadow-lg"
        >
          {/* Search input */}
          <div className="border-b border-gray-100 p-2">
            <div className="relative">
              <Search className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
              <input
                ref={searchRef}
                type="text"
                data-testid="member-picker-search-input"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search members..."
                className="w-full rounded-md border border-gray-200 bg-gray-50 py-2 pr-3 pl-9 text-sm transition-colors focus:border-blue-400 focus:bg-white focus:ring-1 focus:ring-blue-100 focus:outline-none"
              />
            </div>
          </div>

          {/* Results */}
          <div className="max-h-56 overflow-y-auto py-1">
            {loading ? (
              <div className="flex flex-col items-center justify-center py-8 text-gray-400">
                <Loader2 className="h-5 w-5 animate-spin text-blue-500" />
                <span className="mt-2 text-xs">Searching...</span>
              </div>
            ) : options.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-8 text-gray-400">
                <Users className="h-7 w-7 text-gray-300" />
                <span className="mt-2 text-xs">No members found</span>
              </div>
            ) : (
              options.map((opt) => {
                const isSelected = selectedIds.includes(opt.id);
                return (
                  <button
                    key={opt.id}
                    type="button"
                    data-testid={`member-picker-option-${opt.id}`}
                    onClick={() => handleSelect(opt)}
                    className={cn(
                      'mx-1 flex w-[calc(100%-8px)] items-center gap-2.5 rounded-md px-2 py-2 text-left transition-colors',
                      isSelected ? 'bg-blue-50' : 'hover:bg-gray-50',
                    )}
                  >
                    <span
                      className={cn(
                        'flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full text-xs font-semibold text-white',
                        getAvatarColor(opt.name),
                      )}
                    >
                      {getInitials(opt.name)}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5">
                        <span
                          className={cn(
                            'truncate text-sm font-medium',
                            isSelected ? 'text-blue-700' : 'text-gray-700',
                          )}
                        >
                          {opt.name}
                        </span>
                        {isSelected && (
                          <svg
                            className="h-4 w-4 flex-shrink-0 text-blue-500"
                            fill="currentColor"
                            viewBox="0 0 20 20"
                          >
                            <path
                              fillRule="evenodd"
                              d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                              clipRule="evenodd"
                            />
                          </svg>
                        )}
                      </div>
                      <div className="flex items-center gap-1.5 text-xs text-gray-400">
                        {opt.email && <span className="truncate">{opt.email}</span>}
                        {opt.email && opt.department && <span>&middot;</span>}
                        {opt.department && (
                          <span className="truncate text-gray-500">{opt.department}</span>
                        )}
                      </div>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default MemberPicker;
