import React from 'react';
import { CircleAlert } from 'lucide-react';
import type { AuthoringSession, AuthoringValidationIssue } from './types';

export function AuthoringValidationNotice({
  session,
  maxVisibleIssues,
}: {
  session: AuthoringSession;
  maxVisibleIssues?: number;
}) {
  if (session.validationState !== 'INVALID') return null;
  const issues = session.validation?.issues ?? [];
  const errorCount = session.validation?.errorCount ?? Math.max(1, issues.length);
  const visibleIssues = maxVisibleIssues ? issues.slice(0, maxVisibleIssues) : issues;

  return (
    <section
      className="rounded-md border border-red-300 bg-red-50 px-3 py-3 text-sm text-red-950"
      aria-label="ChangeSet 校验失败"
      data-testid="authoring-validation-notice"
      role="alert"
    >
      <div className="flex items-start gap-2">
        <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="font-semibold">当前 revision 有 {errorCount} 个校验错误，尚未提交评审</div>
          <p className="mt-1 text-xs leading-5">
            草稿已保存，ChangeSet 仍保持可编辑；修复下列位置并保存为新 revision 后，再重新提交。
          </p>
          {issues.length > 0 ? (
            <ul className="mt-2 grid gap-2" data-testid="authoring-validation-issues">
              {visibleIssues.map((issue, index) => (
                <li
                  key={`${issue.code}:${issue.changeItemPid ?? index}`}
                  className="rounded border border-red-200 bg-white px-2 py-2 text-xs"
                >
                  <div className="font-semibold">{issueLabel(issue)}</div>
                  <div className="mt-0.5 flex flex-wrap gap-x-3 gap-y-1 font-mono text-red-800">
                    {issue.blockId ? <span>block: {issue.blockId}</span> : null}
                    <span>path: {issue.propertyPath}</span>
                    <span>code: {issue.code}</span>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 text-xs">刷新工作区以加载服务端返回的错误位置。</p>
          )}
          {maxVisibleIssues && issues.length > maxVisibleIssues ? (
            <p className="mt-2 text-xs">
              另有 {issues.length - maxVisibleIssues} 个错误，请在专业工作台继续查看。
            </p>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function issueLabel(issue: AuthoringValidationIssue): string {
  return (
    {
      PAGE_SNAPSHOT_INVALID: '页面草稿结构无效',
      PAGE_ID_REQUIRED: '页面缺少稳定标识',
      BLOCKS_ARRAY_REQUIRED: '页面区块集合格式无效',
      BLOCK_OBJECT_REQUIRED: '区块节点格式无效',
      BLOCK_ID_REQUIRED: '区块缺少稳定 ID',
      BLOCK_ID_DUPLICATE: '区块稳定 ID 重复',
      BLOCK_TYPE_REQUIRED: '区块缺少类型',
      CHILD_BLOCKS_ARRAY_REQUIRED: '子区块集合格式无效',
      CHANGE_TARGET_MISSING: '变更目标已不存在',
      CHANGED_PROPERTY_MISSING: '保存后的属性未找到',
      DENSITY_INVALID: '密度值不受支持',
      PAGE_SIZE_INVALID: '分页大小必须在 1–1000 之间',
      LAYOUT_SPAN_INVALID: '布局跨度必须在 1–24 之间',
      DEFAULT_FILTER_INVALID: '默认筛选必须是结构化条件',
      DEFAULT_SORT_INVALID: '默认排序必须是列表',
      DATA_SOURCE_MODEL_REQUIRED: '数据源必须指定模型',
    }[issue.code] ?? '配置值未通过服务端校验'
  );
}
