/**
 * Visual ORDER BY builder for NamedQuery test panel.
 */

import React, { useState, useCallback } from 'react';
import type { NamedQueryFieldDTO } from '~/services/namedQueryService';

interface OrderItem {
  id: string;
  field: string;
  direction: 'asc' | 'desc';
}

interface OrderBuilderProps {
  fields: NamedQueryFieldDTO[];
  value: string; // raw JSON
  onChange: (json: string) => void;
}

let nextId = 1;
function genId() {
  return `ord_${nextId++}`;
}

function parseOrders(json: string): OrderItem[] {
  if (!json.trim()) return [];
  try {
    const parsed = JSON.parse(json);
    const items = Array.isArray(parsed) ? parsed : [parsed];
    return items.map((item) => ({
      id: genId(),
      field: item.field || '',
      direction: item.direction === 'desc' ? 'desc' : 'asc',
    }));
  } catch {
    return [];
  }
}

function toJson(orders: OrderItem[]): string {
  if (orders.length === 0) return '';
  const items = orders
    .filter((o) => o.field)
    .map((o) => ({ field: o.field, direction: o.direction }));
  if (items.length === 0) return '';
  return JSON.stringify(items, null, 2);
}

export default function OrderBuilder({ fields, value, onChange }: OrderBuilderProps) {
  const [orders, setOrders] = useState<OrderItem[]>(() => parseOrders(value));

  const sortableFields = fields.filter((f) => f.sortable !== false);

  const sync = useCallback(
    (items: OrderItem[]) => {
      setOrders(items);
      onChange(toJson(items));
    },
    [onChange],
  );

  const addOrder = () => {
    const firstField = sortableFields[0]?.fieldCode || fields[0]?.fieldCode || '';
    sync([...orders, { id: genId(), field: firstField, direction: 'asc' }]);
  };

  const removeOrder = (id: string) => {
    sync(orders.filter((o) => o.id !== id));
  };

  const updateOrder = (id: string, patch: Partial<OrderItem>) => {
    sync(orders.map((o) => (o.id === id ? { ...o, ...patch } : o)));
  };

  return (
    <div>
      <label className="mb-2 block text-sm font-medium text-gray-700">排序规则</label>
      <div className="space-y-2">
        {orders.map((order) => (
          <div
            key={order.id}
            className="flex items-center gap-2 rounded-md border border-gray-200 bg-gray-50 p-2"
          >
            <select
              value={order.field}
              onChange={(e) => updateOrder(order.id, { field: e.target.value })}
              className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">选择字段</option>
              {fields.map((f) => (
                <option key={f.fieldCode} value={f.fieldCode}>
                  {f.displayName || f.fieldCode}
                </option>
              ))}
            </select>
            <select
              value={order.direction}
              onChange={(e) =>
                updateOrder(order.id, { direction: e.target.value as 'asc' | 'desc' })
              }
              className="w-24 rounded border border-gray-300 px-2 py-1.5 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
            >
              <option value="asc">ASC</option>
              <option value="desc">DESC</option>
            </select>
            <button
              type="button"
              onClick={() => removeOrder(order.id)}
              className="p-1 text-gray-400 hover:text-red-500"
              title="删除排序"
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
          </div>
        ))}

        <button
          type="button"
          onClick={addOrder}
          className="inline-flex items-center rounded-md px-3 py-1.5 text-sm text-blue-600 hover:bg-blue-50 hover:text-blue-800"
        >
          <svg className="mr-1 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          添加排序
        </button>
      </div>
    </div>
  );
}
