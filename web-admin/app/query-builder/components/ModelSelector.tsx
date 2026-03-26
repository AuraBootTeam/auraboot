/**
 * ModelSelector — Select a model to query
 */

import { useState, useEffect, useCallback } from 'react';
import { queryBuilderService, type ModelInfo } from '../services/queryBuilderService';
import { ResultHelper } from '~/utils/type';

interface ModelSelectorProps {
  value?: string;
  onChange: (modelCode: string) => void;
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({ value, onChange }) => {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);

  const loadModels = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await queryBuilderService.getModels(search || undefined);
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setModels(resp.data);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [search]);

  useEffect(() => {
    loadModels();
  }, [loadModels]);

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium text-gray-700">Model</h3>
      <input
        type="text"
        placeholder="Search models..."
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
        data-testid="qb-model-search"
      />
      <div className="max-h-48 overflow-y-auto rounded-md border border-gray-200">
        {loading && <div className="px-3 py-2 text-sm text-gray-500">Loading...</div>}
        {models.map((m) => (
          <button
            key={m.code}
            type="button"
            onClick={() => onChange(m.code)}
            data-testid={`qb-model-${m.code}`}
            className={`w-full px-3 py-2 text-left text-sm hover:bg-blue-50 ${
              value === m.code ? 'bg-blue-100 font-medium text-blue-800' : 'text-gray-700'
            }`}
          >
            <div className="font-medium">{m.name || m.code}</div>
            <div className="text-xs text-gray-500">{m.code}</div>
          </button>
        ))}
        {!loading && models.length === 0 && (
          <div className="px-3 py-2 text-sm text-gray-500">No models found</div>
        )}
      </div>
    </div>
  );
};
