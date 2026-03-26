import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import ProjectWorkspace from './components/ProjectWorkspace';

interface ProjectData {
  id: string;
  pid: string;
  pm_project_name: string;
  pm_project_code: string;
  pm_project_status: string;
  pm_project_description: string;
  pm_project_start_date: string;
  pm_project_end_date: string;
  pm_project_owner: string;
  pm_project_priority: string;
  [key: string]: unknown;
}

export default function ProjectDetailRoute() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { locale } = useI18n();
  const { showErrorToast } = useToastContext();
  const showErrorToastRef = useRef(showErrorToast);
  showErrorToastRef.current = showErrorToast;

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [projectData, setProjectData] = useState<ProjectData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadProject = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await get<ProjectData>(`/api/dynamic/pm-project/${projectId}`);
      if (ResultHelper.isSuccess(result) && result.data) {
        setProjectData(result.data);
      } else {
        const msg = result.message || l('项目加载失败', 'Failed to load project');
        setError(msg);
        showErrorToastRef.current(msg);
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('项目加载失败', 'Failed to load project');
      setError(msg);
      showErrorToastRef.current(msg);
    } finally {
      setLoading(false);
    }
  }, [projectId, l]);

  useEffect(() => {
    loadProject();
  }, [loadProject]);

  if (loading) {
    return (
      <div className="flex min-h-[400px] items-center justify-center" data-testid="project-loading">
        <div className="flex flex-col items-center gap-3">
          <div className="h-10 w-10 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
          <span className="text-sm text-gray-500 dark:text-gray-400">
            {l('加载中...', 'Loading...')}
          </span>
        </div>
      </div>
    );
  }

  if (error || !projectData) {
    return (
      <div
        className="flex min-h-[400px] flex-col items-center justify-center gap-4"
        data-testid="project-error"
      >
        <div className="text-center">
          <div className="mb-2 text-4xl">!</div>
          <h2 className="mb-1 text-lg font-semibold text-gray-900 dark:text-white">
            {l('项目未找到', 'Project Not Found')}
          </h2>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {error || l('无法加载项目数据', 'Unable to load project data')}
          </p>
        </div>
        <button
          onClick={() => navigate(-1)}
          className="rounded-lg bg-gray-100 px-4 py-2 text-sm text-gray-700 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          data-testid="project-back-btn"
        >
          {l('返回', 'Go Back')}
        </button>
      </div>
    );
  }

  return (
    <ProjectWorkspace
      projectId={projectId!}
      projectData={projectData}
      onProjectUpdate={loadProject}
    />
  );
}
