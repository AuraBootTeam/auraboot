import React from 'react';
import { useLoaderData } from 'react-router';

export default function FormList() {
  const data = useLoaderData() as { schema?: any };
  return (
    <div className="p-6">
      <div className="rounded-md border border-gray-200 bg-white p-4">
        <h2 className="text-lg font-medium text-gray-900">Form List 占位页面</h2>
        <p className="mt-2 text-sm text-gray-600">TODO: 替换为真实的表单列表渲染逻辑</p>
      </div>
      <pre className="mt-4 overflow-auto rounded-md bg-gray-900 p-4 text-xs text-gray-100">
        {JSON.stringify(data?.schema ?? {}, null, 2)}
      </pre>
    </div>
  );
}
