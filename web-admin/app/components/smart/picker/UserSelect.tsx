import React, { useState, useEffect } from 'react';
import { User, Search, X } from 'lucide-react';
import { post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { FieldActionButton } from '~/components/ui/field-action-button';

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

  const handleUserSelect = (user: UserOption) => {
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
  };

  const handleRemoveUser = (userId: string, event: React.MouseEvent) => {
    event.stopPropagation();
    if (multiple) {
      const currentValue = Array.isArray(value) ? value : [];
      const newValue = currentValue.filter((id) => id !== userId);
      onChange?.(newValue.length > 0 ? newValue : undefined);
    } else {
      onChange?.(undefined);
    }
    meta.markTouched();
  };

  const handleClear = (event: React.MouseEvent) => {
    event.stopPropagation();
    onChange?.(undefined);
    meta.markTouched();
  };

  const isSelected = (userId: string) => {
    if (!value) return false;
    return Array.isArray(value) ? value.includes(userId) : value === userId;
  };

  const displayText = () => {
    if (selectedUsers.length === 0) return placeholderText;
    if (selectedUsers.length === 1) return selectedUsers[0].name;
    return st(`已选择 ${selectedUsers.length} 个用户`);
  };

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? st(meta.meta.error) : undefined}
      className={`relative space-y-2 ${className}`}
    >
      <div className="relative">
        <div
          data-testid={`user-select-trigger-${name}`}
          className={`w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm ${disabledValue ? 'cursor-not-allowed bg-gray-50' : 'cursor-pointer bg-white hover:border-gray-400'} ${selectedUsers.length > 0 ? 'text-gray-900' : 'text-gray-500'} focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none`}
          onClick={() => !disabledValue && setIsOpen(!isOpen)}
        >
          <div className="flex items-center justify-between">
            <div className="flex min-w-0 flex-1 items-center space-x-2">
              <User className="h-4 w-4 flex-shrink-0 text-gray-400" />
              {multiple && selectedUsers.length > 0 ? (
                <div className="flex flex-1 flex-wrap gap-1">
                  {selectedUsers.map((user) => (
                    <span
                      key={user.id}
                      className="inline-flex items-center rounded-full bg-blue-100 px-2 py-1 text-xs text-blue-800"
                    >
                      {user.name}
                      {!disabledValue && (
                        <FieldActionButton
                          type="button"
                          onClick={(e) => handleRemoveUser(user.id, e)}
                          iconOnly
                          className="ml-1 text-blue-600 hover:text-blue-800 dark:hover:text-blue-400"
                        >
                          <X className="h-3 w-3" />
                        </FieldActionButton>
                      )}
                    </span>
                  ))}
                </div>
              ) : (
                <span className="truncate">{displayText()}</span>
              )}
            </div>
            {selectedUsers.length > 0 && allowClear && !disabledValue && (
              <FieldActionButton type="button" onClick={handleClear} iconOnly>
                <X className="h-4 w-4" />
              </FieldActionButton>
            )}
          </div>
        </div>

        {/* Dropdown */}
        {isOpen && !disabledValue && (
          <div className="absolute z-50 mt-1 w-full rounded-md border border-gray-300 bg-white shadow-lg">
            {/* Search Input */}
            <div className="border-b p-2">
              <div className="relative">
                <Search className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 transform text-gray-400" />
                <input
                  type="text"
                  data-testid={`user-select-search-${name}`}
                  placeholder={st('搜索用户...')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full rounded-md border border-gray-300 py-2 pr-4 pl-10 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>
            </div>

            {/* User List */}
            <div className="max-h-60 overflow-y-auto">
              {loading ? (
                <div className="p-4 text-center text-gray-500">
                  <div className="mx-auto h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600"></div>
                  <span className="mt-2 block">{st('加载中...')}</span>
                </div>
              ) : users.length === 0 ? (
                <div className="p-4 text-center text-gray-500">{st('没有找到匹配的用户')}</div>
              ) : (
                users.map((user) => (
                  <div
                    key={user.id}
                    data-testid={`user-select-option-${name}-${user.id}`}
                    className={`cursor-pointer border-b border-gray-100 p-3 last:border-b-0 hover:bg-gray-50 ${isSelected(user.id) ? 'bg-blue-50 text-blue-900' : 'text-gray-900'} `}
                    onClick={() => handleUserSelect(user)}
                  >
                    <div className="flex items-center space-x-3">
                      <div className="flex-shrink-0">
                        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-gray-300">
                          <User className="h-4 w-4 text-gray-600" />
                        </div>
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center space-x-2">
                          <span className="font-medium">{user.name}</span>
                          {isSelected(user.id) && <span className="text-blue-600">✓</span>}
                        </div>
                        {user.email && <div className="text-sm text-gray-500">{user.email}</div>}
                        {user.department && user.role && (
                          <div className="text-sm text-gray-500">
                            {user.department} · {user.role}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                ))
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
