/**
 * KanbanCardItem Component
 *
 * A draggable card component for the Kanban board using @dnd-kit/sortable.
 * Displays card title, description, and custom fields with proper formatting.
 */

import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { cn } from '~/utils/cn';
import type { KanbanCard, KanbanCardField } from '~/framework/smart/types/kanban';

/**
 * Props for KanbanCardItem component
 */
export interface KanbanCardItemProps {
  /** Card data */
  card: KanbanCard;
  /** Field name to use as card title */
  titleField: string;
  /** Field name to use as card description */
  descriptionField?: string;
  /** Additional fields to display on the card */
  cardFields?: KanbanCardField[];
  /** Whether the card can be dragged, defaults to true */
  draggable?: boolean;
  /** Callback when card is clicked */
  onClick?: (card: KanbanCard) => void;
}

/**
 * Format a field value based on its type
 */
function formatFieldValue(value: unknown, type?: KanbanCardField['type']): React.ReactNode {
  if (value === null || value === undefined) {
    return '-';
  }

  switch (type) {
    case 'date': {
      const date = value instanceof Date ? value : new Date(String(value));
      return isNaN(date.getTime()) ? String(value) : date.toLocaleDateString('zh-CN');
    }
    case 'number': {
      const num = typeof value === 'number' ? value : parseFloat(String(value));
      return isNaN(num) ? String(value) : new Intl.NumberFormat('zh-CN').format(num);
    }
    case 'tag': {
      return (
        <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
          {String(value)}
        </span>
      );
    }
    case 'avatar': {
      const name = String(value);
      const initial = name.charAt(0).toUpperCase();
      return (
        <span
          className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-gray-200 text-xs font-medium text-gray-600"
          title={name}
        >
          {initial}
        </span>
      );
    }
    default:
      return String(value);
  }
}

/**
 * KanbanCardItem - A draggable card for the Kanban board
 */
export function KanbanCardItem({
  card,
  titleField,
  descriptionField,
  cardFields,
  draggable = true,
  onClick,
}: KanbanCardItemProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: card.id,
    disabled: !draggable,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const title = card[titleField];
  const description = descriptionField ? card[descriptionField] : undefined;

  const handleClick = () => {
    if (onClick) {
      onClick(card);
    }
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      className={cn(
        'cursor-grab rounded-lg border bg-white p-3 shadow-sm',
        'transition-shadow hover:shadow-md',
        isDragging && 'opacity-50',
        !draggable && 'cursor-default',
      )}
      onClick={handleClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          handleClick();
        }
      }}
    >
      {/* Title */}
      <div className="truncate text-sm font-medium" title={String(title ?? '')}>
        {String(title ?? '')}
      </div>

      {/* Description */}
      {description !== undefined && description !== null && (
        <div className="mt-1 line-clamp-2 text-xs text-gray-500">{String(description)}</div>
      )}

      {/* Custom fields */}
      {cardFields && cardFields.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-2">
          {cardFields.map((field) => {
            const value = card[field.field];
            if (value === undefined || value === null) return null;

            return (
              <div key={field.field} className="flex items-center gap-1 text-xs text-gray-600">
                {field.label && <span className="text-gray-400">{field.label}:</span>}
                <span>{formatFieldValue(value, field.type)}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
