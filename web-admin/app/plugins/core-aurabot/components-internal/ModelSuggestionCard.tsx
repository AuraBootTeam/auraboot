/**
 * ModelSuggestionCard
 *
 * Renders model suggestion results from builtin__model_suggest tool
 * inline in AuraBot chat — model info, field table, and create action.
 *
 * @since 3.2.0
 */

import { useNavigate } from 'react-router';

interface FieldSuggestion {
  fieldCode: string;
  fieldName: string;
  dataType: string;
  required: boolean;
  description?: string;
}

interface ModelSuggestion {
  modelCode: string;
  modelName: string;
  description: string;
  fields: FieldSuggestion[];
  suggestedViews: string[];
}

interface ModelSuggestionCardProps {
  suggestion: ModelSuggestion;
}

const DATA_TYPE_COLORS: Record<string, string> = {
  STRING: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
  INTEGER: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  DECIMAL: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  DATETIME: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400',
  DATE: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400',
  ENUM: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
  TEXT: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300',
  BOOLEAN: 'bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-400',
  REFERENCE: 'bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-400',
};

function getTypeColor(dataType: string): string {
  return (
    DATA_TYPE_COLORS[dataType] || 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
  );
}

export function ModelSuggestionCard({ suggestion }: ModelSuggestionCardProps) {
  const navigate = useNavigate();
  const { modelCode, modelName, description, fields = [], suggestedViews = [] } = suggestion;

  const handleCreate = () => {
    navigate(
      `/meta/models/new?code=${encodeURIComponent(modelCode)}&name=${encodeURIComponent(modelName)}`,
    );
  };

  return (
    <div className="mb-3 flex justify-start" data-testid="model-suggestion-card">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-indigo-200 bg-white shadow-sm dark:border-indigo-700 dark:bg-gray-800">
        {/* Header — gradient bar */}
        <div className="flex items-center gap-2 border-b border-indigo-100 bg-gradient-to-r from-blue-50 to-indigo-50 px-3 py-2.5 dark:border-indigo-800 dark:from-blue-900/20 dark:to-indigo-900/20">
          <svg
            className="h-4 w-4 flex-shrink-0 text-indigo-500"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <rect x="3" y="3" width="18" height="18" rx="2" />
            <path d="M3 9h18" />
            <path d="M9 21V9" />
          </svg>
          <span className="text-sm font-medium text-indigo-700 dark:text-indigo-300">
            {modelName}
          </span>
          <span className="ml-auto rounded bg-indigo-100 px-1.5 py-0.5 font-mono text-[10px] text-indigo-500 dark:bg-indigo-800 dark:text-indigo-400">
            {modelCode}
          </span>
        </div>

        {/* Description */}
        {description && (
          <div className="border-b border-gray-100 px-3 py-2 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-300">
            {description}
          </div>
        )}

        {/* Field table */}
        {fields.length > 0 && (
          <div className="overflow-x-auto border-b border-gray-100 dark:border-gray-700">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-gray-50 text-gray-500 dark:bg-gray-700/50 dark:text-gray-400">
                  <th className="px-3 py-1.5 text-left font-medium">Field</th>
                  <th className="px-3 py-1.5 text-left font-medium">Name</th>
                  <th className="px-3 py-1.5 text-left font-medium">Type</th>
                  <th className="px-3 py-1.5 text-center font-medium">Req</th>
                </tr>
              </thead>
              <tbody>
                {fields.map((field) => (
                  <tr
                    key={field.fieldCode}
                    className="border-t border-gray-50 hover:bg-gray-50/50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  >
                    <td className="px-3 py-1.5 font-mono text-gray-600 dark:text-gray-400">
                      {field.fieldCode}
                    </td>
                    <td className="px-3 py-1.5 text-gray-700 dark:text-gray-300">
                      {field.fieldName}
                    </td>
                    <td className="px-3 py-1.5">
                      <span
                        className={`inline-block rounded px-1.5 py-0.5 text-[10px] font-medium ${getTypeColor(field.dataType)}`}
                      >
                        {field.dataType}
                      </span>
                    </td>
                    <td className="px-3 py-1.5 text-center">
                      {field.required ? (
                        <span className="text-green-500">&#10003;</span>
                      ) : (
                        <span className="text-gray-300 dark:text-gray-600">&#10005;</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Suggested views + Create button */}
        <div className="flex items-center justify-between gap-2 px-3 py-2.5">
          {suggestedViews.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-[10px] tracking-wide text-gray-400 uppercase dark:text-gray-500">
                Views:
              </span>
              {suggestedViews.map((view) => (
                <span
                  key={view}
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500 dark:bg-gray-700 dark:text-gray-400"
                >
                  {view}
                </span>
              ))}
            </div>
          )}
          <button
            onClick={handleCreate}
            className="ml-auto flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-indigo-700"
          >
            <svg
              className="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path d="M12 5v14M5 12h14" />
            </svg>
            Create This Model
          </button>
        </div>
      </div>
    </div>
  );
}

export default ModelSuggestionCard;
