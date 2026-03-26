/**
 * Webhook configuration manager
 */

import { useState, useEffect } from 'react';
import { Button } from '~/components/ui/button';
import { Input } from '~/components/ui/input';
import { RefreshCw } from 'lucide-react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  listWebhooks,
  createWebhook,
  deleteWebhook,
  type WebhookConfig,
} from '../services/bpmWebhookService';
import { confirmDialog } from '~/utils/confirmDialog';

export function WebhookManager() {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [webhooks, setWebhooks] = useState<WebhookConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<WebhookConfig>({
    name: '',
    url: '',
    eventTypes: [],
    enabled: true,
  });

  useEffect(() => {
    loadWebhooks();
  }, []);

  const loadWebhooks = async () => {
    setLoading(true);
    try {
      setWebhooks(await listWebhooks());
    } catch (error) {
      console.error('Failed to load webhooks:', error);
      showErrorToast('Failed to load webhooks');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async () => {
    try {
      await createWebhook(formData);
      setShowForm(false);
      setFormData({ name: '', url: '', eventTypes: [], enabled: true });
      showSuccessToast('Webhook created');
      loadWebhooks();
    } catch (error) {
      console.error('Failed to create webhook:', error);
      showErrorToast('Failed to create webhook');
    }
  };

  const handleDelete = async (pid: string) => {
    if (!(await confirmDialog({ content: 'Delete this webhook?', variant: 'danger' }))) return;
    try {
      await deleteWebhook(pid);
      showSuccessToast('Webhook deleted');
      loadWebhooks();
    } catch (error) {
      console.error('Failed to delete webhook:', error);
      showErrorToast('Failed to delete webhook');
    }
  };

  return (
    <div className="p-4">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-semibold">Webhooks</h3>
        <Button size="sm" onClick={() => setShowForm(!showForm)}>
          {showForm ? 'Cancel' : 'Add Webhook'}
        </Button>
      </div>

      {showForm && (
        <div className="mb-4 space-y-3 rounded-lg bg-gray-50 p-4">
          <Input
            type="text"
            placeholder="Name"
            value={formData.name}
            onChange={(e) => setFormData((prev) => ({ ...prev, name: e.target.value }))}
          />
          <Input
            type="text"
            placeholder="url"
            value={formData.url}
            onChange={(e) => setFormData((prev) => ({ ...prev, url: e.target.value }))}
          />
          <Input
            type="text"
            placeholder="Secret (optional)"
            value={formData.secret || ''}
            onChange={(e) => setFormData((prev) => ({ ...prev, secret: e.target.value }))}
          />
          <Input
            type="text"
            placeholder="Event types (comma separated)"
            value={formData.eventTypes.join(',')}
            onChange={(e) =>
              setFormData((prev) => ({
                ...prev,
                eventTypes: e.target.value.split(',').filter(Boolean),
              }))
            }
          />
          <Button onClick={handleCreate}>Save</Button>
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-4 text-gray-500">
          <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
          Loading...
        </div>
      ) : webhooks.length === 0 ? (
        <div className="py-4 text-center text-gray-500">No webhooks configured</div>
      ) : (
        <div className="space-y-2">
          {webhooks.map((wh) => (
            <div
              key={wh.pid}
              className="flex items-center justify-between rounded-lg border border-gray-200 bg-white p-3"
            >
              <div>
                <p className="text-sm font-medium">{wh.name}</p>
                <p className="text-xs text-gray-500">{wh.url}</p>
                <p className="text-xs text-gray-400">Events: {wh.eventTypes?.join(', ')}</p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleDelete(wh.pid!)}
                className="text-xs text-red-500 hover:text-red-700"
              >
                Delete
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
