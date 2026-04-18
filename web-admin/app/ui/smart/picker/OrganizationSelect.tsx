import React, { useState, useEffect } from 'react';
import { Building2, Search, X, ChevronRight, ChevronDown } from 'lucide-react';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { FieldActionButton } from '~/ui/ui/field-action-button';
import {
  organizationService,
  type DepartmentTreeNode,
} from '~/shared/services/organizationService';

interface OrganizationNode {
  id: string;
  name: string;
  code?: string;
  type: 'company' | 'department' | 'team';
  parentId?: string;
  children?: OrganizationNode[];
  level: number;
}

interface OrganizationSelectProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: string | string[];
  onChange?: (value: string | string[] | undefined) => void;
  disabled?: boolean;
  required?: boolean;
  multiple?: boolean;
  allowClear?: boolean;
  showHierarchy?: boolean;
  selectableTypes?: ('company' | 'department' | 'team')[];
  className?: string;
}

export const OrganizationSelect: React.FC<OrganizationSelectProps> = ({
  name,
  label,
  placeholder = '请选择组织',
  value,
  onChange,
  disabled = false,
  required = false,
  multiple = false,
  allowClear = true,
  showHierarchy = true,
  selectableTypes = ['company', 'department', 'team'],
  className = '',
}) => {
  const st = useSmartText();
  const {
    labelText,
    placeholderText,
    required: requiredValue,
    disabled: disabledValue,
  } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
  });
  const meta = useSmartFieldMeta({ externalError: undefined });
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [organizations, setOrganizations] = useState<OrganizationNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);

  // Map DepartmentTreeNode (API) → OrganizationNode (UI)
  const mapDeptTreeToOrgNodes = (
    nodes: DepartmentTreeNode[],
    parentId: string | null,
    level: number,
  ): OrganizationNode[] => {
    return nodes.map((node) => {
      const type: OrganizationNode['type'] =
        level === 0 ? 'company' : level === 1 ? 'department' : 'team';
      return {
        id: node.pid,
        name: node.name,
        type,
        parentId: parentId ?? undefined,
        level,
        children:
          node.children && node.children.length > 0
            ? mapDeptTreeToOrgNodes(node.children, node.pid, level + 1)
            : undefined,
      };
    });
  };

  useEffect(() => {
    setLoading(true);
    organizationService
      .getDepartmentTree()
      .then((result) => {
        if (result.data) {
          const nodes = mapDeptTreeToOrgNodes(result.data, null, 0);
          setOrganizations(nodes);
          setExpandedNodes(new Set(result.data.map((n) => n.pid)));
        }
      })
      .catch((err) => console.error('Failed to load departments:', err))
      .finally(() => setLoading(false));
  }, []);

  const flattenOrganizations = (orgs: OrganizationNode[]): OrganizationNode[] => {
    const result: OrganizationNode[] = [];
    const traverse = (nodes: OrganizationNode[]) => {
      nodes.forEach((node) => {
        result.push(node);
        if (node.children) {
          traverse(node.children);
        }
      });
    };
    traverse(orgs);
    return result;
  };

  const filteredOrganizations = React.useMemo(() => {
    const allOrgs = flattenOrganizations(organizations);
    if (!searchQuery) return organizations;

    const filtered = allOrgs.filter((org) =>
      org.name.toLowerCase().includes(searchQuery.toLowerCase()),
    );

    // If searching, return flat list
    return filtered.map((org) => ({ ...org, children: undefined }));
  }, [organizations, searchQuery]);

  const selectedOrganizations = React.useMemo(() => {
    if (!value) return [];
    const selectedIds = Array.isArray(value) ? value : [value];
    const allOrgs = flattenOrganizations(organizations);
    return allOrgs.filter((org) => selectedIds.includes(org.id));
  }, [value, organizations]);

  const handleOrganizationSelect = (org: OrganizationNode) => {
    if (!selectableTypes.includes(org.type)) return;

    if (multiple) {
      const currentValue = Array.isArray(value) ? value : value ? [value] : [];
      const newValue = currentValue.includes(org.id)
        ? currentValue.filter((id) => id !== org.id)
        : [...currentValue, org.id];
      onChange?.(newValue.length > 0 ? newValue : undefined);
    } else {
      onChange?.(org.id);
      setIsOpen(false);
    }
    meta.markTouched();
  };

  const handleRemoveOrganization = (orgId: string, event: React.MouseEvent) => {
    event.stopPropagation();
    if (multiple) {
      const currentValue = Array.isArray(value) ? value : [];
      const newValue = currentValue.filter((id) => id !== orgId);
      onChange?.(newValue.length > 0 ? newValue : undefined);
    } else {
      onChange?.(undefined);
    }
    meta.markTouched();
  };

  const handleClear = (event: React.MouseEvent) => {
    event.stopPropagation();
    onChange?.(undefined);
    meta.markTouched();
  };

  const toggleExpanded = (nodeId: string, event: React.MouseEvent) => {
    event.stopPropagation();
    const newExpanded = new Set(expandedNodes);
    if (newExpanded.has(nodeId)) {
      newExpanded.delete(nodeId);
    } else {
      newExpanded.add(nodeId);
    }
    setExpandedNodes(newExpanded);
  };

  const isSelected = (orgId: string) => {
    if (!value) return false;
    return Array.isArray(value) ? value.includes(orgId) : value === orgId;
  };

  const isSelectable = (org: OrganizationNode) => {
    return selectableTypes.includes(org.type);
  };

  const getTypeIcon = (type: string) => {
    switch (type) {
      case 'company':
        return '🏢';
      case 'department':
        return '🏬';
      case 'team':
        return '👥';
      default:
        return '📁';
    }
  };

  const displayText = () => {
    if (selectedOrganizations.length === 0) return placeholderText;
    if (selectedOrganizations.length === 1) return selectedOrganizations[0].name;
    return st(`已选择 ${selectedOrganizations.length} 个组织`);
  };

  const renderOrganizationTree = (nodes: OrganizationNode[]) => {
    return nodes.map((node) => (
      <div key={node.id}>
        <div
          className={`flex cursor-pointer items-center p-2 hover:bg-gray-50 ${isSelected(node.id) ? 'bg-blue-50 text-blue-900' : 'text-gray-900'} ${!isSelectable(node) ? 'opacity-60' : ''} `}
          style={{ paddingLeft: `${node.level * 20 + 8}px` }}
          onClick={() => isSelectable(node) && handleOrganizationSelect(node)}
        >
          {node.children && node.children.length > 0 && (
            <button
              onClick={(e) => toggleExpanded(node.id, e)}
              className="mr-1 rounded p-1 hover:bg-gray-200"
            >
              {expandedNodes.has(node.id) ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              )}
            </button>
          )}
          {(!node.children || node.children.length === 0) && <div className="mr-1 w-5" />}
          <span className="mr-2">{getTypeIcon(node.type)}</span>
          <div className="min-w-0 flex-1">
            <div className="flex items-center space-x-2">
              <span className="truncate font-medium">{node.name}</span>
              {isSelected(node.id) && <span className="text-blue-600">✓</span>}
            </div>
            {node.code && <div className="text-xs text-gray-500">{node.code}</div>}
          </div>
        </div>
        {node.children && expandedNodes.has(node.id) && (
          <div>{renderOrganizationTree(node.children)}</div>
        )}
      </div>
    ));
  };

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? st(meta.meta.error) : undefined}
      className={`relative space-y-2 ${className}`}
    >
      <div className="relative">
        <div
          className={`w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm ${disabledValue ? 'cursor-not-allowed bg-gray-50' : 'cursor-pointer bg-white hover:border-gray-400'} ${selectedOrganizations.length > 0 ? 'text-gray-900' : 'text-gray-500'} focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none`}
          onClick={() => !disabledValue && setIsOpen(!isOpen)}
        >
          <div className="flex items-center justify-between">
            <div className="flex min-w-0 flex-1 items-center space-x-2">
              <Building2 className="h-4 w-4 flex-shrink-0 text-gray-400" />
              {multiple && selectedOrganizations.length > 0 ? (
                <div className="flex flex-1 flex-wrap gap-1">
                  {selectedOrganizations.map((org) => (
                    <span
                      key={org.id}
                      className="inline-flex items-center rounded-full bg-blue-100 px-2 py-1 text-xs text-blue-800"
                    >
                      {org.name}
                      {!disabledValue && (
                        <button
                          type="button"
                          onClick={(e) => handleRemoveOrganization(org.id, e)}
                          className="ml-1 text-blue-600 hover:text-blue-800"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      )}
                    </span>
                  ))}
                </div>
              ) : (
                <span className="truncate">{displayText()}</span>
              )}
            </div>
            {selectedOrganizations.length > 0 && allowClear && !disabledValue && (
              <FieldActionButton type="button" onClick={handleClear} iconOnly>
                <X className="h-4 w-4" />
              </FieldActionButton>
            )}
          </div>
        </div>

        {/* Dropdown */}
        {isOpen && !disabledValue && (
          <div className="absolute z-50 mt-1 w-full rounded-md border border-gray-300 bg-white shadow-lg">
            {/* Search Input */}
            <div className="border-b p-2">
              <div className="relative">
                <Search className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 transform text-gray-400" />
                <input
                  type="text"
                  placeholder={st('搜索组织...')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full rounded-md border border-gray-300 py-2 pr-4 pl-10 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>
            </div>

            {/* Organization Tree */}
            <div className="max-h-60 overflow-y-auto">
              {loading ? (
                <div className="p-4 text-center text-gray-500">
                  <div className="mx-auto h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600"></div>
                  <span className="mt-2 block">{st('加载中...')}</span>
                </div>
              ) : filteredOrganizations.length === 0 ? (
                <div className="p-4 text-center text-gray-500">{st('没有找到匹配的组织')}</div>
              ) : (
                <div>
                  {searchQuery
                    ? // Flat list when searching
                      filteredOrganizations.map((org) => (
                        <div
                          key={org.id}
                          className={`cursor-pointer border-b border-gray-100 p-3 last:border-b-0 hover:bg-gray-50 ${isSelected(org.id) ? 'bg-blue-50 text-blue-900' : 'text-gray-900'} ${!isSelectable(org) ? 'opacity-60' : ''} `}
                          onClick={() => isSelectable(org) && handleOrganizationSelect(org)}
                        >
                          <div className="flex items-center space-x-3">
                            <span>{getTypeIcon(org.type)}</span>
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center space-x-2">
                                <span className="font-medium">{org.name}</span>
                                {isSelected(org.id) && <span className="text-blue-600">✓</span>}
                              </div>
                              {org.code && <div className="text-sm text-gray-500">{org.code}</div>}
                            </div>
                          </div>
                        </div>
                      ))
                    : // Tree view when not searching
                      renderOrganizationTree(filteredOrganizations)}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      <input
        type="hidden"
        name={name}
        value={Array.isArray(value) ? value.join(',') : value || ''}
      />
    </FieldBase>
  );
};

export default OrganizationSelect;
