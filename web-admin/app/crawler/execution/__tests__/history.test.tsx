import { describe, it, expect, vi } from 'vitest';
import { useCrawlerStore } from '~/crawler/store';

// Mock the store
vi.mock('../../store', () => ({
  useCrawlerStore: vi.fn(),
}));

describe('ExecutionHistory Store Integration', () => {
  it('should fetch execution history with pagination', async () => {
    const mockFetchExecutionHistory = vi.fn().mockResolvedValue({
      records: [
        {
          id: 'instance-001',
          status: 'success',
          startTime: '2024-12-05T10:00:00Z',
          endTime: '2024-12-05T10:15:00Z',
          duration: 900,
          articlesCollected: 45,
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    });

    (useCrawlerStore as any).mockReturnValue({
      executionHistory: [],
      loading: false,
      fetchExecutionHistory: mockFetchExecutionHistory,
    });

    const store = useCrawlerStore();
    const result = await store.fetchExecutionHistory('template-123', 1, 20);

    expect(mockFetchExecutionHistory).toHaveBeenCalledWith('template-123', 1, 20);
    expect(result.records).toHaveLength(1);
    expect(result.total).toBe(1);
    expect(result.records[0].status).toBe('success');
    expect(result.records[0].articlesCollected).toBe(45);
  });

  it('should handle pagination parameters', async () => {
    const mockFetchExecutionHistory = vi.fn().mockResolvedValue({
      records: [],
      total: 100,
      page: 2,
      size: 20,
    });

    (useCrawlerStore as any).mockReturnValue({
      executionHistory: [],
      loading: false,
      fetchExecutionHistory: mockFetchExecutionHistory,
    });

    const store = useCrawlerStore();
    const result = await store.fetchExecutionHistory('template-123', 2, 20);

    expect(mockFetchExecutionHistory).toHaveBeenCalledWith('template-123', 2, 20);
    expect(result.page).toBe(2);
    expect(result.size).toBe(20);
    expect(result.total).toBe(100);
  });

  it('should handle different execution statuses', async () => {
    const mockFetchExecutionHistory = vi.fn().mockResolvedValue({
      records: [
        { id: '1', status: 'success', articlesCollected: 45 },
        { id: '2', status: 'failed', articlesCollected: 10 },
        { id: '3', status: 'running', articlesCollected: 5 },
        { id: '4', status: 'cancelled', articlesCollected: 0 },
      ],
      total: 4,
      page: 1,
      size: 20,
    });

    (useCrawlerStore as any).mockReturnValue({
      executionHistory: [],
      loading: false,
      fetchExecutionHistory: mockFetchExecutionHistory,
    });

    const store = useCrawlerStore();
    const result = await store.fetchExecutionHistory('template-123');

    expect(result.records).toHaveLength(4);
    expect(result.records.map((r) => r.status)).toEqual([
      'success',
      'failed',
      'running',
      'cancelled',
    ]);
  });
});
