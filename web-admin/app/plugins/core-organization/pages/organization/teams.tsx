import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { PlusIcon, PencilIcon, TrashIcon, UserGroupIcon } from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import {
  fetchTeams,
  createTeam,
  updateTeam,
  deleteTeam,
  type Team,
  type TeamCreateRequest,
  type TeamUpdateRequest,
} from '~/shared/services/teamService';
import { useI18n } from '~/contexts/I18nContext';

const STATUS_CONFIG: Record<string, { className: string }> = {
  active: { className: 'bg-green-100 text-green-800' },
  inactive: { className: 'bg-gray-100 text-gray-800' },
  archived: { className: 'bg-yellow-100 text-yellow-800' },
};

export default function TeamsPage() {
  const navigate = useNavigate();
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const l = useCallback(
    (zhCN: string, enUS: string) => (locale === 'zh-CN' ? zhCN : enUS),
    [locale],
  );
  const [teams, setTeams] = useState<Team[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editingTeam, setEditingTeam] = useState<Team | null>(null);

  const loadTeams = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchTeams();
      setTeams(data);
    } catch (e: any) {
      showErrorToast(e.message || l('加载团队失败', 'Failed to load teams'));
    } finally {
      setLoading(false);
    }
  }, [showErrorToast, l]);

  useEffect(() => {
    loadTeams();
  }, [loadTeams]);

  const handleCreate = () => {
    setEditingTeam(null);
    setShowModal(true);
  };

  const handleEdit = (team: Team) => {
    setEditingTeam(team);
    setShowModal(true);
  };

  const handleDelete = async (team: Team) => {
    if (
      !confirm(
        l(
          `确认删除团队「${team.name}」吗？`,
          `Are you sure you want to delete team "${team.name}"?`,
        ),
      )
    )
      return;
    try {
      await deleteTeam(team.pid);
      showSuccessToast(l('团队删除成功', 'Team deleted successfully'));
      loadTeams();
    } catch (e: any) {
      showErrorToast(e.message || l('删除团队失败', 'Failed to delete team'));
    }
  };

  const handleSubmit = async (data: TeamCreateRequest | TeamUpdateRequest) => {
    try {
      if (editingTeam) {
        await updateTeam(editingTeam.pid, data as TeamUpdateRequest);
        showSuccessToast(l('团队更新成功', 'Team updated successfully'));
      } else {
        await createTeam(data as TeamCreateRequest);
        showSuccessToast(l('团队创建成功', 'Team created successfully'));
      }
      setShowModal(false);
      loadTeams();
    } catch (e: any) {
      showErrorToast(e.message || l('保存团队失败', 'Failed to save team'));
    }
  };

  const handleViewMembers = (team: Team) => {
    navigate(`/organization/teams/${team.pid}`);
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
          {l('团队管理', 'Team Management')}
        </h1>
        <button
          onClick={handleCreate}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          data-testid="create-team-btn"
        >
          <PlusIcon className="h-5 w-5" />
          {l('新建团队', 'Create Team')}
        </button>
      </div>

      {loading ? (
        <div className="flex justify-center py-12">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
        </div>
      ) : teams.length === 0 ? (
        <div className="py-12 text-center text-gray-500 dark:text-gray-400">
          <UserGroupIcon className="mx-auto mb-3 h-12 w-12 opacity-50" />
          <p>
            {l(
              '暂无团队，创建第一个团队开始使用。',
              'No teams yet. Create your first team to get started.',
            )}
          </p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow dark:bg-gray-800">
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('团队', 'Team')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('编码', 'Code')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('负责人', 'Leader')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('成员数', 'Members')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('状态', 'Status')}
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('操作', 'Actions')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {teams.map((team) => {
                const statusCfg = STATUS_CONFIG[team.status] || STATUS_CONFIG.ACTIVE;
                return (
                  <tr key={team.pid} className="dark:hover:bg-gray-750 hover:bg-gray-50">
                    <td className="px-6 py-4">
                      <div className="text-sm font-medium text-gray-900 dark:text-white">
                        {team.name}
                      </div>
                      {team.description && (
                        <div className="max-w-xs truncate text-sm text-gray-500 dark:text-gray-400">
                          {team.description}
                        </div>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
                      {team.code}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
                      {team.leaderName || '-'}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
                      {team.memberCount}
                    </td>
                    <td className="px-6 py-4">
                      <span
                        className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${statusCfg.className}`}
                      >
                        {team.status === 'active'
                          ? l('启用', 'Active')
                          : team.status === 'inactive'
                            ? l('停用', 'Inactive')
                            : l('归档', 'Archived')}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <button
                          onClick={() => handleViewMembers(team)}
                          className="rounded p-1.5 text-gray-400 hover:text-blue-600"
                          title={l('管理成员', 'Manage members')}
                          data-testid={`team-members-${team.code}`}
                        >
                          <UserGroupIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleEdit(team)}
                          className="rounded p-1.5 text-gray-400 hover:text-blue-600"
                          title={l('编辑', 'Edit')}
                          data-testid={`team-edit-${team.code}`}
                        >
                          <PencilIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleDelete(team)}
                          className="rounded p-1.5 text-gray-400 hover:text-red-600"
                          title={l('删除', 'Delete')}
                          data-testid={`team-delete-${team.code}`}
                        >
                          <TrashIcon className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showModal && (
        <TeamModal
          team={editingTeam}
          locale={locale}
          onClose={() => setShowModal(false)}
          onSubmit={handleSubmit}
        />
      )}
    </div>
  );
}

function TeamModal({
  team,
  locale,
  onClose,
  onSubmit,
}: {
  team: Team | null;
  locale: string;
  onClose: () => void;
  onSubmit: (data: TeamCreateRequest | TeamUpdateRequest) => void;
}) {
  const l = (zhCN: string, enUS: string) => (locale === 'zh-CN' ? zhCN : enUS);
  const [code, setCode] = useState(team?.code || '');
  const [name, setName] = useState(team?.name || '');
  const [description, setDescription] = useState(team?.description || '');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (team) {
      onSubmit({ name, description } as TeamUpdateRequest);
    } else {
      onSubmit({ code, name, description } as TeamCreateRequest);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="mx-4 w-full max-w-md rounded-lg bg-white shadow-xl dark:bg-gray-800">
        <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            {team ? l('编辑团队', 'Edit Team') : l('新建团队', 'Create Team')}
          </h3>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          {!team && (
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {l('编码', 'Code')} <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                placeholder={l('例如：engineering', 'e.g. engineering')}
                data-testid="team-code-input"
              />
            </div>
          )}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('名称', 'Name')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder={l('例如：工程团队', 'e.g. Engineering Team')}
              data-testid="team-name-input"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('描述', 'Description')}
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder={l('可选的团队描述', 'Optional team description')}
              data-testid="team-desc-input"
            />
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg bg-gray-100 px-4 py-2 text-gray-700 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            >
              {l('取消', 'Cancel')}
            </button>
            <button
              type="submit"
              className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
              data-testid="team-save-btn"
            >
              {team ? l('保存', 'Save') : l('创建', 'Create')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
