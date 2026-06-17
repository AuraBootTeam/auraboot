import React from 'react';
import { Link } from 'react-router';
interface FormItem {
  id: string;
  title: string;
  version: string | null;
  type: string | null;
  action: string | null;
  device: string | null;
  layout: string;
  components: any[];
}

interface ListProps {
  itemList: FormItem[];
}

export default function List({ itemList }: ListProps) {
  return (
    <div className="mx-auto w-full max-w-6xl p-4">
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-text text-2xl font-bold">表单列表</h2>
        <Link
          to="/form/new"
          className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white shadow-sm transition-colors"
        >
          新建表单
        </Link>
      </div>

      <div className="rounded-card bg-panel overflow-x-auto shadow">
        <table className="divide-border min-w-full divide-y">
          <thead className="bg-subtle">
            <tr>
              <th
                scope="col"
                className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase"
              >
                ID
              </th>
              <th
                scope="col"
                className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase"
              >
                表单标题
              </th>
              <th
                scope="col"
                className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase"
              >
                布局方式
              </th>
              <th
                scope="col"
                className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase"
              >
                组件数量
              </th>
              <th
                scope="col"
                className="text-text-2 px-6 py-3 text-left text-xs font-medium tracking-wider uppercase"
              >
                操作
              </th>
            </tr>
          </thead>
          <tbody className="divide-border bg-panel divide-y">
            {itemList.map((item) => (
              <tr key={item.id} className="hover:bg-hover">
                <td className="text-text-2 px-6 py-4 text-sm whitespace-nowrap">{item.id}</td>
                <td className="text-text px-6 py-4 text-sm font-medium whitespace-nowrap">
                  {item.title}
                </td>
                <td className="text-text-2 px-6 py-4 text-sm whitespace-nowrap">{item.layout}</td>
                <td className="text-text-2 px-6 py-4 text-sm whitespace-nowrap">
                  {item.components?.length || 0}
                </td>
                <td className="space-x-2 px-6 py-4 text-sm font-medium whitespace-nowrap">
                  <Link
                    to={`/form/${item.id}/edit`}
                    className="bg-accent-weak text-accent hover:text-accent-hover rounded px-2 py-1"
                  >
                    编辑
                  </Link>
                  <Link
                    to={`/form/${item.id}/permissions`}
                    className="rounded bg-green-50 px-2 py-1 text-green-600 hover:text-green-900"
                  >
                    权限
                  </Link>
                  <Link
                    to={`/form/${item.id}/preview`}
                    className="rounded bg-purple-50 px-2 py-1 text-purple-600 hover:text-purple-900"
                  >
                    预览
                  </Link>
                  <button className="rounded bg-red-50 px-2 py-1 text-red-600 hover:text-red-900">
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-6 flex items-center justify-between">
        <div className="text-text-2 text-sm">共 {itemList.length} 条记录</div>
        <div className="flex space-x-2">
          <button
            className="rounded-control border-border-strong bg-panel text-text-2 border px-3 py-1 disabled:opacity-50"
            disabled
          >
            上一页
          </button>
          <button className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-3 py-1">
            下一页
          </button>
        </div>
      </div>
    </div>
  );
}
