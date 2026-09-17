export interface Suggestion {
  icon: string;
  label: string;
  labelZh: string;
  prompt: string;
}

const CONTEXT_SUGGESTIONS: Record<string, Suggestion[]> = {
  // PCBA Procurement
  'list:pe_procurement_comparison': [
    {
      icon: '📊',
      label: 'Compare suppliers',
      labelZh: '生成供应商比价建议',
      prompt:
        '请使用 pe_procurement_comparison_supplier_options 查询 PCBA-DEMO-RM-001 的供应商价格、交期、评分和资质证据，先给出推荐和风险说明，确认后再生成采购比价草稿，不要自动提交复核。',
    },
  ],

  // PCBA Quality
  'list:qc_defect_record': [
    {
      icon: '⚠️',
      label: 'Analyze anomalies',
      labelZh: '生成质量异常分析',
      prompt:
        '请使用 qc_quality_anomaly_trend、qc_quality_batch_correlation 和 qc_quality_capa_context 分析近期 PCBA 质量异常趋势、批次关联和缺陷上下文，先给出证据表、根因假设和风险说明；只有我明确确认后，才生成 CAPA 草稿，不要自动放行、拒收、关闭或处置任何记录。',
    },
  ],

  // Project Management
  'detail:pm_project': [
    {
      icon: '📋',
      label: 'Project summary',
      labelZh: '项目总结',
      prompt: '总结这个项目的当前状态和风险',
    },
    {
      icon: '📅',
      label: 'Weekly report',
      labelZh: '生成周报',
      prompt: '生成这个项目的本周工作报告',
    },
    {
      icon: '⚠️',
      label: 'Risk analysis',
      labelZh: '风险分析',
      prompt: '分析这个项目的风险点和建议',
    },
  ],
  'detail:pm_task': [
    {
      icon: '📊',
      label: 'Task analysis',
      labelZh: '任务分析',
      prompt: '分析这个任务的完成情况和依赖',
    },
    {
      icon: '💡',
      label: 'Suggest assignee',
      labelZh: '建议负责人',
      prompt: '根据任务内容建议合适的负责人',
    },
  ],

  // ERP / Manufacturing
  'detail:pe_production_order': [
    {
      icon: '🏭',
      label: 'Production status',
      labelZh: '生产状态',
      prompt: '总结这个生产订单的进度和问题',
    },
    {
      icon: '📦',
      label: 'Material check',
      labelZh: '物料检查',
      prompt: '检查这个订单的物料齐套情况',
    },
  ],

  // Wildcard fallbacks
  'detail:*': [
    { icon: '📊', label: 'Summarize', labelZh: '总结记录', prompt: '总结这条记录的关键信息' },
    {
      icon: '✏️',
      label: 'Suggest actions',
      labelZh: '建议操作',
      prompt: '根据当前记录状态，建议下一步操作',
    },
    {
      icon: '❓',
      label: 'Explain fields',
      labelZh: '解释字段',
      prompt: '解释这条记录各字段的含义',
    },
  ],
  'list:*': [
    {
      icon: '📊',
      label: 'Data analysis',
      labelZh: '数据分析',
      prompt: '分析当前列表数据的分布和趋势',
    },
    {
      icon: '🔍',
      label: 'Find patterns',
      labelZh: '发现规律',
      prompt: '找出这些数据中的规律和异常',
    },
  ],
  'dashboard:*': [
    {
      icon: '📊',
      label: 'Interpret metrics',
      labelZh: '解读指标',
      prompt: '解读当前仪表盘的关键指标',
    },
    { icon: '📈', label: 'Trend analysis', labelZh: '趋势分析', prompt: '分析指标的趋势和异常' },
  ],
  'form:*': [
    {
      icon: '✅',
      label: 'Validate data',
      labelZh: '检查数据',
      prompt: '检查当前表单数据是否完整合理',
    },
    {
      icon: '💡',
      label: 'Suggest values',
      labelZh: '建议填写',
      prompt: '根据已有信息建议表单字段值',
    },
  ],
};

export function getSuggestions(pageType: string, modelCode: string): Suggestion[] {
  const provider = getKernel().contributionRegistry.getPrimaryService(
    'aura.aurabot.suggestions',
  )?.provider as
    | { getSuggestions?: (pageType: string, modelCode: string) => Suggestion[] }
    | undefined;
  const contributed = provider?.getSuggestions?.(pageType, modelCode);
  if (contributed?.length) return contributed;
  const exact = CONTEXT_SUGGESTIONS[`${pageType}:${modelCode}`];
  if (exact) return exact;
  const wildcard = CONTEXT_SUGGESTIONS[`${pageType}:*`];
  if (wildcard) return wildcard;
  return [];
}

export default CONTEXT_SUGGESTIONS;
import { getKernel } from '~/framework/bootstrap';
