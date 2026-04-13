export type DataSourceType =
  | 'namedQuery'
  | 'api'
  | 'static'
  | 'dictionary'
  | 'relation'

/**
 * Reference to a data source. Resolved at runtime by the DataSource registry.
 *
 * @example
 * { type: 'namedQuery', source: 'crm.customer.list' }
 * { type: 'dictionary', source: 'order_status' }
 * { type: 'relation', source: 'customer.orders' }
 */
export interface DataSourceRef {
  type: DataSourceType
  source: string
  params?: Record<string, unknown>
}
