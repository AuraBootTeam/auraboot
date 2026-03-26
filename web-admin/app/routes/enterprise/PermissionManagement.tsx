import { useState, useCallback } from 'react';
import { useSearchParams } from 'react-router';
import { ShieldCheckIcon, UserGroupIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import RoleTab from './permission/RoleTab';
import AssignmentTab from './permission/AssignmentTab';
import PermissionTab from './permission/PermissionTab';
import type { Role } from './permission/types';

type TabKey = 'roles' | 'assignments' | 'permissions';

export default function PermissionManagement() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { t } = useI18n();

  const activeTab = (searchParams.get('tab') as TabKey) || 'roles';
  const [preSelectedRole, setPreSelectedRole] = useState<Role | null>(null);

  const switchTab = useCallback(
    (tab: TabKey) => {
      setSearchParams((prev) => ({ ...Object.fromEntries(prev), tab }));
    },
    [setSearchParams],
  );

  const handleAssignPermissions = useCallback(
    (role: Role) => {
      setPreSelectedRole(role);
      switchTab('assignments');
    },
    [switchTab],
  );

  const tabs: { key: TabKey; label: string; icon: typeof ShieldCheckIcon }[] = [
    { key: 'roles', label: t('admin.permission.tab.roles') || 'Roles', icon: UserGroupIcon },
    {
      key: 'assignments',
      label: t('admin.permission.tab.assignments') || 'Assignments',
      icon: ShieldCheckIcon,
    },
  ];

  return (
    <div className="p-6" data-testid="permission-page">
      {/* Header */}
      <div className="mb-6">
        <div className="flex items-center">
          <ShieldCheckIcon className="mr-3 h-8 w-8 text-blue-600" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
              {t('admin.permission.title') || 'Permission Management'}
            </h1>
            <p className="text-gray-600 dark:text-gray-400">
              {t('admin.permission.description') || 'Manage permissions, roles and assignments'}
            </p>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200 dark:border-gray-700">
        <nav className="-mb-px flex space-x-8">
          {tabs.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              data-testid={`permission-tab-${key}`}
              onClick={() => switchTab(key)}
              className={`flex items-center border-b-2 px-1 py-2 text-sm font-medium transition-colors ${
                activeTab === key
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              <Icon className="mr-2 h-5 w-5" />
              {label}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab content */}
      {activeTab === 'roles' && <RoleTab onAssignPermissions={handleAssignPermissions} />}

      {activeTab === 'assignments' && <AssignmentTab preSelectedRole={preSelectedRole} />}

      {activeTab === 'permissions' && <PermissionTab />}
    </div>
  );
}
