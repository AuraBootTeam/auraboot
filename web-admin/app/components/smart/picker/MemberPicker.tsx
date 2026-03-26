/**
 * MemberPicker Component
 *
 * A user/member selection field that searches and displays team members.
 * Supports single and multi-select modes.
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';

export interface MemberOption {
  id: string;
  name: string;
  email?: string;
  avatar?: string;
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
 * MemberPicker - User/member selection field
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
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [open]);

  // Search users
  const searchUsers = useCallback(async (keyword: string) => {
    setLoading(true);
    try {
      const response = await fetch(
        `/api/users/search?keyword=${encodeURIComponent(keyword)}&size=20`,
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
            }),
          );
          setOptions(users);
        }
      }
    } catch {
      // Silently fail search
    } finally {
      setLoading(false);
    }
  }, []);

  // Load initial users and resolve selected values
  useEffect(() => {
    if (open) {
      searchUsers(search);
      searchRef.current?.focus();
    }
  }, [open, search, searchUsers]);

  // Resolve selected member names on mount
  useEffect(() => {
    if (selectedIds.length > 0 && selectedMembers.length === 0) {
      // Try to resolve member names
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
                });
              }
            }
          } catch {
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
    (id: string) => {
      const newIds = selectedIds.filter((i) => i !== id);
      setSelectedMembers((prev) => prev.filter((m) => m.id !== id));
      onChange?.(multiple ? (newIds.length > 0 ? newIds : undefined) : undefined);
    },
    [selectedIds, multiple, onChange],
  );

  // Read-only display
  if (readOnly) {
    return (
      <div className={cn('flex flex-wrap gap-1', className)}>
        {selectedMembers.length > 0 ? (
          selectedMembers.map((m) => (
            <span
              key={m.id}
              className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-sm text-blue-700"
            >
              <MemberAvatar member={m} size="sm" />
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
    <div ref={dropdownRef} className={cn('relative', className)}>
      {/* Trigger */}
      <div
        onClick={() => !disabled && setOpen(!open)}
        className={cn(
          'min-h-[36px] cursor-pointer rounded-md border border-gray-300 px-3 py-1.5 text-sm',
          'flex flex-wrap items-center gap-1',
          'focus-within:ring-2 focus-within:ring-blue-500 hover:border-blue-400',
          disabled && 'cursor-not-allowed bg-gray-100 opacity-50',
        )}
      >
        {selectedMembers.length > 0 ? (
          selectedMembers.map((m) => (
            <span
              key={m.id}
              className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-700"
            >
              <MemberAvatar member={m} size="sm" />
              {m.name}
              {!disabled && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleRemove(m.id);
                  }}
                  className="ml-0.5 text-blue-400 hover:text-blue-600"
                >
                  ×
                </button>
              )}
            </span>
          ))
        ) : (
          <span className="text-gray-400">{placeholder}</span>
        )}
      </div>

      {/* Dropdown */}
      {open && (
        <div className="absolute top-full right-0 left-0 z-50 mt-1 max-h-64 overflow-hidden rounded-md border border-gray-200 bg-white shadow-lg">
          <div className="border-b border-gray-100 p-2">
            <input
              ref={searchRef}
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search members..."
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
            />
          </div>
          <div className="max-h-48 overflow-y-auto">
            {loading ? (
              <div className="px-3 py-4 text-center text-sm text-gray-400">
                <span className="mr-2 inline-block h-4 w-4 animate-spin rounded-full border-2 border-gray-200 border-t-blue-500" />
                Searching...
              </div>
            ) : options.length === 0 ? (
              <div className="px-3 py-4 text-center text-sm text-gray-400">No members found</div>
            ) : (
              options.map((opt) => {
                const isSelected = selectedIds.includes(opt.id);
                return (
                  <button
                    key={opt.id}
                    type="button"
                    onClick={() => handleSelect(opt)}
                    className={cn(
                      'flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-blue-50',
                      isSelected && 'bg-blue-50',
                    )}
                  >
                    <MemberAvatar member={opt} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium text-gray-700">{opt.name}</div>
                      {opt.email && (
                        <div className="truncate text-xs text-gray-400">{opt.email}</div>
                      )}
                    </div>
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

/**
 * MemberAvatar - Small avatar display for a member
 */
const MemberAvatar: React.FC<{ member: MemberOption; size?: 'sm' | 'md' }> = ({
  member,
  size = 'md',
}) => {
  const sizeClass = size === 'sm' ? 'w-4 h-4 text-[8px]' : 'w-6 h-6 text-xs';

  if (member.avatar) {
    return (
      <img
        src={member.avatar}
        alt={member.name}
        className={cn(sizeClass, 'rounded-full object-cover')}
      />
    );
  }

  const initials = member.name
    .split(/\s+/)
    .map((n) => n[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <div
      className={cn(
        sizeClass,
        'flex flex-shrink-0 items-center justify-center rounded-full bg-blue-500 font-medium text-white',
      )}
    >
      {initials || '?'}
    </div>
  );
};

export default MemberPicker;
