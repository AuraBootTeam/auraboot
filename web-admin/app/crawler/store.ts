import { create } from 'zustand';
import axios from 'axios';

const API_BASE = '/api/crawler';

export interface Task {
  id: string;
  name: string;
  description?: string;
  site: string;
  config: any;
  enabled: boolean;
  createdAt: string;
}

export interface Article {
  id: number;
  source: string;
  stock?: string;
  url: string;
  title: string;
  author?: string;
  contentText: string;
  publishTime?: string;
  createdAt: string;
}

export interface TaskInstance {
  id: string;
  templateId: string;
  tenantId: number;
  site: string;
  status: 'pending' | 'running' | 'success' | 'failed' | 'cancelled';
  workerId?: string;
  startTime?: string;
  endTime?: string;
  progress?: {
    urlsProcessed: number;
    articlesCollected: number;
    currentUrl?: string;
    lastUpdateTime?: string;
  };
  result?: any;
  error?: {
    message: string;
    stackTrace?: string;
    failedUrls?: string[];
  };
  createdAt: string;
  duration?: number;
}

export interface ExecutionHistory {
  id: string;
  status: string;
  startTime: string;
  endTime?: string;
  duration?: number;
  articlesCollected: number;
}

interface CrawlerStore {
  tasks: Task[];
  articles: Article[];
  currentInstance: TaskInstance | null;
  executionHistory: ExecutionHistory[];
  loading: boolean;

  // Task actions
  fetchTasks: () => Promise<void>;
  createTask: (data: any) => Promise<void>;
  executeTask: (templateId: string) => Promise<string>;

  // Execution monitoring actions
  fetchTaskInstance: (instanceId: string) => Promise<void>;
  fetchTaskArticles: (instanceId: string) => Promise<Article[]>;
  cancelTask: (instanceId: string) => Promise<void>;
  fetchExecutionHistory: (
    templateId: string,
    page?: number,
    size?: number,
  ) => Promise<{
    records: ExecutionHistory[];
    total: number;
    page: number;
    size: number;
  }>;

  // Article actions
  fetchArticles: (params?: { source?: string; stock?: string }) => Promise<void>;
}

export const useCrawlerStore = create<CrawlerStore>((set) => ({
  tasks: [],
  articles: [],
  currentInstance: null,
  executionHistory: [],
  loading: false,

  fetchTasks: async () => {
    set({ loading: true });
    try {
      const response = await axios.get(`${API_BASE}/tasks/templates`);
      set({ tasks: response.data.records || [] });
    } finally {
      set({ loading: false });
    }
  },

  createTask: async (data) => {
    await axios.post(`${API_BASE}/tasks/templates`, data);
  },

  executeTask: async (templateId) => {
    const response = await axios.post(`${API_BASE}/tasks/templates/${templateId}/execute`);
    // Return the instance ID from the response
    return response.data.id;
  },

  fetchTaskInstance: async (instanceId) => {
    const response = await axios.get(`${API_BASE}/tasks/instances/${instanceId}`);
    set({ currentInstance: response.data });
  },

  fetchTaskArticles: async (instanceId) => {
    const response = await axios.get(`${API_BASE}/tasks/instances/${instanceId}/articles`, {
      params: { page: 1, size: 100 },
    });
    return response.data.records || [];
  },

  cancelTask: async (instanceId) => {
    await axios.post(`${API_BASE}/tasks/instances/${instanceId}/cancel`);
  },

  fetchExecutionHistory: async (templateId, page = 1, size = 20) => {
    set({ loading: true });
    try {
      const response = await axios.get(`${API_BASE}/tasks/templates/${templateId}/instances`, {
        params: { page, size },
      });
      set({ executionHistory: response.data.records || [] });
      return {
        records: response.data.records || [],
        total: response.data.total || 0,
        page: response.data.page || page,
        size: response.data.pageSize || size,
      };
    } finally {
      set({ loading: false });
    }
  },

  fetchArticles: async (params = {}) => {
    set({ loading: true });
    try {
      const response = await axios.get(`${API_BASE}/articles`, { params });
      set({ articles: response.data.records || [] });
    } finally {
      set({ loading: false });
    }
  },
}));
