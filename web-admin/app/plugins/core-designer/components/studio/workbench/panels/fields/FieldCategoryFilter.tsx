import React from 'react';
import type { FieldCategoryInfo } from './types';

interface FieldCategoryFilterProps {
  categories: FieldCategoryInfo[];
  selected: string;
  onSelect: (category: string) => void;
}

/**
 * Category filter chips for the Field Library Panel.
 *
 * @since 3.1.0
 */
export const FieldCategoryFilter: React.FC<FieldCategoryFilterProps> = ({
  categories,
  selected,
  onSelect,
}) => {
  return (
    <div className="flex flex-wrap gap-1.5">
      <button
        onClick={() => onSelect('all')}
        className={`rounded-full px-2.5 py-1 text-xs transition-colors ${
          selected === 'all'
            ? 'border border-blue-200 bg-blue-100 text-blue-700'
            : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
        }`}
      >
        全部
      </button>
      {categories.map((cat) => (
        <button
          key={cat.id}
          onClick={() => onSelect(cat.id)}
          className={`flex items-center gap-1 rounded-full px-2.5 py-1 text-xs transition-colors ${
            selected === cat.id
              ? 'border border-blue-200 bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          <span>{cat.icon}</span>
          <span>{cat.name}</span>
          <span className="text-gray-400">({cat.count})</span>
        </button>
      ))}
    </div>
  );
};
