/**
 * AgentToolPicker - Agent Tool multi-select component
 *
 * Fetches available tools from ab_agent_tool and renders a searchable
 * multi-select. The value is stored as a JSON array of tool objects:
 * [{toolCode, toolType}]
 */

import React, { forwardRef, useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { get } from '~/services/http-client/HttpClient';
import { ResultHelper } from '~/utils/type';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { FieldControl } from '~/components/ui/field-control';

interface AgentTool {
  pid: string;
  tool_code: string;
  tool_type: string;
  tool_name: string;
  tool_description: string;
  tool_status: string;
  source_type: string;
}

interface SelectedTool {
  toolCode: string;
  toolType: string;
}

interface AgentToolPickerProps {
  label?: string;
  name: string;
  value?: string;
  placeholder?: string;
  error?: string;
  required?: boolean;
  readOnly?: boolean;
  onChange?: (value: string) => void;
}

export const AgentToolPicker = forwardRef<HTMLDivElement, AgentToolPickerProps>(
  (
    {
      label,
      name,
      value,
      placeholder,
      error: propError,
      required = false,
      readOnly = false,
      onChange,
    },
    ref,
  ) => {
    const st = useSmartText();
    const {
      labelText,
      placeholderText,
      required: requiredValue,
    } = useSmartFieldContract({ label, placeholder, required });

    const [tools, setTools] = useState<AgentTool[]>([]);
    const [search, setSearch] = useState('');
    const [open, setOpen] = useState(false);
    const [loading, setLoading] = useState(false);
    const dropdownRef = useRef<HTMLDivElement>(null);

    // Parse current value
    const selected = useMemo<SelectedTool[]>(() => {
      if (!value) return [];
      try {
        const parsed = typeof value === 'string' ? JSON.parse(value) : value;
        return Array.isArray(parsed) ? parsed : [];
      } catch {
        return [];
      }
    }, [value]);

    const selectedCodes = useMemo(() => new Set(selected.map((s) => s.toolCode)), [selected]);

    // Fetch available tools
    useEffect(() => {
      setLoading(true);
      get<{ records: AgentTool[] }>('/api/datasource/list', {
        datasourceId: 'nq:acp_agent_tools_active',
        format: 'records',
        maxItems: '500',
      })
        .then((res) => {
          if (ResultHelper.isSuccess(res) && res.data?.records) {
            setTools(res.data.records);
          }
        })
        .finally(() => setLoading(false));
    }, []);

    // Close dropdown on outside click
    useEffect(() => {
      const handler = (e: MouseEvent) => {
        if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
          setOpen(false);
        }
      };
      document.addEventListener('mousedown', handler);
      return () => document.removeEventListener('mousedown', handler);
    }, []);

    const toggleTool = useCallback(
      (tool: AgentTool) => {
        if (readOnly) return;
        let newSelected: SelectedTool[];
        if (selectedCodes.has(tool.tool_code)) {
          newSelected = selected.filter((s) => s.toolCode !== tool.tool_code);
        } else {
          newSelected = [...selected, { toolCode: tool.tool_code, toolType: tool.tool_type }];
        }
        onChange?.(JSON.stringify(newSelected));
      },
      [selected, selectedCodes, readOnly, onChange],
    );

    const removeTool = useCallback(
      (toolCode: string) => {
        if (readOnly) return;
        const newSelected = selected.filter((s) => s.toolCode !== toolCode);
        onChange?.(JSON.stringify(newSelected));
      },
      [selected, readOnly, onChange],
    );

    const filtered = useMemo(() => {
      if (!search) return tools;
      const q = search.toLowerCase();
      return tools.filter(
        (t) =>
          t.tool_code.toLowerCase().includes(q) ||
          t.tool_name.toLowerCase().includes(q) ||
          (t.tool_description || '').toLowerCase().includes(q),
      );
    }, [tools, search]);

    const typeColors: Record<string, string> = {
      DSL_COMMAND: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
      DSL_QUERY: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
      CUSTOM_API: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
      MCP_SERVER: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
    };

    return (
      <FieldBase label={labelText} required={requiredValue} error={propError} ref={ref}>
        <FieldControl error={propError}>
          <div className="relative" ref={dropdownRef}>
            {/* Selected tags */}
            <div
              className={`flex min-h-[38px] cursor-pointer flex-wrap gap-1.5 rounded-lg border border-gray-300 bg-white p-2 dark:border-gray-600 dark:bg-gray-800 ${readOnly ? 'cursor-not-allowed opacity-60' : 'hover:border-blue-400'}`}
              onClick={() => !readOnly && setOpen(!open)}
              data-testid={`tool-picker-${name}`}
            >
              {selected.length === 0 && (
                <span className="text-sm text-gray-400">
                  {placeholderText || st('agent.selectTools', 'Select tools...')}
                </span>
              )}
              {selected.map((s) => {
                const tool = tools.find((t) => t.tool_code === s.toolCode);
                return (
                  <span
                    key={s.toolCode}
                    className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs ${typeColors[s.toolType] || 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300'}`}
                  >
                    {tool?.tool_name || s.toolCode}
                    {!readOnly && (
                      <button
                        type="button"
                        className="ml-0.5 hover:text-red-500"
                        onClick={(e) => {
                          e.stopPropagation();
                          removeTool(s.toolCode);
                        }}
                      >
                        ×
                      </button>
                    )}
                  </span>
                );
              })}
              <input type="hidden" name={name} value={value || '[]'} />
            </div>

            {/* Dropdown */}
            {open && (
              <div className="absolute z-50 mt-1 max-h-64 w-full overflow-hidden rounded-lg border border-gray-200 bg-white shadow-lg dark:border-gray-600 dark:bg-gray-800">
                {/* Search */}
                <div className="border-b border-gray-200 p-2 dark:border-gray-700">
                  <input
                    type="text"
                    className="w-full rounded-md border border-gray-200 bg-gray-50 px-3 py-1.5 text-sm text-gray-900 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder={st('agent.searchTools', 'Search tools...')}
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    autoFocus
                    data-testid="tool-picker-search"
                  />
                </div>

                {/* Tool list */}
                <div className="max-h-48 overflow-y-auto">
                  {loading ? (
                    <div className="p-3 text-center text-sm text-gray-400">Loading...</div>
                  ) : filtered.length === 0 ? (
                    <div className="p-3 text-center text-sm text-gray-400">
                      {tools.length === 0
                        ? st('agent.noToolsAvailable', 'No tools available. Sync tools first.')
                        : st('agent.noMatch', 'No matching tools')}
                    </div>
                  ) : (
                    filtered.map((tool) => {
                      const isSelected = selectedCodes.has(tool.tool_code);
                      return (
                        <div
                          key={tool.tool_code}
                          className={`flex cursor-pointer items-start gap-2 px-3 py-2 transition-colors ${isSelected ? 'bg-blue-50 dark:bg-blue-900/20' : 'hover:bg-gray-50 dark:hover:bg-gray-700/30'}`}
                          onClick={() => toggleTool(tool)}
                        >
                          <input
                            type="checkbox"
                            checked={isSelected}
                            readOnly
                            className="mt-1 rounded border-gray-300"
                          />
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-medium text-gray-900 dark:text-white">
                                {tool.tool_name}
                              </span>
                              <span
                                className={`rounded px-1.5 py-0.5 text-[10px] ${typeColors[tool.tool_type] || 'bg-gray-100 text-gray-600'}`}
                              >
                                {tool.tool_type}
                              </span>
                            </div>
                            <div className="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">
                              {tool.tool_description || tool.tool_code}
                            </div>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            )}
          </div>
        </FieldControl>
      </FieldBase>
    );
  },
);

AgentToolPicker.displayName = 'AgentToolPicker';
export default AgentToolPicker;
