import TaskListPage from '~/crawler/tasks/list';
import TaskCreatePage from '~/crawler/tasks/new';
import ArticleListPage from '~/crawler/articles/list';
import TaskExecutionMonitor from '~/crawler/execution/monitor';
import ExecutionHistoryPage from '~/crawler/execution/history';

export const crawlerRoutes = [
  {
    path: '/crawler/tasks',
    element: <TaskListPage />,
  },
  {
    path: '/crawler/tasks/new',
    element: <TaskCreatePage />,
  },
  {
    path: '/crawler/tasks/:instanceId/monitor',
    element: <TaskExecutionMonitor />,
  },
  {
    path: '/crawler/tasks/:templateId/history',
    element: <ExecutionHistoryPage />,
  },
  {
    path: '/crawler/articles',
    element: <ArticleListPage />,
  },
];
