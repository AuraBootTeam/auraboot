import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { KanbanCardItem } from '../KanbanCardItem';
import type { KanbanCard } from '~/framework/smart/types/kanban';

const card: KanbanCard = {
  id: 'card-1',
  columnId: 'col-1',
  name: 'Test Card',
};

function renderCard(terminal?: 'won' | 'lost') {
  return render(
    <DndContext>
      <SortableContext items={[card.id]}>
        <KanbanCardItem card={card} titleField="name" terminal={terminal} />
      </SortableContext>
    </DndContext>,
  );
}

describe('KanbanCardItem terminal visual treatment', () => {
  it('renders declared dictionary labels and does not expose an unknown enum code', () => {
    const { getByText, queryByText, rerender } = render(
      <DndContext><SortableContext items={[card.id]}>
        <KanbanCardItem card={{ ...card, inbound_type: 'purchase_in' }} titleField="name"
          cardFields={[{ field: 'inbound_type', type: 'tag', dictCode: 'pe_in_type', valueLabels: { purchase_in: '采购入库' }, unrecognizedLabel: '未识别' }]} />
      </SortableContext></DndContext>,
    );
    expect(getByText('采购入库')).toBeInTheDocument();
    expect(queryByText('purchase_in')).toBeNull();
    rerender(<DndContext><SortableContext items={[card.id]}>
      <KanbanCardItem card={{ ...card, inbound_type: 'legacy_unknown' }} titleField="name"
        cardFields={[{ field: 'inbound_type', type: 'tag', dictCode: 'pe_in_type', valueLabels: { purchase_in: '采购入库' }, unrecognizedLabel: '未识别' }]} />
    </SortableContext></DndContext>);
    expect(getByText('未识别')).toBeInTheDocument();
    expect(queryByText('legacy_unknown')).toBeNull();
  });

  it('applies green left border and renders won icon when terminal=won', () => {
    const { container, queryByTestId } = renderCard('won');
    const cardEl = container.querySelector('[data-card-id="card-1"]') as HTMLElement | null;
    expect(cardEl).not.toBeNull();
    expect(cardEl!.className).toMatch(/border-l-green-500/);
    expect(cardEl!.getAttribute('data-card-terminal')).toBe('won');
    expect(queryByTestId('card-terminal-icon-won')).not.toBeNull();
    expect(queryByTestId('card-terminal-icon-lost')).toBeNull();
  });

  it('applies gray left border and renders lost icon when terminal=lost', () => {
    const { container, queryByTestId } = renderCard('lost');
    const cardEl = container.querySelector('[data-card-id="card-1"]') as HTMLElement | null;
    expect(cardEl).not.toBeNull();
    expect(cardEl!.className).toMatch(/border-l-gray-400/);
    expect(cardEl!.getAttribute('data-card-terminal')).toBe('lost');
    expect(queryByTestId('card-terminal-icon-lost')).not.toBeNull();
    expect(queryByTestId('card-terminal-icon-won')).toBeNull();
  });

  it('applies default blue border and renders no terminal icon when terminal undefined', () => {
    const { container, queryByTestId } = renderCard(undefined);
    const cardEl = container.querySelector('[data-card-id="card-1"]') as HTMLElement | null;
    expect(cardEl).not.toBeNull();
    expect(cardEl!.className).toMatch(/border-l-blue-300/);
    expect(cardEl!.getAttribute('data-card-terminal')).toBe('');
    expect(queryByTestId('card-terminal-icon-won')).toBeNull();
    expect(queryByTestId('card-terminal-icon-lost')).toBeNull();
  });

  it('renders reference display labels instead of internal record ids', () => {
    const referenceCard: KanbanCard = {
      ...card,
      account_id: '01KZQ6G65XH6K2EF3MQ17W90FK',
      account_id_display: '华东智造集团',
    };
    const { getByText, queryByText } = render(
      <DndContext>
        <SortableContext items={[referenceCard.id]}>
          <KanbanCardItem
            card={referenceCard}
            titleField="name"
            cardFields={[
              {
                field: 'account_id',
                label: { 'zh-CN': '关联客户', 'en-US': 'Account' },
                type: 'text',
              },
            ]}
          />
        </SortableContext>
      </DndContext>,
    );

    expect(getByText('华东智造集团')).not.toBeNull();
    expect(getByText('关联客户:')).not.toBeNull();
    expect(queryByText('01KZQ6G65XH6K2EF3MQ17W90FK')).toBeNull();
  });
});
