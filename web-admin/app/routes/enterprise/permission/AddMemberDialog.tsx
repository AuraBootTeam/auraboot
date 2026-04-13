/**
 * AddMemberDialog — Dialog for adding members to a role.
 *
 * Two modes via tab switch:
 * 1. Organization Structure — Uses OrgTreePicker with department tree
 * 2. Member List — Flat searchable table of all tenant members
 */

import { useState, useEffect, useCallback } from 'react';
import {
  MagnifyingGlassIcon,
  BuildingOffice2Icon,
  UserGroupIcon,
  UserPlusIcon,
} from '@heroicons/react/24/outline';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '~/ui/ui/dialog';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import OrgTreePicker from '~/ui/shared/OrgTreePicker';
import { permissionService } from '~/shared/services/permissionService';
import type { RoleMemberDTO } from './types';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface AddMemberDialogProps {
  open: boolean;
  onClose: () => void;
  rolePid: string;
  existingMemberPids: string[];
  onSuccess: () => void;
}

type TabKey = 'org' | 'list';

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function AddMemberDialog({
  open,
  onClose,
  rolePid,
  existingMemberPids,
  onSuccess,
}: AddMemberDialogProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();

  const [activeTab, setActiveTab] = useState<TabKey>('org');
  const [submitting, setSubmitting] = useState(false);

  // Org tree mode
  const [orgSelectedPids, setOrgSelectedPids] = useState<string[]>([]);

  // List mode
  const [candidates, setCandidates] = useState<RoleMemberDTO[]>([]);
  const [candidatesLoading, setCandidatesLoading] = useState(false);
  const [listKeyword, setListKeyword] = useState('');
  const [listSelectedPids, setListSelectedPids] = useState<Set<string>>(new Set());

  // ---------------------------------------------------------------------------
  // Reset state when dialog opens
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (open) {
      setActiveTab('org');
      setOrgSelectedPids([]);
      setListSelectedPids(new Set());
      setListKeyword('');
      setCandidates([]);
    }
  }, [open]);

  // ---------------------------------------------------------------------------
  // Load candidates for list mode
  // ---------------------------------------------------------------------------

  const loadCandidates = useCallback(async () => {
    setCandidatesLoading(true);
    try {
      const data = await permissionService.getRoleMemberCandidates(
        rolePid,
        listKeyword || undefined,
      );
      setCandidates(data || []);
    } catch {
      setCandidates([]);
    } finally {
      setCandidatesLoading(false);
    }
  }, [rolePid, listKeyword]);

  // Load candidates when switching to list tab or keyword changes
  useEffect(() => {
    if (!open || activeTab !== 'list') return;
    const timer = setTimeout(() => loadCandidates(), 300);
    return () => clearTimeout(timer);
  }, [open, activeTab, listKeyword]); // eslint-disable-line react-hooks/exhaustive-deps

  // ---------------------------------------------------------------------------
  // List mode selection handlers
  // ---------------------------------------------------------------------------

  const toggleListSelect = (memberPid: string) => {
    setListSelectedPids((prev) => {
      const next = new Set(prev);
      if (next.has(memberPid)) next.delete(memberPid);
      else next.add(memberPid);
      return next;
    });
  };

  const toggleListAll = () => {
    const selectable = candidates.filter(
      (c) => !existingMemberPids.includes(c.memberPid),
    );
    if (listSelectedPids.size === selectable.length && selectable.length > 0) {
      setListSelectedPids(new Set());
    } else {
      setListSelectedPids(new Set(selectable.map((c) => c.memberPid)));
    }
  };

  // ---------------------------------------------------------------------------
  // Submit
  // ---------------------------------------------------------------------------

  const handleConfirm = async () => {
    let memberPids: string[] = [];

    if (activeTab === 'org') {
      if (orgSelectedPids.length === 0) return;
      memberPids = orgSelectedPids;
    } else {
      memberPids = Array.from(listSelectedPids);
      if (memberPids.length === 0) return;
    }

    setSubmitting(true);
    try {
      await permissionService.addRoleMembers(rolePid, memberPids);
      showSuccessToast(
        (t('admin.permission.members.addSuccess') || '{count} member(s) added').replace(
          '{count}',
          String(memberPids.length),
        ),
      );
      onSuccess();
      onClose();
    } catch (err: any) {
      showErrorToast(err?.message || t('common.error') || 'Failed');
    } finally {
      setSubmitting(false);
    }
  };

  // ---------------------------------------------------------------------------
  // Selection count
  // ---------------------------------------------------------------------------

  const selectedCount =
    activeTab === 'org' ? orgSelectedPids.length : listSelectedPids.size;

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent
        data-testid="add-member-dialog"
        className="max-w-3xl"
      >
        <DialogHeader>
          <DialogTitle>
            {t('admin.permission.members.addTitle') || 'Add Members'}
          </DialogTitle>
        </DialogHeader>

        {/* Tab switch */}
        <div className="border-b border-gray-200 dark:border-gray-700">
          <nav className="-mb-px flex space-x-4">
            <button
              data-testid="add-member-tab-org"
              onClick={() => setActiveTab('org')}
              className={`flex items-center border-b-2 px-1 py-2 text-sm font-medium transition-colors ${
                activeTab === 'org'
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
              }`}
            >
              <BuildingOffice2Icon className="mr-1.5 h-4 w-4" />
              {t('admin.permission.members.tabOrg') || 'Organization'}
            </button>
            <button
              data-testid="add-member-tab-list"
              onClick={() => setActiveTab('list')}
              className={`flex items-center border-b-2 px-1 py-2 text-sm font-medium transition-colors ${
                activeTab === 'list'
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
              }`}
            >
              <UserGroupIcon className="mr-1.5 h-4 w-4" />
              {t('admin.permission.members.tabList') || 'Member List'}
            </button>
          </nav>
        </div>

        {/* Tab content */}
        <div className="min-h-[340px]">
          {activeTab === 'org' && (
            <OrgTreePicker
              value={orgSelectedPids}
              onChange={setOrgSelectedPids}
              disabledPids={existingMemberPids}
            />
          )}

          {activeTab === 'list' && (
            <div>
              {/* Search */}
              <div className="relative mb-3">
                <MagnifyingGlassIcon className="absolute top-2 left-2.5 h-4 w-4 text-gray-400" />
                <input
                  data-testid="add-member-list-search"
                  type="text"
                  placeholder={
                    t('admin.permission.members.searchMembers') || 'Search members...'
                  }
                  value={listKeyword}
                  onChange={(e) => setListKeyword(e.target.value)}
                  className="w-full rounded-md border border-gray-300 py-1.5 pl-8 pr-3 text-sm placeholder-gray-400 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-200"
                />
              </div>

              {/* Table */}
              <div className="max-h-64 overflow-y-auto rounded-md border border-gray-200 dark:border-gray-600">
                {candidatesLoading ? (
                  <div className="flex items-center justify-center py-8">
                    <LoadingSpinner />
                  </div>
                ) : candidates.length === 0 ? (
                  <div className="py-8 text-center text-sm text-gray-400">
                    {t('admin.permission.members.noCandidates') ||
                      'No available members found'}
                  </div>
                ) : (
                  <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
                    <thead className="bg-gray-50 dark:bg-gray-800">
                      <tr>
                        <th className="px-3 py-2 text-left">
                          <input
                            type="checkbox"
                            checked={
                              listSelectedPids.size > 0 &&
                              listSelectedPids.size ===
                                candidates.filter(
                                  (c) => !existingMemberPids.includes(c.memberPid),
                                ).length
                            }
                            onChange={toggleListAll}
                            className="rounded text-blue-600"
                          />
                        </th>
                        <th className="px-3 py-2 text-left text-xs font-medium uppercase text-gray-500">
                          {t('admin.permission.members.colName') || 'Name'}
                        </th>
                        <th className="px-3 py-2 text-left text-xs font-medium uppercase text-gray-500">
                          {t('admin.permission.members.colEmail') || 'Email'}
                        </th>
                        <th className="px-3 py-2 text-left text-xs font-medium uppercase text-gray-500">
                          {t('admin.permission.members.colDepartment') || 'Department'}
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
                      {candidates.map((c) => {
                        const isExisting = existingMemberPids.includes(c.memberPid);
                        const isSelected = listSelectedPids.has(c.memberPid);
                        return (
                          <tr
                            key={c.memberId}
                            data-testid={`candidate-row-${c.memberId}`}
                            className={`${
                              isExisting
                                ? 'cursor-not-allowed opacity-50'
                                : 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800'
                            }`}
                            onClick={() => !isExisting && toggleListSelect(c.memberPid)}
                          >
                            <td className="px-3 py-2">
                              <input
                                type="checkbox"
                                checked={isExisting || isSelected}
                                disabled={isExisting}
                                onChange={() =>
                                  !isExisting && toggleListSelect(c.memberPid)
                                }
                                className="rounded text-blue-600"
                              />
                            </td>
                            <td className="px-3 py-2 text-sm text-gray-900 dark:text-gray-100">
                              {c.userName}
                            </td>
                            <td className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">
                              {c.email || '-'}
                            </td>
                            <td className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">
                              {c.departmentName || '-'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="mt-3 flex items-center justify-between border-t border-gray-200 pt-3 dark:border-gray-700">
          <span className="text-xs text-gray-500">
            {selectedCount > 0
              ? (t('admin.permission.members.selectedCount') || '{count} selected').replace(
                  '{count}',
                  String(selectedCount),
                )
              : ''}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              data-testid="add-member-cancel"
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-800"
            >
              {t('common.cancel') || 'Cancel'}
            </button>
            <button
              data-testid="add-member-confirm"
              disabled={selectedCount === 0 || submitting}
              onClick={handleConfirm}
              className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <UserPlusIcon className="h-4 w-4" />
              {submitting
                ? t('common.saving') || 'Adding...'
                : t('admin.permission.members.addConfirm') || 'Add Selected'}
            </button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
