/**
 * Plugin Management Page
 *
 * Two-tab layout:
 *   1. Upload — drag-and-drop .abp file upload for air-gapped environments
 *   2. Marketplace — iframe embed that communicates via postMessage
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  ArrowUpTrayIcon,
  GlobeAltIcon,
  CheckCircleIcon,
  XCircleIcon,
  ArrowPathIcon,
  CloudArrowUpIcon,
} from '@heroicons/react/24/outline';
import { Button } from '~/ui/ui/button';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type TabKey = 'upload' | 'marketplace';

interface UploadResult {
  success: boolean;
  importId?: string;
  pluginId?: string;
  pluginName?: string;
  version?: string;
  error?: string;
}

// ---------------------------------------------------------------------------
// PluginUpload Tab
// ---------------------------------------------------------------------------

function PluginUploadTab() {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [result, setResult] = useState<UploadResult | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleUpload = async (file: File) => {
    if (!file.name.endsWith('.abp')) {
      showErrorToast('Only .abp files are accepted');
      return;
    }

    setUploading(true);
    setResult(null);
    try {
      const formData = new FormData();
      formData.append('file', file);

      const res = await fetch('/api/plugins/upload-package', {
        method: 'POST',
        body: formData,
      });
      const json = await res.json();

      if (res.ok && json.code === '0') {
        const data = json.data ?? {};
        const uploadResult: UploadResult = {
          success: true,
          importId: data.importId,
          pluginId: data.pluginId,
          pluginName: data.pluginName ?? data.displayName ?? data.pluginId,
          version: data.version,
        };
        setResult(uploadResult);
        showSuccessToast(
          `Plugin "${uploadResult.pluginName}" (v${uploadResult.version ?? '?'}) uploaded successfully`,
        );
      } else {
        const errorMsg = json.desc || json.message || 'Upload failed';
        setResult({ success: false, error: errorMsg });
        showErrorToast(errorMsg);
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Network error';
      setResult({ success: false, error: errorMsg });
      showErrorToast(`Upload failed: ${errorMsg}`);
    } finally {
      setUploading(false);
      // Reset file input so the same file can be re-selected
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) handleUpload(file);
  };

  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  };

  const onDragLeave = () => setDragOver(false);

  return (
    <div className="mx-auto max-w-2xl">
      {/* Drop zone */}
      <div
        onDrop={onDrop}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        className={`rounded-xl border-2 border-dashed p-12 text-center transition-colors ${dragOver ? 'border-blue-500 bg-blue-50' : 'border-gray-300 bg-white hover:border-gray-400'} `}
      >
        <CloudArrowUpIcon
          className={`mx-auto h-16 w-16 ${dragOver ? 'text-blue-500' : 'text-gray-400'}`}
        />
        <p className="mt-4 text-lg font-medium text-gray-700">
          Drag &amp; drop your{' '}
          <code className="rounded bg-gray-100 px-1.5 py-0.5 text-sm">.abp</code> file here
        </p>
        <p className="mt-1 text-sm text-gray-500">or click the button below to browse</p>

        <input
          ref={fileInputRef}
          type="file"
          accept=".abp"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) handleUpload(file);
          }}
        />

        <Button
          variant="outline"
          className="mt-6"
          disabled={uploading}
          onClick={() => fileInputRef.current?.click()}
        >
          {uploading ? (
            <>
              <ArrowPathIcon className="mr-2 h-4 w-4 animate-spin" />
              Uploading...
            </>
          ) : (
            <>
              <ArrowUpTrayIcon className="mr-2 h-4 w-4" />
              Select File
            </>
          )}
        </Button>
      </div>

      {/* Result */}
      {result && (
        <div
          className={`mt-6 rounded-lg border p-4 ${
            result.success ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
          }`}
        >
          <div className="flex items-start gap-3">
            {result.success ? (
              <CheckCircleIcon className="mt-0.5 h-6 w-6 flex-shrink-0 text-green-600" />
            ) : (
              <XCircleIcon className="mt-0.5 h-6 w-6 flex-shrink-0 text-red-600" />
            )}
            <div>
              {result.success ? (
                <>
                  <p className="font-medium text-green-800">Plugin installed successfully</p>
                  <div className="mt-1 space-y-0.5 text-sm text-green-700">
                    <p>Name: {result.pluginName}</p>
                    {result.version && <p>Version: {result.version}</p>}
                    {result.importId && (
                      <p className="font-mono text-xs text-green-600">
                        Import ID: {result.importId}
                      </p>
                    )}
                  </div>
                </>
              ) : (
                <>
                  <p className="font-medium text-red-800">Upload failed</p>
                  <p className="mt-1 text-sm text-red-700">{result.error}</p>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Help text */}
      <div className="mt-8 space-y-1 text-sm text-gray-500">
        <p className="font-medium text-gray-600">About .abp packages</p>
        <p>
          AuraBoot Plugin packages (<code>.abp</code>) are used to install plugins in air-gapped
          environments without marketplace connectivity. Obtain packages from your plugin vendor or
          export them from another AuraBoot instance.
        </p>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// MarketplaceEmbed Tab
// ---------------------------------------------------------------------------

function MarketplaceEmbedTab() {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [marketplaceUrl, setMarketplaceUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [installing, setInstalling] = useState(false);

  // Fetch marketplace URL from system config
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch('/api/system/config/marketplace.url');
        if (res.ok) {
          const json = await res.json();
          const url = json.data ?? json.value ?? null;
          setMarketplaceUrl(typeof url === 'string' && url.length > 0 ? url : null);
        }
      } catch {
        // Config not available — marketplace not configured
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  // Install from marketplace via token
  const handleInstallFromMarket = useCallback(
    async (installToken: string) => {
      setInstalling(true);
      try {
        const res = await fetch('/api/marketplace/install-from-market', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ installToken }),
        });
        const json = await res.json();
        const success = res.ok && json.code === '0';

        // Notify iframe of result
        if (iframeRef.current?.contentWindow && marketplaceUrl) {
          iframeRef.current.contentWindow.postMessage(
            {
              type: 'auraboot:install-result',
              payload: {
                success,
                installToken,
                error: success ? undefined : json.desc || json.message,
              },
            },
            marketplaceUrl,
          );
        }

        if (success) {
          showSuccessToast('Plugin installed from marketplace');
        } else {
          showErrorToast(json.desc || json.message || 'Installation failed');
        }
      } catch (err) {
        showErrorToast('Failed to install from marketplace');
      } finally {
        setInstalling(false);
      }
    },
    [marketplaceUrl, showSuccessToast, showErrorToast],
  );

  // Listen for postMessage from marketplace iframe
  useEffect(() => {
    if (!marketplaceUrl) return;

    const origin = new URL(marketplaceUrl).origin;

    const handler = (event: MessageEvent) => {
      if (event.origin !== origin) return;
      const { type, payload } = event.data ?? {};

      if (type === 'marketplace:install-request' && payload?.installToken) {
        handleInstallFromMarket(payload.installToken);
      }
      // Future: handle marketplace:navigate etc.
    };

    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [marketplaceUrl, handleInstallFromMarket]);

  // Send init message when iframe loads
  const handleIframeLoad = () => {
    if (!iframeRef.current?.contentWindow || !marketplaceUrl) return;
    iframeRef.current.contentWindow.postMessage(
      {
        type: 'auraboot:init',
        payload: {
          instanceUrl: window.location.origin,
          platformVersion: '1.0.0',
          locale: navigator.language || 'en',
          theme: 'light',
        },
      },
      marketplaceUrl,
    );
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <ArrowPathIcon className="h-6 w-6 animate-spin text-gray-400" />
        <span className="ml-2 text-sm text-gray-500">Loading marketplace configuration...</span>
      </div>
    );
  }

  if (!marketplaceUrl) {
    return (
      <div className="flex flex-col items-center justify-center py-24 text-center">
        <GlobeAltIcon className="h-16 w-16 text-gray-300" />
        <h3 className="mt-4 text-lg font-medium text-gray-700">Marketplace not configured</h3>
        <p className="mt-2 max-w-md text-sm text-gray-500">
          No marketplace URL has been configured for this instance. Contact your administrator to
          set the <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs">marketplace.url</code>{' '}
          system configuration, or use the <strong>Upload</strong> tab to install plugins from .abp
          files.
        </p>
      </div>
    );
  }

  return (
    <div className="relative w-full" style={{ height: 'calc(100vh - 220px)' }}>
      {installing && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-white/70">
          <div className="flex items-center gap-3 rounded-lg border bg-white px-6 py-4 shadow-lg">
            <ArrowPathIcon className="h-5 w-5 animate-spin text-blue-600" />
            <span className="text-sm font-medium text-gray-700">Installing plugin...</span>
          </div>
        </div>
      )}
      <iframe
        ref={iframeRef}
        src={marketplaceUrl}
        onLoad={handleIframeLoad}
        className="h-full w-full rounded-lg border-0"
        sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
        title="AuraBoot Marketplace"
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

const TABS: { key: TabKey; label: string; icon: typeof ArrowUpTrayIcon }[] = [
  { key: 'upload', label: 'Upload', icon: ArrowUpTrayIcon },
  { key: 'marketplace', label: 'Marketplace', icon: GlobeAltIcon },
];

export default function PluginManagementPage() {
  const [activeTab, setActiveTab] = useState<TabKey>('upload');

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Plugin Management</h1>
        <p className="mt-1 text-sm text-gray-500">
          Install plugins via file upload or browse the marketplace
        </p>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200">
        <nav className="-mb-px flex gap-6">
          {TABS.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              onClick={() => setActiveTab(key)}
              className={`flex items-center gap-2 border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeTab === key
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              <Icon className="h-4 w-4" />
              {label}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab content */}
      {activeTab === 'upload' ? <PluginUploadTab /> : <MarketplaceEmbedTab />}
    </div>
  );
}
