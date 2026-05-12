/**
 * KanbanCardItem Component
 *
 * A draggable card component for the Kanban board using @dnd-kit/sortable.
 * Displays card title, description, and custom fields with proper formatting.
 */

import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Check, X } from 'lucide-react';
import { cn } from '~/utils/cn';
import type { KanbanCard, KanbanCardField } from '~/framework/smart/types/kanban';
import {
  AvatarField,
  CurrencyField,
  DateRelativeField,
  ProgressField,
} from './cardFields';

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
  /** Terminal state of the column this card belongs to (drives visual treatment) */
  terminal?: 'won' | 'lost';
  /** Callback when card is clicked */
  onClick?: (card: KanbanCard) => void;
}

/**
 * Render a field value based on its declared type. Dispatches to the
 * dedicated cardFields components for currency / avatar / progress /
 * date-relative; keeps inline rendering for legacy text / number / date / tag.
 */
function renderFieldValue(field: KanbanCardField, value: unknown): React.ReactNode {
  switch (field.type) {
    case 'currency':
      return (
        <CurrencyField
          value={value as number | string | null | undefined}
          currencyCode={field.currencyCode}
        />
      );
    case 'avatar':
      return <AvatarField value={value === null || value === undefined ? null : String(value)} />;
    case 'progress':
      return (
        <ProgressField
          value={value as number | string | null | undefined}
          max={field.max}
        />
      );
    case 'date-relative':
      return (
        <DateRelativeField value={value as string | Date | null | undefined} />
      );
    case 'date': {
      if (value === null || value === undefined) return '-';
      const date = value instanceof Date ? value : new Date(String(value));
      return isNaN(date.getTime()) ? String(value) : date.toLocaleDateString('zh-CN');
    }
    case 'number': {
      if (value === null || value === undefined) return '-';
      const num = typeof value === 'number' ? value : parseFloat(String(value));
      return isNaN(num) ? String(value) : new Intl.NumberFormat('zh-CN').format(num);
    }
    case 'tag': {
      if (value === null || value === undefined) return '-';
      return (
        <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
          {String(value)}
        </span>
      );
    }
    default:
      if (value === null || value === undefined) return '-';
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
  terminal,
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
        'relative cursor-grab rounded-lg border border-l-4 bg-white p-3 shadow-sm',
        'transition-shadow hover:shadow-md',
        terminal === 'won' && 'border-l-green-500',
        terminal === 'lost' && 'border-l-gray-400',
        !terminal && 'border-l-blue-300',
        isDragging && 'opacity-50',
        !draggable && 'cursor-default',
      )}
      data-testid="kanban-card"
      data-card-id={card.id}
      data-card-terminal={terminal ?? ''}
      onClick={handleClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          handleClick();
        }
      }}
    >
      {/* Terminal corner badge */}
      {terminal === 'won' && (
        <Check
          className="absolute right-1 top-1 h-3 w-3 text-green-500"
          data-testid="card-terminal-icon-won"
        />
      )}
      {terminal === 'lost' && (
        <X
          className="absolute right-1 top-1 h-3 w-3 text-gray-400"
          data-testid="card-terminal-icon-lost"
        />
      )}

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
            const isNewFieldType =
              field.type === 'currency' ||
              field.type === 'avatar' ||
              field.type === 'progress' ||
              field.type === 'date-relative';
            // Legacy field types skip rendering when null/undefined to preserve
            // existing card layout; new field types render their own em-dash
            // placeholders for visual consistency.
            if (!isNewFieldType && (value === undefined || value === null)) return null;

            return (
              <div key={field.field} className="flex items-center gap-1 text-xs text-gray-600">
                {field.label && <span className="text-gray-400">{field.label}:</span>}
                <span>{renderFieldValue(field, value)}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
