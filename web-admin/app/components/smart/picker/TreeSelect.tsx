import React, { forwardRef, useState, useEffect, useRef } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { useDictTree } from './useDictTree';

export type TreeSelectSize = 'small' | 'medium' | 'large';
export type TreeSelectVariant = 'default' | 'error';

interface TreeNode {
  key: string;
  value: string;
  label: string;
  disabled?: boolean;
  children?: TreeNode[];
}

interface TreeSelectProps {
  /** 标签文本 */
  label?: string;
  /** 字段名称 */
  name: string;
  /** 树形数据 */
  treeData: TreeNode[];
  /** 受控值 */
  value?: string | string[];
  /** 默认值 */
  defaultValue?: string | string[];
  /** 占位符文本 */
  placeholder?: string;
  /** 是否多选 */
  multiple?: boolean;
  /** 是否显示复选框 */
  checkable?: boolean;
  /** 只允许选择叶子节点 */
  leafOnly?: boolean;
  /** 级联选择模式 */
  cascade?: boolean;
  /** 尺寸 */
  size?: TreeSelectSize;
  /** 样式变体 */
  variant?: TreeSelectVariant;
  /** 错误信息 */
  error?: string;
  /** 必填 */
  required?: boolean;
  /** 是否内联显示 */
  inline?: boolean;
  /** 是否只读 */
  readOnly?: boolean;
  /** 是否显示清除按钮 */
  clearable?: boolean;
  /** 是否显示搜索框 */
  searchable?: boolean;
  /** 最大显示标签数量（多选时） */
  maxTagCount?: number;
  /** 自定义类名 */
  className?: string;
  /** 值变化回调 */
  onChange?: (value: string | string[], selectedNodes?: TreeNode[]) => void;
  /** 清除回调 */
  onClear?: () => void;
  /** 搜索回调 */
  onSearch?: (searchValue: string) => void;
  /** Dict code for auto-loading tree data */
  dictCode?: string;
  /** Disabled state */
  disabled?: boolean;
}

const baseStyles =
  'rounded-lg border transition-all focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed';

const sizeStyles = {
  small: 'px-2 py-1 text-sm min-h-[2rem]',
  medium: 'px-3 py-2 text-base min-h-[2.5rem]',
  large: 'px-4 py-3 text-lg min-h-[3rem]',
};

const variantStyles = {
  default:
    'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white',
  error: 'border-red-300 focus:border-red-500 focus:ring-red-500 dark:border-red-600',
};

export const TreeSelect = forwardRef<HTMLDivElement, TreeSelectProps>(
  (
    {
      label,
      name,
      treeData: externalTreeData,
      value,
      defaultValue,
      placeholder = '请选择',
      multiple = false,
      checkable = false,
      leafOnly = true,
      cascade = false,
      size = 'medium',
      variant = 'default',
      error: propError,
      required = false,
      inline = false,
      readOnly = false,
      clearable = false,
      searchable = false,
      maxTagCount = 3,
      className,
      onChange,
      onClear,
      onSearch,
      dictCode,
    },
    ref,
  ) => {
    const st = useSmartText();

    // Auto-load tree data from dict when dictCode is provided
    const dictTreeData = useDictTree(dictCode, !!externalTreeData);
    const treeData = externalTreeData || (dictTreeData as any) || [];
    const {
      labelText,
      placeholderText,
      required: requiredValue,
    } = useSmartFieldContract({
      label,
      placeholder,
      required,
    });
    const [isOpen, setIsOpen] = useState(false);
    const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
    const [searchValue, setSearchValue] = useState('');
    const [internalValue, setInternalValue] = useState<string | string[]>(
      defaultValue || (multiple ? [] : ''),
    );
    const dropdownRef = useRef<HTMLDivElement>(null);
    const searchInputRef = useRef<HTMLInputElement>(null);

    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    const error = propError || actionError;
    const meta = useSmartFieldMeta({ externalError: error });
    const finalVariant = error ? 'error' : variant;

    // 判断是否为受控组件
    const isControlled = value !== undefined;
    const currentValue = isControlled ? value : internalValue;

    useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
          setIsOpen(false);
          setSearchValue('');
        }
      };

      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    // 搜索时自动展开匹配的节点
    useEffect(() => {
      if (searchValue && isOpen) {
        const matchedKeys = findMatchedNodeKeys(treeData, searchValue);
        setExpandedKeys((prev) => [...new Set([...prev, ...matchedKeys])]);
      }
    }, [searchValue, isOpen, treeData]);

    const findNodeByValue = (nodes: TreeNode[], targetValue: string): TreeNode | null => {
      for (const node of nodes) {
        if (node.value === targetValue) return node;
        if (node.children) {
          const found = findNodeByValue(node.children, targetValue);
          if (found) return found;
        }
      }
      return null;
    };

    const findMatchedNodeKeys = (nodes: TreeNode[], searchText: string): string[] => {
      const keys: string[] = [];
      const traverse = (nodeList: TreeNode[], parentKeys: string[] = []) => {
        nodeList.forEach((node) => {
          if (node.label.toLowerCase().includes(searchText.toLowerCase())) {
            keys.push(...parentKeys, node.key);
          }
          if (node.children) {
            traverse(node.children, [...parentKeys, node.key]);
          }
        });
      };
      traverse(nodes);
      return keys;
    };

    const getAllChildValues = (node: TreeNode): string[] => {
      const values: string[] = [];
      const traverse = (n: TreeNode) => {
        values.push(n.value);
        if (n.children) {
          n.children.forEach(traverse);
        }
      };
      traverse(node);
      return values;
    };

    const getParentValues = (
      nodes: TreeNode[],
      targetValue: string,
      parentValues: string[] = [],
    ): string[] => {
      for (const node of nodes) {
        if (node.value === targetValue) {
          return parentValues;
        }
        if (node.children) {
          const found = getParentValues(node.children, targetValue, [...parentValues, node.value]);
          if (found.length > 0 || node.children.some((child) => child.value === targetValue)) {
            return [...parentValues, node.value];
          }
        }
      }
      return [];
    };

    // 检查节点是否为叶子节点
    const isLeafNode = (node: TreeNode): boolean => {
      return !node.children || node.children.length === 0;
    };

    const handleNodeSelect = (nodeValue: string) => {
      const node = findNodeByValue(treeData, nodeValue);
      if (!node || node.disabled) return;

      // 如果启用了leafOnly且当前节点不是叶子节点，则不允许选择
      if (leafOnly && !isLeafNode(node)) {
        return;
      }

      let newValue;
      let selectedNodes: TreeNode[] = [];

      if (multiple) {
        const currentValues = Array.isArray(currentValue) ? currentValue : [];

        if (cascade) {
          // 级联选择逻辑
          if (currentValues.includes(nodeValue)) {
            // 取消选择：移除当前节点及其所有子节点
            const childValues = getAllChildValues(node);
            newValue = currentValues.filter((v) => !childValues.includes(v));
          } else {
            // 选择：添加当前节点及其所有子节点，同时处理父节点
            const childValues = getAllChildValues(node);
            const parentValues = getParentValues(treeData, nodeValue);
            newValue = [...new Set([...currentValues, ...childValues, ...parentValues])];
          }
        } else {
          // 普通多选逻辑
          if (currentValues.includes(nodeValue)) {
            newValue = currentValues.filter((v) => v !== nodeValue);
          } else {
            newValue = [...currentValues, nodeValue];
          }
        }

        // 获取选中的节点对象
        selectedNodes = (newValue as string[])
          .map((val) => findNodeByValue(treeData, val))
          .filter(Boolean) as TreeNode[];
      } else {
        newValue = nodeValue;
        selectedNodes = [node];
        setIsOpen(false);
      }

      // 如果是非受控组件，更新内部状态
      if (!isControlled) {
        setInternalValue(newValue);
      }

      // 通知父组件
      onChange?.(newValue, selectedNodes);
      meta.markTouched();
    };

    const handleExpand = (nodeKey: string) => {
      setExpandedKeys((prev) =>
        prev.includes(nodeKey) ? prev.filter((k) => k !== nodeKey) : [...prev, nodeKey],
      );
    };

    const handleClear = () => {
      const newValue = multiple ? [] : '';
      if (!isControlled) {
        setInternalValue(newValue);
      }
      onChange?.(newValue, []);
      onClear?.();
      meta.markTouched();
    };

    const handleSearch = (searchText: string) => {
      setSearchValue(searchText);
      onSearch?.(searchText);
    };

    // 处理标签点击事件
    const handleLabelClick = (node: TreeNode) => {
      if (leafOnly && !isLeafNode(node)) {
        // 如果启用了leafOnly且是父节点，则展开/收起节点
        handleExpand(node.key);
      } else {
        // 否则执行选择操作
        handleNodeSelect(node.value);
      }
    };

    const filterTreeData = (nodes: TreeNode[], searchText: string): TreeNode[] => {
      if (!searchText) return nodes;

      return nodes.reduce((filtered: TreeNode[], node) => {
        const matchesSearch = node.label.toLowerCase().includes(searchText.toLowerCase());
        const filteredChildren = node.children ? filterTreeData(node.children, searchText) : [];

        if (matchesSearch || filteredChildren.length > 0) {
          filtered.push({
            ...node,
            children: filteredChildren.length > 0 ? filteredChildren : node.children,
          });
        }

        return filtered;
      }, []);
    };

    const renderTreeNode = (node: TreeNode, level: number = 0) => {
      const hasChildren = node.children && node.children.length > 0;
      const isExpanded = expandedKeys.includes(node.key);
      const isSelected = multiple
        ? Array.isArray(currentValue) && currentValue.includes(node.value)
        : currentValue === node.value;
      const isLeaf = isLeafNode(node);
      const canSelect = !leafOnly || isLeaf;

      return (
        <div key={node.key}>
          <div
            className={clsx(
              'flex cursor-pointer items-center px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-600',
              isSelected && canSelect && 'bg-blue-50 dark:bg-blue-900',
              node.disabled && 'cursor-not-allowed opacity-50',
              !canSelect && leafOnly && 'text-gray-400 dark:text-gray-500',
            )}
            style={{ paddingLeft: `${12 + level * 20}px` }}
            onClick={() => !node.disabled && !readOnly && handleLabelClick(node)}
          >
            {hasChildren && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleExpand(node.key);
                }}
                className="mr-2 rounded p-1 hover:bg-gray-200 dark:hover:bg-gray-500"
              >
                <svg
                  className={clsx('h-3 w-3 transition-transform', isExpanded && 'rotate-90')}
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    fillRule="evenodd"
                    d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z"
                    clipRule="evenodd"
                  />
                </svg>
              </button>
            )}

            {(multiple || checkable) && canSelect && (
              <input
                type="checkbox"
                checked={isSelected}
                disabled={node.disabled || readOnly}
                onChange={(e) => {
                  e.stopPropagation();
                  !node.disabled && !readOnly && handleNodeSelect(node.value);
                }}
                className="mr-2 h-4 w-4 rounded border-gray-300 text-blue-600"
              />
            )}

            <span className="flex-1 text-sm text-gray-700 dark:text-gray-300">{node.label}</span>
          </div>

          {hasChildren && isExpanded && (
            <div>{node.children!.map((child) => renderTreeNode(child, level + 1))}</div>
          )}
        </div>
      );
    };

    const getDisplayText = () => {
      if (multiple) {
        const values = Array.isArray(currentValue) ? currentValue : [];
        if (values.length === 0) return placeholderText;

        const labels = values.map((val) => {
          const node = findNodeByValue(treeData, val);
          return node?.label || val;
        });

        if (labels.length <= maxTagCount) {
          return labels.join(', ');
        } else {
          return `${labels.slice(0, maxTagCount).join(', ')} +${labels.length - maxTagCount}`;
        }
      } else {
        if (!currentValue) return placeholderText;
        const node = findNodeByValue(treeData, currentValue as string);
        return node?.label || currentValue;
      }
    };

    const hasValue = multiple
      ? Array.isArray(currentValue) && currentValue.length > 0
      : Boolean(currentValue);

    const filteredTreeData = filterTreeData(treeData, searchValue);

    const selectElement = (
      <div className={clsx('relative', inline ? 'flex-1' : 'w-full')} ref={dropdownRef}>
        <div
          className={clsx(
            baseStyles,
            sizeStyles[size],
            variantStyles[finalVariant],
            'flex w-full cursor-pointer items-center justify-between',
            readOnly && 'cursor-not-allowed bg-gray-50 dark:bg-gray-800',
            className,
          )}
          onClick={() => !readOnly && setIsOpen(!isOpen)}
        >
          <span className={clsx('flex-1 truncate', !hasValue && 'text-gray-500')}>
            {getDisplayText()}
          </span>

          <div className="flex items-center gap-1">
            {clearable && hasValue && !readOnly && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleClear();
                }}
                className="p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            )}

            <svg
              className={clsx('h-4 w-4 transition-transform', isOpen && 'rotate-180')}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </div>
        </div>

        {isOpen && (
          <div className="absolute z-10 mt-1 w-full rounded-lg border border-gray-300 bg-white shadow-lg dark:border-gray-600 dark:bg-gray-700">
            {searchable && (
              <div className="border-b border-gray-200 p-2 dark:border-gray-600">
                <input
                  ref={searchInputRef}
                  type="text"
                  placeholder={st('搜索...')}
                  value={searchValue}
                  onChange={(e) => handleSearch(e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-500 dark:bg-gray-600 dark:text-white"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>
            )}

            <div className="max-h-60 overflow-y-auto">
              {filteredTreeData.length > 0 ? (
                filteredTreeData.map((node) => renderTreeNode(node))
              ) : (
                <div className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">
                  {searchValue ? st('无匹配结果') : st('暂无数据')}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    );

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        inline={inline}
        error={meta.showError ? st(meta.meta.error) : undefined}
      >
        <div ref={ref} className="w-full">
          {/* 添加隐藏的input来处理表单提交 */}
          <input
            type="hidden"
            name={name}
            value={
              multiple
                ? Array.isArray(currentValue)
                  ? currentValue.join(',')
                  : ''
                : currentValue || ''
            }
          />
          {selectElement}
        </div>
      </FieldBase>
    );
  },
);

TreeSelect.displayName = 'TreeSelect';

export default TreeSelect;

// 侧边栏配置组件
export function TreeSelectSideBar({
  onChange,
  focusItem,
}: {
  onChange: (value: any) => void;
  focusItem: any;
}) {
  return (
    <div className="w-full rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <h2 className="mb-6 border-b pb-3 text-center text-xl font-bold text-gray-900 dark:text-white">
        树形选择框属性设置
      </h2>

      <div className="space-y-5">
        <div className="flex items-center">
          <label
            htmlFor="props.label"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            标签：
          </label>
          <input
            name="props.label"
            onChange={onChange}
            value={focusItem.props?.label || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.placeholder"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            占位符：
          </label>
          <input
            name="props.placeholder"
            onChange={onChange}
            value={focusItem.props?.placeholder || ''}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.size"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            尺寸：
          </label>
          <select
            name="props.size"
            onChange={onChange}
            value={focusItem.props?.size || 'medium'}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="small">小</option>
            <option value="medium">中</option>
            <option value="large">大</option>
          </select>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.inline"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            内联显示：
          </label>
          <input
            type="checkbox"
            name="props.inline"
            onChange={onChange}
            checked={focusItem.props?.inline || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.multiple"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            多选：
          </label>
          <input
            type="checkbox"
            name="props.multiple"
            onChange={onChange}
            checked={focusItem.props?.multiple || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.checkable"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            显示复选框：
          </label>
          <input
            type="checkbox"
            name="props.checkable"
            onChange={onChange}
            checked={focusItem.props?.checkable || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.leafOnly"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            仅选叶子节点：
          </label>
          <input
            type="checkbox"
            name="props.leafOnly"
            onChange={onChange}
            checked={focusItem.props?.leafOnly !== false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.cascade"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            级联选择：
          </label>
          <input
            type="checkbox"
            name="props.cascade"
            onChange={onChange}
            checked={focusItem.props?.cascade || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.searchable"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            可搜索：
          </label>
          <input
            type="checkbox"
            name="props.searchable"
            onChange={onChange}
            checked={focusItem.props?.searchable || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.clearable"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            可清除：
          </label>
          <input
            type="checkbox"
            name="props.clearable"
            onChange={onChange}
            checked={focusItem.props?.clearable || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.readOnly"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            只读：
          </label>
          <input
            type="checkbox"
            name="props.readOnly"
            onChange={onChange}
            checked={focusItem.props?.readOnly || false}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        </div>

        <div>
          <label className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
            树形数据配置：
          </label>
          <textarea
            name="props.treeDataJson"
            onChange={onChange}
            value={
              focusItem.props?.treeDataJson ||
              '[{"key":"1","value":"node1","label":"节点1","children":[{"key":"1-1","value":"node1-1","label":"子节点1-1"},{"key":"1-2","value":"node1-2","label":"子节点1-2"}]},{"key":"2","value":"node2","label":"节点2"}]'
            }
            placeholder='[{"key":"1","value":"node1","label":"节点1","children":[...]}]'
            rows={6}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            JSON 格式的树形数据，支持 key、value、label、disabled、children 字段
          </p>
        </div>

        <div className="flex items-center">
          <label
            htmlFor="props.maxTagCount"
            className="w-1/4 text-sm font-medium text-gray-700 dark:text-gray-300"
          >
            最大标签数：
          </label>
          <input
            type="number"
            name="props.maxTagCount"
            onChange={onChange}
            value={focusItem.props?.maxTagCount || 3}
            min="1"
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>
      </div>
    </div>
  );
}
