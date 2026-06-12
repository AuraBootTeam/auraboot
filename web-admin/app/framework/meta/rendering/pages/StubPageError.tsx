import React from 'react';

export interface StubPageErrorProps {
  /** Resolved page key (e.g. "webhook_delivery_log_list"). */
  pageKey?: string;
  /** Model/table name, used to derive the key when pageKey is absent. */
  tableName?: string;
  /** Page kind, combined with tableName to derive the key. */
  kind?: string;
}

/**
 * Item-3 fail-fast state for an unconfigured platform stub page.
 *
 * Replaces the misleading empty shell (raw-code title + zero-column table +
 * "no data") that an {@code auto_created} placeholder page renders. Surfaces an
 * explicit, diagnosable error naming the page and the two most likely causes
 * (just-published model not yet configured, or a renamed model whose derived
 * pageKey was not synced). Deliberately does not fall back to a default render.
 */
export const StubPageError: React.FC<StubPageErrorProps> = ({ pageKey, tableName, kind }) => {
  const id =
    pageKey || (tableName && kind ? `${tableName}_${kind}` : tableName) || '(unknown)';

  return (
    <div
      data-testid="page-stub-error"
      className="mx-auto mt-8 max-w-2xl rounded-lg border border-red-200 bg-red-50 p-6"
    >
      <h3 className="mb-2 text-lg font-medium text-red-800">此页面尚未配置内容</h3>
      <p className="mb-3 text-sm text-red-700">
        页面{' '}
        <code className="rounded bg-red-100 px-1 py-0.5 font-mono text-xs">{id}</code>{' '}
        是平台自动生成的未配置占位页(auto_created stub),没有可展示的内容配置。
      </p>
      <p className="mb-1 text-sm text-red-700">最可能的原因:</p>
      <ul className="mb-3 list-disc pl-5 text-sm text-red-700">
        <li>模型刚发布,list / form / detail 页还没有在 Page Designer 中配置;</li>
        <li>
          模型改名后,派生 pageKey(<code className="font-mono text-xs">&lt;model&gt;_list</code>{' '}
          等)未同步 —— 菜单指向了这个占位页。
        </li>
      </ul>
      <p className="text-sm text-red-700">
        请在 Page Designer 中配置该页面,或检查模型改名后派生 pageKey 是否已同步。
      </p>
    </div>
  );
};

export default StubPageError;
