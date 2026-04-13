import React, { useState, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '~/ui/ui/dialog';
import { useI18n } from '~/contexts/I18nContext';
import type { Role } from './types';

interface RoleFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  role: Role | null;
  onSave: (data: {
    code: string;
    name: string;
    description: string;
    type: string;
  }) => Promise<void>;
}

const ROLE_TYPES = ['custom', 'system', 'tenant'] as const;

export default function RoleFormDialog({ open, role, onOpenChange, onSave }: RoleFormDialogProps) {
  const { t } = useI18n();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState('custom');
  const [saving, setSaving] = useState(false);

  const isEdit = role !== null;

  useEffect(() => {
    if (open) {
      if (role) {
        setCode(role.code);
        setName(role.name);
        setDescription(role.description || '');
        setType(role.type || 'custom');
      } else {
        setCode('');
        setName('');
        setDescription('');
        setType('custom');
      }
    }
  }, [open, role]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await onSave({ code, name, description, type });
    } catch {
      // Error is handled by the parent — don't close dialog on failure
      return;
    } finally {
      setSaving(false);
    }
  };

  const inputClass =
    'block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100';
  const labelClass = 'block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="role-form-dialog">
        <DialogHeader>
          <DialogTitle>
            {isEdit
              ? t('admin.permission.role.edit') || 'Edit Role'
              : t('admin.permission.role.create') || 'Create Role'}
          </DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="role-code" className={labelClass}>
              {t('admin.permission.role.code') || 'Code'}
            </label>
            <input
              id="role-code"
              data-testid="role-form-code"
              type="text"
              required
              disabled={isEdit}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className={`${inputClass} ${isEdit ? 'cursor-not-allowed bg-gray-100 dark:bg-gray-700' : ''}`}
            />
          </div>

          <div>
            <label htmlFor="role-name" className={labelClass}>
              {t('admin.permission.role.name') || 'Name'}
            </label>
            <input
              id="role-name"
              data-testid="role-form-name"
              type="text"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={inputClass}
            />
          </div>

          <div>
            <label htmlFor="role-description" className={labelClass}>
              {t('admin.permission.role.description') || 'Description'}
            </label>
            <textarea
              id="role-description"
              data-testid="role-form-description"
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className={inputClass}
            />
          </div>

          <div>
            <label htmlFor="role-type" className={labelClass}>
              {t('admin.permission.role.type') || 'Type'}
            </label>
            <select
              id="role-type"
              data-testid="role-form-type"
              value={type}
              onChange={(e) => setType(e.target.value)}
              className={inputClass}
            >
              {ROLE_TYPES.map((rt) => (
                <option key={rt} value={rt}>
                  {t(`admin.permission.role.type.${rt.toLowerCase()}`) || rt}
                </option>
              ))}
            </select>
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              data-testid="role-form-cancel"
              onClick={() => onOpenChange(false)}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-800"
            >
              {t('common.cancel') || 'Cancel'}
            </button>
            <button
              type="submit"
              data-testid="role-form-submit"
              disabled={saving}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving
                ? t('common.saving') || 'Saving...'
                : t('common.confirm') || (isEdit ? 'Update' : 'Create')}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
