/**
 * Infrastructure Status — Admin page
 *
 * Shows current Storage, MQ, Redis, and Database provider status.
 * Allows connection testing for each infrastructure component.
 */

import { useState, useEffect, useCallback } from 'react';
import {
  ServerStackIcon,
  CircleStackIcon,
  SignalIcon,
  CloudIcon,
  CheckCircleIcon,
  XCircleIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline';
import { get, post } from '~/shared/services/http-client';
import { useToastContext } from '~/contexts/ToastContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface InfraStatus {
  storage: { type: string; provider: string; tenantIsolated: boolean };
  mq: { type: string; provider: string };
  redis: { connected: boolean; version?: string; error?: string };
  database: { url: string };
}

interface TestResult {
  status: 'ok' | 'error';
  error?: string;
  [key: string]: any;
}

const PROVIDER_DESCRIPTIONS: Record<string, string> = {
  // Storage
  LocalStorageProvider: 'Local filesystem storage — for development only',
  MinioStorageProvider: 'MinIO S3-compatible object storage',
  S3StorageProvider: 'AWS S3 object storage',
  OssStorageProvider: 'Alibaba Cloud OSS',
  // MQ
  InMemoryMqProvider: 'In-memory — for development only. Messages not persisted',
  RedisMqProvider: 'Redis Streams — production-grade, uses existing Redis',
  KafkaMqProvider: 'Apache Kafka — for high-throughput workloads',
  RabbitMqProvider: 'RabbitMQ — for complex routing scenarios',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function InfrastructurePage() {
  const toast = useToastContext();
  const [status, setStatus] = useState<InfraStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState<Record<string, boolean>>({});
  const [testResults, setTestResults] = useState<Record<string, TestResult>>({});

  const fetchStatus = useCallback(async () => {
    try {
      const res = await get<InfraStatus>('/api/admin/infrastructure/status');
      setStatus(res?.data ?? null);
    } catch {
      toast.showErrorToast('Failed to load infrastructure status');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  const runTest = async (component: string) => {
    setTesting((prev) => ({ ...prev, [component]: true }));
    try {
      const res = await post<TestResult>(`/api/admin/infrastructure/test/${component}`);
      const result = res?.data;
      setTestResults((prev) => ({ ...prev, [component]: result as TestResult }));
      if (result?.status === 'ok') {
        toast.showSuccessToast(`${component} connection test passed`);
      } else {
        toast.showErrorToast(`${component} test failed: ${result?.error}`);
      }
    } catch {
      setTestResults((prev) => ({
        ...prev,
        [component]: { status: 'error', error: 'Request failed' },
      }));
      toast.showErrorToast(`${component} test failed`);
    } finally {
      setTesting((prev) => ({ ...prev, [component]: false }));
    }
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!status) {
    return <div className="p-6 text-gray-500">Failed to load infrastructure status</div>;
  }

  return (
    <div className="max-w-4xl p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Infrastructure</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          Current provider status and connection testing
        </p>
      </div>

      <div className="space-y-4">
        {/* Storage */}
        <InfraCard
          icon={<CloudIcon className="h-6 w-6" />}
          title="File Storage"
          provider={status.storage.provider}
          type={status.storage.type}
          description={PROVIDER_DESCRIPTIONS[status.storage.provider]}
          details={[
            { label: 'Type', value: status.storage.type },
            { label: 'Tenant Isolated', value: status.storage.tenantIsolated ? 'Yes' : 'No' },
          ]}
          testResult={testResults.storage}
          testing={testing.storage}
          onTest={() => runTest('storage')}
          isProduction={status.storage.type !== 'local'}
        />

        {/* MQ */}
        <InfraCard
          icon={<SignalIcon className="h-6 w-6" />}
          title="Message Queue"
          provider={status.mq.provider}
          type={status.mq.type}
          description={PROVIDER_DESCRIPTIONS[status.mq.provider]}
          details={[{ label: 'Type', value: status.mq.type }]}
          testResult={testResults.mq}
          testing={testing.mq}
          onTest={() => runTest('mq')}
          isProduction={status.mq.type !== 'memory'}
        />

        {/* Redis */}
        <InfraCard
          icon={<ServerStackIcon className="h-6 w-6" />}
          title="Redis"
          provider={status.redis.connected ? 'Connected' : 'Disconnected'}
          type="redis"
          description={
            status.redis.version ? `Redis ${status.redis.version}` : status.redis.error || 'Unknown'
          }
          details={[
            { label: 'Status', value: status.redis.connected ? 'Connected' : 'Disconnected' },
            ...(status.redis.version ? [{ label: 'Version', value: status.redis.version }] : []),
          ]}
          testResult={testResults.redis}
          testing={testing.redis}
          onTest={() => runTest('redis')}
          isProduction={status.redis.connected}
        />

        {/* Database */}
        <InfraCard
          icon={<CircleStackIcon className="h-6 w-6" />}
          title="Database"
          provider="PostgreSQL"
          type="postgresql"
          description={status.database.url || 'Configured'}
          details={[{ label: 'Connection', value: status.database.url || 'N/A' }]}
          isProduction={true}
        />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// InfraCard
// ---------------------------------------------------------------------------

function InfraCard({
  icon,
  title,
  provider,
  type,
  description,
  details,
  testResult,
  testing,
  onTest,
  isProduction,
}: {
  icon: React.ReactNode;
  title: string;
  provider: string;
  type: string;
  description?: string;
  details: { label: string; value: string }[];
  testResult?: TestResult;
  testing?: boolean;
  onTest?: () => void;
  isProduction?: boolean;
}) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div
            className={`rounded-lg p-2 ${
              isProduction
                ? 'bg-green-50 text-green-600 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-yellow-50 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-400'
            }`}
          >
            {icon}
          </div>
          <div>
            <h3 className="font-semibold text-gray-900 dark:text-white">{title}</h3>
            <div className="mt-0.5 flex items-center gap-2">
              <span className="font-mono text-sm text-gray-600 dark:text-gray-400">{provider}</span>
              <span
                className={`rounded px-1.5 py-0.5 text-xs ${
                  isProduction
                    ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                    : 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
                }`}
              >
                {isProduction ? 'Production' : 'Dev Only'}
              </span>
            </div>
          </div>
        </div>

        {onTest && (
          <button
            onClick={onTest}
            disabled={testing}
            className="flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:hover:bg-gray-700"
          >
            {testing ? (
              <ArrowPathIcon className="h-4 w-4 animate-spin" />
            ) : testResult?.status === 'ok' ? (
              <CheckCircleIcon className="h-4 w-4 text-green-500" />
            ) : testResult?.status === 'error' ? (
              <XCircleIcon className="h-4 w-4 text-red-500" />
            ) : null}
            Test
          </button>
        )}
      </div>

      {description && (
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">{description}</p>
      )}

      <div className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
        {details.map((d) => (
          <span key={d.label}>
            <span className="font-medium">{d.label}:</span> {d.value}
          </span>
        ))}
      </div>

      {testResult?.status === 'error' && (
        <div className="mt-2 rounded bg-red-50 px-3 py-1.5 text-xs text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {testResult.error}
        </div>
      )}
    </div>
  );
}
