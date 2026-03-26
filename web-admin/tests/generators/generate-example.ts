/**
 * Example: Generate a test spec from model metadata.
 *
 * Usage: npx tsx tests/generators/generate-example.ts > tests/generated-store.spec.ts
 *
 * @since 4.0.0
 */

import { generateTestSpec } from './TestGenerator';
import type { TemplateModelMeta } from '../../app/meta/templates/types';

// Example model definition
const storeModel: TemplateModelMeta = {
  modelCode: 'store',
  displayName: '门店',
  primaryKey: 'pid',
  fields: [
    { field: 'name', label: '门店名称', type: 'string', required: true, searchable: true, listVisible: true },
    { field: 'code', label: '门店编码', type: 'string', required: true, listVisible: true },
    { field: 'type', label: '门店类型', type: 'enum', searchable: true, listVisible: true,
      options: [
        { label: '旗舰店', value: 'flagship' },
        { label: '分店', value: 'branch' },
        { label: '加盟店', value: 'franchise' },
      ],
    },
    { field: 'status', label: '状态', type: 'enum', searchable: true, listVisible: true,
      options: [
        { label: '营业中', value: 'active' },
        { label: '暂停营业', value: 'inactive' },
        { label: '已关闭', value: 'closed' },
      ],
    },
    { field: 'address', label: '地址', type: 'string', listVisible: true },
    { field: 'contactPhone', label: '联系电话', type: 'string', required: true },
    { field: 'createdAt', label: '创建时间', type: 'datetime', listVisible: true, valueType: 'datetime' },
  ],
};

const spec = generateTestSpec({
  model: storeModel,
  paths: {
    list: '/enterprise/stores',
    create: '/enterprise/stores/create',
    edit: '/enterprise/stores/:id/edit',
    detail: '/enterprise/stores/:id',
  },
  crudFlow: true,
  validationTests: true,
  paginationTests: true,
});

console.log(spec);
