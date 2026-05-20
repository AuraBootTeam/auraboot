import type { PageSchemaV3 } from '../types';

export const samplePageSchemaV3: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'composite',
  id: 'customer_workspace',
  title: { en: 'Customer Workspace', 'zh-CN': '客户工作台' },
  layout: { type: 'grid', cols: 12 },
  blocks: [
    {
      id: 'form_customer',
      blockType: 'form',
      title: { en: 'Customer Form', 'zh-CN': '客户表单' },
      layout: { span: 6 },
      dataSource: { model: 'customer' },
      blocks: [
        {
          id: 'section_basic',
          blockType: 'form-section',
          title: { en: 'Basic Information', 'zh-CN': '基本信息' },
          layout: { columns: 12 },
          blocks: [
            {
              id: 'field_customer_name',
              blockType: 'field',
              field: 'name',
              layout: { span: 6 },
              props: { label: 'Customer name', component: 'input', required: true },
            },
            {
              id: 'field_customer_phone',
              blockType: 'field',
              field: 'phone',
              layout: { span: 6 },
              props: { label: 'Phone', component: 'input' },
            },
          ],
        },
        {
          id: 'form_actions',
          blockType: 'action-bar',
          region: 'footer',
          blocks: [
            {
              id: 'action_submit',
              blockType: 'action',
              actionType: 'submit',
              props: { label: 'Submit', feedback: 'toast' },
            },
          ],
        },
      ],
    },
    {
      id: 'list_customer',
      blockType: 'list',
      title: { en: 'Customer List', 'zh-CN': '客户列表' },
      layout: { span: 6 },
      dataSource: { model: 'customer' },
      blocks: [
        {
          id: 'list_filters',
          blockType: 'filter-bar',
          region: 'filters',
          blocks: [{ id: 'filter_status', blockType: 'filter-field', field: 'status' }],
        },
        {
          id: 'list_toolbar',
          blockType: 'action-bar',
          region: 'toolbar',
          blocks: [
            {
              id: 'action_create',
              blockType: 'action',
              actionType: 'create',
              props: { label: 'Create' },
            },
            {
              id: 'action_import',
              blockType: 'action',
              actionType: 'command',
              props: { label: 'Import', command: 'customer.import' },
            },
          ],
        },
        {
          id: 'table_customers',
          blockType: 'table',
          blocks: [
            {
              id: 'column_title',
              blockType: 'column',
              field: 'title',
              layout: { width: 220 },
              props: { label: 'Title' },
            },
            {
              id: 'column_status',
              blockType: 'column',
              field: 'status',
              layout: { width: 140 },
              props: { label: 'Status' },
            },
          ],
        },
      ],
    },
    {
      id: 'dashboard_sales',
      blockType: 'dashboard',
      title: { en: 'Sales Dashboard', 'zh-CN': '销售仪表盘' },
      layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
      blocks: [
        {
          id: 'widget_revenue',
          blockType: 'widget',
          widgetType: 'number-card',
          layout: { x: 0, y: 0, w: 3, h: 2 },
          props: { title: 'Revenue' },
        },
        {
          id: 'widget_trend',
          blockType: 'widget',
          widgetType: 'line-chart',
          layout: { x: 3, y: 0, w: 6, h: 3 },
          props: { title: 'Revenue trend' },
        },
      ],
    },
  ],
};
