export interface Suggestion {
  icon: string;
  label: string;
  labelZh: string;
  prompt: string;
}

const CONTEXT_SUGGESTIONS: Record<string, Suggestion[]> = {
  // CRM
  'detail:crm_lead': [
    {
      icon: '📊',
      label: 'Summarize lead',
      labelZh: '总结线索',
      prompt: '请总结这个线索的关键信息和历史',
    },
    {
      icon: '✉️',
      label: 'Draft follow-up',
      labelZh: '生成跟进邮件',
      prompt: '请为这个线索生成一封跟进邮件',
    },
    {
      icon: '📈',
      label: 'Win probability',
      labelZh: '分析成交概率',
      prompt: '分析这个线索的成交概率和建议',
    },
    {
      icon: '📅',
      label: 'Create task',
      labelZh: '创建跟进任务',
      prompt: '为这个线索创建一个跟进任务',
    },
  ],
  'detail:crm_opportunity': [
    {
      icon: '📊',
      label: 'Deal summary',
      labelZh: '商机摘要',
      prompt: '总结这个商机的状态和关键信息',
    },
    {
      icon: '💰',
      label: 'Revenue forecast',
      labelZh: '营收预测',
      prompt: '预测这个商机的营收和时间线',
    },
    {
      icon: '📋',
      label: 'Next steps',
      labelZh: '建议下一步',
      prompt: '建议推进这个商机的下一步行动',
    },
  ],
  'detail:crm_complaint': [
    {
      icon: '🔍',
      label: 'Analyze complaint',
      labelZh: '分析投诉',
      prompt: '分析这个投诉的严重程度和处理建议',
    },
    { icon: '✉️', label: 'Draft response', labelZh: '生成回复', prompt: '生成一封专业的客户回复' },
  ],
  'list:crm_lead': [
    {
      icon: '📊',
      label: 'Lead analysis',
      labelZh: '线索分析',
      prompt: '分析当前线索的状态和来源分布',
    },
    {
      icon: '🎯',
      label: 'Conversion tips',
      labelZh: '转化建议',
      prompt: '给出提高线索转化率的建议',
    },
  ],

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
  const exact = CONTEXT_SUGGESTIONS[`${pageType}:${modelCode}`];
  if (exact) return exact;
  const wildcard = CONTEXT_SUGGESTIONS[`${pageType}:*`];
  if (wildcard) return wildcard;
  return [];
}

export default CONTEXT_SUGGESTIONS;
