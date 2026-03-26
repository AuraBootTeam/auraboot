import { describe, it, expect, vi, beforeEach, type Mocked } from 'vitest';
import { render } from '@testing-library/react';
import { screen, waitFor } from '@testing-library/dom';
import { BrowserRouter } from 'react-router';
import TaskExecutionMonitor from '~/crawler/execution/monitor';
import axios from 'axios';

// Mock axios
vi.mock('axios');
const mockedAxios = axios as Mocked<typeof axios>;

// Mock useParams
vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router');
  return {
    ...actual,
    useParams: () => ({ instanceId: 'test-instance-123' }),
    useNavigate: () => vi.fn(),
  };
});

describe('TaskExecutionMonitor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should render loading state initially', () => {
    mockedAxios.get.mockImplementation(() => new Promise(() => {}));

    render(
      <BrowserRouter>
        <TaskExecutionMonitor />
      </BrowserRouter>,
    );

    expect(
      screen.getByText((content: string, element: Element | null) => {
        return (
          (element instanceof HTMLElement && element.className.includes('loading-spinner')) || false
        );
      }),
    ).toBeInTheDocument();
  });

  it('should render task instance details', async () => {
    const mockInstance = {
      id: 'test-instance-123',
      templateId: 'template-456',
      tenantId: 1,
      site: 'xueqiu',
      status: 'running',
      workerId: 'worker-001',
      startTime: '2024-12-05T10:00:00Z',
      progress: {
        urlsProcessed: 5,
        articlesCollected: 10,
        currentUrl: 'https://test.com/page1',
      },
      createdAt: '2024-12-05T09:59:00Z',
    };

    mockedAxios.get.mockImplementation((url: string) => {
      if (url.includes('/instances/')) {
        return Promise.resolve({ data: mockInstance });
      }
      if (url.includes('/articles')) {
        return Promise.resolve({ data: { records: [] } });
      }
      return Promise.reject(new Error('Unknown URL'));
    });

    render(
      <BrowserRouter>
        <TaskExecutionMonitor />
      </BrowserRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('任务执行监控')).toBeInTheDocument();
    });

    expect(
      screen.getByText((content: string) => content.includes('test-instance-12')),
    ).toBeInTheDocument();
    expect(screen.getByText('运行中')).toBeInTheDocument();
  });

  it('should render error state when fetch fails', async () => {
    mockedAxios.get.mockRejectedValue({
      response: { data: { message: '获取任务详情失败' } },
    });

    render(
      <BrowserRouter>
        <TaskExecutionMonitor />
      </BrowserRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('获取任务详情失败')).toBeInTheDocument();
    });
  });

  it('should display cancel button for running tasks', async () => {
    const mockInstance = {
      id: 'test-instance-123',
      status: 'running',
      site: 'xueqiu',
      createdAt: '2024-12-05T09:59:00Z',
    };

    mockedAxios.get.mockImplementation((url: string) => {
      if (url.includes('/instances/')) {
        return Promise.resolve({ data: mockInstance });
      }
      if (url.includes('/articles')) {
        return Promise.resolve({ data: { records: [] } });
      }
      return Promise.reject(new Error('Unknown URL'));
    });

    render(
      <BrowserRouter>
        <TaskExecutionMonitor />
      </BrowserRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('取消任务')).toBeInTheDocument();
    });
  });
});
