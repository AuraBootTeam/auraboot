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
        <h2 className="text-2xl font-bold text-gray-800">表单列表</h2>
        <Link
          to="/form/new"
          className="rounded-md bg-blue-500 px-4 py-2 text-white shadow-sm transition-colors hover:bg-blue-600"
        >
          新建表单
        </Link>
      </div>

      <div className="overflow-x-auto rounded-lg bg-white shadow">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th
                scope="col"
                className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
              >
                ID
              </th>
              <th
                scope="col"
                className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
              >
                表单标题
              </th>
              <th
                scope="col"
                className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
              >
                布局方式
              </th>
              <th
                scope="col"
                className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
              >
                组件数量
              </th>
              <th
                scope="col"
                className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
              >
                操作
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {itemList.map((item) => (
              <tr key={item.id} className="hover:bg-gray-50">
                <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">{item.id}</td>
                <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                  {item.title}
                </td>
                <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">{item.layout}</td>
                <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                  {item.components?.length || 0}
                </td>
                <td className="space-x-2 px-6 py-4 text-sm font-medium whitespace-nowrap">
                  <Link
                    to={`/form/${item.id}/edit`}
                    className="rounded bg-blue-50 px-2 py-1 text-blue-600 hover:text-blue-900"
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
        <div className="text-sm text-gray-500">共 {itemList.length} 条记录</div>
        <div className="flex space-x-2">
          <button
            className="rounded-md border border-gray-300 bg-white px-3 py-1 text-gray-500 disabled:opacity-50"
            disabled
          >
            上一页
          </button>
          <button className="rounded-md border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50">
            下一页
          </button>
        </div>
      </div>
    </div>
  );
}
