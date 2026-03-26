import { useState, useCallback } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * Field change detail
 */
export interface FieldChange {
  field: string;
  fieldLabel?: string;
  oldValue: unknown;
  newValue: unknown;
}

/**
 * Data Change Log entity
 */
export interface DataChangeLog {
  id: number;
  modelCode: string;
  recordId: string;
  operation: 'create' | 'update' | 'delete';
  changedBy: number;
  changedAt: string;
  commandCode: string | null;
  clientRequestId: string | null;
  changes: string | FieldChange[]; // JSON string or parsed array
  snapshotBefore: string | null;
  snapshotAfter: string | null;
}

/**
 * Query parameters
 */
export interface ChangeLogQueryParams {
  pageNum: number;
  pageSize: number;
  modelCode?: string;
  operation?: string;
}

/**
 * Event Stream DTO
 */
export interface EventStream {
  aggregateType: string;
  aggregateId: string;
  currentVersion: number;
  totalEvents: number;
  events: EventEntry[];
}

export interface EventEntry {
  eventId: string;
  eventType: string;
  version: number;
  payload: Record<string, unknown>;
  metadata: Record<string, unknown>;
  occurredAt: string | null;
}

/**
 * Hook for managing audit logs
 */
export function useAuditLog() {
  const [changeLogs, setChangeLogs] = useState<DataChangeLog[]>([]);
  const [myChanges, setMyChanges] = useState<DataChangeLog[]>([]);
  const [total, setTotal] = useState(0);
  const [eventStream, setEventStream] = useState<EventStream | null>(null);
  const [loading, setLoading] = useState(false);
  const [eventLoading, setEventLoading] = useState(false);
  const { showErrorToast } = useToastContext();

  /**
   * Get change history for a specific record
   */
  const getRecordHistory = useCallback(
    async (modelCode: string, recordId: string) => {
      setLoading(true);
      try {
        const result = await fetchResult('/api/meta/change-logs/history', {
          method: 'get',
          params: { modelCode, recordId },
        });

        if (ResultHelper.isSuccess(result) || Array.isArray(result)) {
          const data = Array.isArray(result) ? result : (result.data as DataChangeLog[]) || [];
          setChangeLogs(data);
          return data;
        } else {
          showErrorToast(result.desc || 'Failed to load change history');
          return [];
        }
      } catch (error) {
        console.error('Failed to fetch change history:', error);
        showErrorToast('Failed to load change history');
        return [];
      } finally {
        setLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Get current user's changes with pagination
   */
  const getMyChanges = useCallback(
    async (params: ChangeLogQueryParams) => {
      setLoading(true);
      try {
        const result = await fetchResult('/api/meta/change-logs/my', {
          method: 'get',
          params: {
            pageNum: params.pageNum.toString(),
            pageSize: params.pageSize.toString(),
            ...(params.modelCode ? { modelCode: params.modelCode } : {}),
            ...(params.operation ? { operation: params.operation } : {}),
          },
        });

        if (ResultHelper.isSuccess(result)) {
          const data = result.data as { records: DataChangeLog[]; total: number } | DataChangeLog[];
          if (Array.isArray(data)) {
            setMyChanges(data);
            setTotal(data.length);
          } else {
            setMyChanges(data?.records || []);
            setTotal(data?.total || 0);
          }
        } else {
          showErrorToast(result.desc || 'Failed to load my changes');
        }
      } catch (error) {
        console.error('Failed to fetch my changes:', error);
        showErrorToast('Failed to load my changes');
      } finally {
        setLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Get a single change log entry
   */
  const getChangeLogById = useCallback(
    async (id: number): Promise<DataChangeLog | null> => {
      try {
        const result = await fetchResult(`/api/meta/change-logs/${id}`, {
          method: 'get',
        });

        const direct = result as unknown as DataChangeLog;
        const data = result.data ?? (direct && typeof direct.id === 'number' ? direct : null);
        if (ResultHelper.isSuccess(result) || data) {
          return data as DataChangeLog;
        } else {
          showErrorToast(result.desc || 'Failed to load change log');
          return null;
        }
      } catch (error) {
        console.error('Failed to fetch change log:', error);
        showErrorToast('Failed to load change log');
        return null;
      }
    },
    [showErrorToast],
  );

  /**
   * Get event stream for an aggregate
   */
  const getEventStream = useCallback(
    async (aggregateType: string, aggregateId: string, page: number = 1, size: number = 20) => {
      setEventLoading(true);
      try {
        const result = await fetchResult(`/api/meta/event-store/${aggregateType}/${aggregateId}`, {
          method: 'get',
          params: { page: page.toString(), size: size.toString() },
        });

        if (ResultHelper.isSuccess(result)) {
          const data = result.data as EventStream;
          setEventStream(data);
          return data;
        } else {
          showErrorToast(result.desc || 'Failed to load event stream');
          return null;
        }
      } catch (error) {
        console.error('Failed to fetch event stream:', error);
        showErrorToast('Failed to load event stream');
        return null;
      } finally {
        setEventLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Replay aggregate state
   */
  const replayAggregate = useCallback(
    async (aggregateType: string, aggregateId: string): Promise<Record<string, unknown> | null> => {
      try {
        const result = await fetchResult(
          `/api/meta/event-store/${aggregateType}/${aggregateId}/replay`,
          {
            method: 'get',
          },
        );

        if (ResultHelper.isSuccess(result)) {
          return result.data as Record<string, unknown>;
        } else {
          showErrorToast(result.desc || 'Failed to replay aggregate');
          return null;
        }
      } catch (error) {
        console.error('Failed to replay aggregate:', error);
        showErrorToast('Failed to replay aggregate');
        return null;
      }
    },
    [showErrorToast],
  );

  /**
   * Parse changes JSON string to array
   */
  const parseChanges = useCallback((changes: string | FieldChange[]): FieldChange[] => {
    if (Array.isArray(changes)) {
      return changes;
    }
    try {
      return JSON.parse(changes) as FieldChange[];
    } catch {
      return [];
    }
  }, []);

  return {
    changeLogs,
    myChanges,
    total,
    eventStream,
    loading,
    eventLoading,
    getRecordHistory,
    getMyChanges,
    getChangeLogById,
    getEventStream,
    replayAggregate,
    parseChanges,
  };
}
