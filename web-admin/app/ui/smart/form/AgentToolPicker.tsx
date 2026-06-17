/**
 * AgentToolPicker - Agent Tool multi-select component
 *
 * Fetches available tools from ab_agent_tool and renders a searchable
 * multi-select. The value is stored as a JSON array of tool objects:
 * [{toolCode, toolType}]
 */

import React, { forwardRef, useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { get } from '~/shared/services/http-client/HttpClient';
import { ResultHelper } from '~/utils/type';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { FieldControl } from '~/ui/ui/field-control';

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
              className={`rounded-card border-border-strong bg-panel flex min-h-[38px] cursor-pointer flex-wrap gap-1.5 border p-2 dark:border-gray-600 dark:bg-gray-800 ${readOnly ? 'cursor-not-allowed opacity-60' : 'hover:border-accent'}`}
              onClick={() => !readOnly && setOpen(!open)}
              data-testid={`tool-picker-${name}`}
            >
              {selected.length === 0 && (
                <span className="text-text-3 text-sm">
                  {placeholderText || st('agent.selectTools', 'Select tools...')}
                </span>
              )}
              {selected.map((s) => {
                const tool = tools.find((t) => t.tool_code === s.toolCode);
                return (
                  <span
                    key={s.toolCode}
                    className={`rounded-pill inline-flex items-center gap-1 px-2 py-0.5 text-xs ${typeColors[s.toolType] || 'text-text-2 bg-gray-100 dark:bg-gray-700 dark:text-gray-300'}`}
                  >
                    {tool?.tool_name || s.toolCode}
                    {!readOnly && (
                      <button
                        type="button"
                        className="hover:text-status-red ml-0.5"
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
              <div className="rounded-card border-border bg-panel absolute z-50 mt-1 max-h-64 w-full overflow-hidden border shadow-lg dark:border-gray-600 dark:bg-gray-800">
                {/* Search */}
                <div className="border-border border-b p-2 dark:border-gray-700">
                  <input
                    type="text"
                    className="rounded-control border-border bg-subtle text-text focus-visible:shadow-focus w-full border px-3 py-1.5 text-sm focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
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
                    <div className="text-text-3 p-3 text-center text-sm">Loading...</div>
                  ) : filtered.length === 0 ? (
                    <div className="text-text-3 p-3 text-center text-sm">
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
                          className={`flex cursor-pointer items-start gap-2 px-3 py-2 transition-colors ${isSelected ? 'bg-accent-weak dark:bg-blue-900/20' : 'hover:bg-subtle dark:hover:bg-gray-700/30'}`}
                          onClick={() => toggleTool(tool)}
                        >
                          <input
                            type="checkbox"
                            checked={isSelected}
                            readOnly
                            className="rounded-control border-border-strong mt-1"
                          />
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                              <span className="text-text text-sm font-medium dark:text-white">
                                {tool.tool_name}
                              </span>
                              <span
                                className={`rounded-control px-1.5 py-0.5 text-[10px] ${typeColors[tool.tool_type] || 'text-text-2 bg-gray-100'}`}
                              >
                                {tool.tool_type}
                              </span>
                            </div>
                            <div className="text-text-2 mt-0.5 truncate text-xs dark:text-gray-400">
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
