import React, { useState, useEffect, useRef, useCallback } from 'react';
import { User, Search, X, Loader2, UserX } from 'lucide-react';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { getAvatarColor, getInitials } from './avatar-utils';

interface UserOption {
  id: string;
  name: string;
  email?: string;
  avatar?: string;
  department?: string;
  role?: string;
}

interface TenantMemberSearchRecord {
  displayName?: string;
  user?: {
    pid?: string;
    username?: string | null;
    email?: string | null;
    realName?: string | null;
    avatar?: string | null;
  };
  userId?: string | number | null;
}

interface UserSelectProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: string | string[];
  onChange?: (value: string | string[] | undefined) => void;
  disabled?: boolean;
  required?: boolean;
  multiple?: boolean;
  allowClear?: boolean;
  className?: string;
}

export const UserSelect: React.FC<UserSelectProps> = ({
  name,
  label,
  placeholder = '请选择用户',
  value,
  onChange,
  disabled = false,
  required = false,
  multiple = false,
  allowClear = true,
  className = '',
}) => {
  const st = useSmartText();
  const {
    labelText,
    placeholderText,
    required: requiredValue,
    disabled: disabledValue,
  } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
  });
  const meta = useSmartFieldMeta({ externalError: undefined });
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [users, setUsers] = useState<UserOption[]>([]);
  const [loading, setLoading] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Close on outside click
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [isOpen]);

  // Focus search input when dropdown opens
  useEffect(() => {
    if (isOpen) {
      requestAnimationFrame(() => searchInputRef.current?.focus());
    }
  }, [isOpen]);

  useEffect(() => {
    let cancelled = false;

    const loadUsers = async () => {
      setLoading(true);
      try {
        const result = await post<{
          records?: TenantMemberSearchRecord[];
          content?: TenantMemberSearchRecord[];
        }>('/api/tenant/members/search', {
          pageNum: 1,
          pageSize: 50,
          status: 'active',
          ...(searchQuery.trim() ? { keyword: searchQuery.trim() } : {}),
        });

        if (!ResultHelper.isSuccess(result)) {
          throw new Error(result.message || result.desc || 'Failed to load users');
        }

        const records = Array.isArray(result.data)
          ? result.data
          : result.data?.records || result.data?.content || [];

        if (cancelled) return;

        setUsers(
          records
            .map((record) => {
              const userPid = String(record.user?.pid || '');
              if (!userPid) return null;
              return {
                id: userPid,
                name:
                  record.displayName ||
                  record.user?.realName ||
                  record.user?.username ||
                  record.user?.email ||
                  String(record.userId || userPid),
                email: record.user?.email || undefined,
                avatar: record.user?.avatar || undefined,
              } satisfies UserOption;
            })
            .filter((item): item is NonNullable<typeof item> => item !== null) as UserOption[],
        );
      } catch {
        if (!cancelled) {
          setUsers([]);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    loadUsers();

    return () => {
      cancelled = true;
    };
  }, [searchQuery]);

  const selectedUsers = React.useMemo(() => {
    if (!value) return [];
    const selectedIds = Array.isArray(value) ? value : [value];
    return users.filter((user) => selectedIds.includes(user.id));
  }, [users, value]);

  const handleUserSelect = useCallback(
    (user: UserOption) => {
      if (multiple) {
        const currentValue = Array.isArray(value) ? value : value ? [value] : [];
        const newValue = currentValue.includes(user.id)
          ? currentValue.filter((id) => id !== user.id)
          : [...currentValue, user.id];
        onChange?.(newValue.length > 0 ? newValue : undefined);
      } else {
        onChange?.(user.id);
        setIsOpen(false);
      }
      meta.markTouched();
    },
    [multiple, value, onChange, meta],
  );

  const handleRemoveUser = useCallback(
    (userId: string, event: React.MouseEvent) => {
      event.stopPropagation();
      if (multiple) {
        const currentValue = Array.isArray(value) ? value : [];
        const newValue = currentValue.filter((id) => id !== userId);
        onChange?.(newValue.length > 0 ? newValue : undefined);
      } else {
        onChange?.(undefined);
      }
      meta.markTouched();
    },
    [multiple, value, onChange, meta],
  );

  const handleClear = useCallback(
    (event: React.MouseEvent) => {
      event.stopPropagation();
      onChange?.(undefined);
      meta.markTouched();
    },
    [onChange, meta],
  );

  const isSelected = useCallback(
    (userId: string) => {
      if (!value) return false;
      return Array.isArray(value) ? value.includes(userId) : value === userId;
    },
    [value],
  );

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? st(meta.meta.error) : undefined}
      className={`relative space-y-2 ${className}`}
    >
      <div ref={dropdownRef} className="relative">
        {/* Trigger */}
        <div
          data-testid={`user-select-trigger-${name}`}
          className={`rounded-card min-h-[38px] w-full border px-3 py-1.5 shadow-sm transition-all ${
            disabledValue
              ? 'border-border bg-subtle cursor-not-allowed'
              : isOpen
                ? 'border-accent bg-panel cursor-pointer ring-2 ring-blue-100'
                : 'border-border-strong bg-panel hover:border-border-strong cursor-pointer'
          }`}
          onClick={() => !disabledValue && setIsOpen(!isOpen)}
        >
          <div className="flex items-center justify-between gap-2">
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
              {selectedUsers.length > 0 ? (
                selectedUsers.map((user) => (
                  <span
                    key={user.id}
                    className="rounded-pill bg-hover text-text inline-flex items-center gap-1.5 py-0.5 pr-1.5 pl-0.5 text-sm transition-colors hover:bg-gray-200"
                  >
                    <span
                      className={`rounded-pill flex h-5 w-5 flex-shrink-0 items-center justify-center text-[10px] font-semibold text-white ${getAvatarColor(user.name)}`}
                    >
                      {getInitials(user.name)}
                    </span>
                    <span className="max-w-[120px] truncate">{user.name}</span>
                    {!disabledValue && (
                      <button
                        type="button"
                        onClick={(e) => handleRemoveUser(user.id, e)}
                        className="rounded-pill text-text-3 hover:text-text-2 flex h-4 w-4 items-center justify-center transition-colors hover:bg-gray-300"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    )}
                  </span>
                ))
              ) : (
                <span className="text-text-3 py-0.5 text-sm">{placeholderText}</span>
              )}
            </div>
            <div className="flex flex-shrink-0 items-center gap-1">
              {selectedUsers.length > 0 && allowClear && !disabledValue && (
                <button
                  type="button"
                  onClick={handleClear}
                  className="rounded-pill text-text-3 hover:bg-hover hover:text-text-2 flex h-5 w-5 items-center justify-center transition-colors"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              )}
              <User className="text-text-3 h-4 w-4" />
            </div>
          </div>
        </div>

        {/* Dropdown */}
        {isOpen && !disabledValue && (
          <div className="rounded-card border-border bg-panel absolute z-50 mt-1.5 w-full overflow-hidden border shadow-lg">
            {/* Search Input */}
            <div className="border-border border-b p-2">
              <div className="relative">
                <Search className="text-text-3 absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2" />
                <input
                  ref={searchInputRef}
                  type="text"
                  data-testid={`user-select-search-${name}`}
                  placeholder={st('搜索用户...')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="rounded-control border-border bg-subtle focus:bg-panel w-full border py-2 pr-3 pl-9 text-sm transition-colors focus:border-blue-400 focus:ring-1 focus:ring-blue-100 focus:outline-none"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>
            </div>

            {/* User List */}
            <div className="max-h-60 overflow-y-auto py-1">
              {loading ? (
                <div className="text-text-3 flex flex-col items-center justify-center py-8">
                  <Loader2 className="h-6 w-6 animate-spin text-blue-500" />
                  <span className="mt-2 text-sm">{st('加载中...')}</span>
                </div>
              ) : users.length === 0 ? (
                <div className="text-text-3 flex flex-col items-center justify-center py-8">
                  <UserX className="text-text-3 h-8 w-8" />
                  <span className="mt-2 text-sm">{st('没有找到匹配的用户')}</span>
                </div>
              ) : (
                users.map((user) => {
                  const selected = isSelected(user.id);
                  return (
                    <div
                      key={user.id}
                      data-testid={`user-select-option-${name}-${user.id}`}
                      className={`rounded-control mx-1 cursor-pointer px-2 py-2 transition-colors ${
                        selected ? 'bg-accent-weak text-blue-900' : 'text-text-2 hover:bg-subtle'
                      }`}
                      onClick={() => handleUserSelect(user)}
                    >
                      <div className="flex items-center gap-3">
                        <span
                          className={`rounded-pill flex h-8 w-8 flex-shrink-0 items-center justify-center text-xs font-semibold text-white ${getAvatarColor(user.name)}`}
                        >
                          {getInitials(user.name)}
                        </span>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="truncate text-sm font-medium">{user.name}</span>
                            {selected && (
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
                          {user.email && (
                            <div className="text-text-3 truncate text-xs">{user.email}</div>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        )}
      </div>

      <input
        type="hidden"
        name={name}
        value={Array.isArray(value) ? value.join(',') : value || ''}
      />
    </FieldBase>
  );
};

export default UserSelect;
