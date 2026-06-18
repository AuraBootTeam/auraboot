/**
 * Shared i18n strings for all designer components.
 * Uses LocalizedText format (inline zh_CN / en_US) so no backend changes are needed.
 */

type L = Record<string, string>;

export const DESIGNER_I18N = {
  // ── Empty state ───────────────────────────────────────────────
  emptyState: {
    dragToCanvas: {
      'zh-CN': '从左侧拖拽组件到这里',
      'en-US': 'Drag components from the left panel here',
    } as L,
    orClickToAdd: {
      'zh-CN': '或点击组件添加到画布',
      'en-US': 'Or click a component to add it to the canvas',
    } as L,
    clickToAdd: {
      'zh-CN': '点击左侧面板中的块添加到此处',
      'en-US': 'Click a block from the palette to add it here',
    } as L,
    startDesign: {
      'zh-CN': '从左侧拖拽组件到这里开始设计',
      'en-US': 'Drag components from the left panel to start designing',
    } as L,
    canvasEmpty: {
      'zh-CN': '画布为空',
      'en-US': 'Canvas is empty',
    } as L,
    dragFieldsOrComponents: {
      'zh-CN': '从左侧拖拽字段或组件到此处',
      'en-US': 'Drag fields or components from the left panel here',
    } as L,
  },

  // ── Drop zone ─────────────────────────────────────────────────
  dropZone: {
    dropHere: {
      'zh-CN': '拖拽组件到此处',
      'en-US': 'Drop component here',
    } as L,
    placeHere: {
      'zh-CN': '放置在这里',
      'en-US': 'Place here',
    } as L,
  },

  // ── Widget palette (dashboard) ────────────────────────────────
  palette: {
    title: {
      'zh-CN': '组件库',
      'en-US': 'Components',
    } as L,
    dragHint: {
      'zh-CN': '拖拽组件到画布',
      'en-US': 'Drag components to canvas',
    } as L,
  },

  // ── Studio / Page Designer ────────────────────────────────────
  studio: {
    dragComponent: {
      'zh-CN': '拖拽组件',
      'en-US': 'Drag Components',
    } as L,
    dragComponentDesc: {
      'zh-CN': '从左侧面板拖拽组件到画布',
      'en-US': 'Drag components from the left panel to the canvas',
    } as L,
    useTemplate: {
      'zh-CN': '使用模板',
      'en-US': 'Use Template',
    } as L,
    useTemplateDesc: {
      'zh-CN': '从预设模板快速开始',
      'en-US': 'Quick start from preset templates',
    } as L,
    importPage: {
      'zh-CN': '导入页面',
      'en-US': 'Import Page',
    } as L,
    importPageDesc: {
      'zh-CN': '导入已有的页面配置',
      'en-US': 'Import existing page configuration',
    } as L,
    startDesignTitle: {
      'zh-CN': '开始设计你的{mode}页面',
      'en-US': 'Start designing your {mode} page',
    } as L,
    startDesignDesc: {
      'zh-CN': '{description}。选择下面的方式开始你的设计之旅。',
      'en-US': '{description}. Choose a method below to start your design journey.',
    } as L,
    quickTips: {
      'zh-CN': '快捷提示',
      'en-US': 'Quick Tips',
    } as L,
    tipSave: {
      'zh-CN': '按 Ctrl+S 保存，Ctrl+Z 撤销',
      'en-US': 'Press Ctrl+S to save, Ctrl+Z to undo',
    } as L,
    tipPreview: {
      'zh-CN': '点击预览按钮查看实际效果',
      'en-US': 'Click the preview button to see the actual result',
    } as L,
    tipMultiselect: {
      'zh-CN': '按住 Shift 可多选组件',
      'en-US': 'Hold Shift to multi-select components',
    } as L,
    viewShortcuts: {
      'zh-CN': '查看所有快捷键',
      'en-US': 'View all shortcuts',
    } as L,
    fullscreenMode: {
      'zh-CN': '全屏模式',
      'en-US': 'Fullscreen mode',
    } as L,
    save: {
      'zh-CN': '保存',
      'en-US': 'Save',
    } as L,
    undo: {
      'zh-CN': '撤销',
      'en-US': 'Undo',
    } as L,
  },

  // ── DragCanvas ────────────────────────────────────────────────
  dragCanvas: {
    canvasTitle: {
      'zh-CN': '画布 ({columns} 列)',
      'en-US': 'Canvas ({columns} columns)',
    } as L,
    cols2: {
      'zh-CN': '2列',
      'en-US': '2 cols',
    } as L,
    cols4: {
      'zh-CN': '4列',
      'en-US': '4 cols',
    } as L,
    cols6: {
      'zh-CN': '6列',
      'en-US': '6 cols',
    } as L,
    components: {
      'zh-CN': '组件',
      'en-US': 'Components',
    } as L,
    rows: {
      'zh-CN': '行',
      'en-US': 'Rows',
    } as L,
    conflicts: {
      'zh-CN': '冲突',
      'en-US': 'Conflicts',
    } as L,
    grid: {
      'zh-CN': '网格',
      'en-US': 'Grid',
    } as L,
    gap: {
      'zh-CN': '间距',
      'en-US': 'Gap',
    } as L,
    padding: {
      'zh-CN': '内边距',
      'en-US': 'Padding',
    } as L,
    selected: {
      'zh-CN': '已选中 {count} 个组件',
      'en-US': '{count} component(s) selected',
    } as L,
    selectedIndicator: {
      'zh-CN': '已选中',
      'en-US': 'Selected',
    } as L,
    componentContent: {
      'zh-CN': '组件内容',
      'en-US': 'Component content',
    } as L,
  },

  // ── GridContainer ─────────────────────────────────────────────
  gridContainer: {
    fields: {
      'zh-CN': '字段',
      'en-US': 'fields',
    } as L,
    componentsLabel: {
      'zh-CN': '组件',
      'en-US': 'components',
    } as L,
  },
  // ── AutoSave ──────────────────────────────────────────────────
  autoSave: {
    saving: {
      'zh-CN': '保存中...',
      'en-US': 'Saving...',
    } as L,
    saved: {
      'zh-CN': '已保存',
      'en-US': 'Saved',
    } as L,
    error: {
      'zh-CN': '保存失败',
      'en-US': 'Save failed',
    } as L,
    offline: {
      'zh-CN': '离线模式',
      'en-US': 'Offline',
    } as L,
    unsaved: {
      'zh-CN': '未保存',
      'en-US': 'Unsaved',
    } as L,
    justNow: {
      'zh-CN': '刚刚',
      'en-US': 'just now',
    } as L,
    minutesAgo: {
      'zh-CN': '{n}分钟前',
      'en-US': '{n}m ago',
    } as L,
    hoursAgo: {
      'zh-CN': '{n}小时前',
      'en-US': '{n}h ago',
    } as L,
    saveNow: {
      'zh-CN': '立即保存',
      'en-US': 'Save now',
    } as L,
    noUnsavedChanges: {
      'zh-CN': '没有未保存的更改',
      'en-US': 'No unsaved changes',
    } as L,
    retry: {
      'zh-CN': '重试',
      'en-US': 'Retry',
    } as L,
    retryTooltip: {
      'zh-CN': '重试保存',
      'en-US': 'Retry save',
    } as L,
    offlineHint: {
      'zh-CN': '离线模式，将在网络恢复后自动保存',
      'en-US': 'Offline — will auto-save when connection is restored',
    } as L,
    unsavedChangesWarning: {
      'zh-CN': '您有未保存的更改，确定要离开吗？',
      'en-US': 'You have unsaved changes. Are you sure you want to leave?',
    } as L,
    versionDescription: {
      'zh-CN': '自动保存',
      'en-US': 'Auto save',
    } as L,
  },

  // ── Toolbar permissions ───────────────────────────────────────
  // Backend PageSchemaController uses a single page.page.manage permission for all
  // mutation endpoints (save / publish / delete). Fine-grained keys don't exist in
  // the RBAC registry, so we expose a single missingManage message for all actions.
  permissions: {
    missingManage: {
      'zh-CN': '您没有管理此页面的权限',
      'en-US': 'You do not have permission to manage this page',
    } as L,
  },

  // ── Dashboard viewer ───────────────────────────────────────────
  viewer: {
    noData: {
      'zh-CN': '暂无仪表盘数据',
      'en-US': 'No dashboard data',
    } as L,
    configureHint: {
      'zh-CN': '请在仪表盘设计器中配置概览图表',
      'en-US': 'Configure overview charts in the dashboard designer',
    } as L,
  },

  // ── Unified designer ───────────────────────────────────────────
  unified: {
    canvasKind: {
      form: { 'zh-CN': '表单', 'en-US': 'Form' } as L,
      list: { 'zh-CN': '列表', 'en-US': 'List' } as L,
      detail: { 'zh-CN': '详情', 'en-US': 'Detail' } as L,
      dashboard: { 'zh-CN': '仪表盘', 'en-US': 'Dashboard' } as L,
      composite: { 'zh-CN': '组合页面', 'en-US': 'Composite' } as L,
    },
    mode: { 'zh-CN': '模式', 'en-US': 'Mode' } as L,
    untitledPage: { 'zh-CN': '未命名页面', 'en-US': 'Untitled page' } as L,
    deleteBlock: { 'zh-CN': '删除', 'en-US': 'Delete' } as L,
    aiLockBadge: { 'zh-CN': 'AI 锁定', 'en-US': 'AI locked' } as L,

    // Multi-select batch bar (shift / cmd / ctrl + click on the canvas)
    multiSelectCount: { 'zh-CN': '已选 {count} 项', 'en-US': '{count} selected' } as L,
    multiSelectDelete: { 'zh-CN': '删除所选', 'en-US': 'Delete selected' } as L,
    multiSelectClear: { 'zh-CN': '清除选择', 'en-US': 'Clear selection' } as L,

    // Palette category headers
    category: {
      page: { 'zh-CN': '页面', 'en-US': 'Page' } as L,
      form: { 'zh-CN': '表单', 'en-US': 'Form' } as L,
      list: { 'zh-CN': '列表', 'en-US': 'List' } as L,
      detail: { 'zh-CN': '详情', 'en-US': 'Detail' } as L,
      dashboard: { 'zh-CN': '仪表盘', 'en-US': 'Dashboard' } as L,
      action: { 'zh-CN': '操作', 'en-US': 'Action' } as L,
      workflow: { 'zh-CN': '流程', 'en-US': 'Workflow' } as L,
      layout: { 'zh-CN': '布局', 'en-US': 'Layout' } as L,
    } as Record<string, L>,

    // Resource panel
    tabOutline: { 'zh-CN': '大纲', 'en-US': 'Outline' } as L,
    tabBlocks: { 'zh-CN': '区块', 'en-US': 'Blocks' } as L,
    tabFields: { 'zh-CN': '字段', 'en-US': 'Fields' } as L,
    pageTree: { 'zh-CN': '页面结构', 'en-US': 'Page tree' } as L,
    target: { 'zh-CN': '目标', 'en-US': 'Target' } as L,
    pageRoot: { 'zh-CN': '页面根', 'en-US': 'Page root' } as L,
    fields: { 'zh-CN': '字段', 'en-US': 'Fields' } as L,
    model: { 'zh-CN': '模型', 'en-US': 'Model' } as L,
    searchFields: { 'zh-CN': '搜索字段', 'en-US': 'Search fields' } as L,
    noFieldsMatch: { 'zh-CN': '无匹配字段', 'en-US': 'No fields match' } as L,
    added: { 'zh-CN': '已添加', 'en-US': 'Added' } as L,
    customField: { 'zh-CN': '自定义字段', 'en-US': 'Custom field' } as L,
    virtual: { 'zh-CN': '虚拟', 'en-US': 'Virtual' } as L,
    fieldCount: { 'zh-CN': '{count} 个字段', 'en-US': '{count} fields' } as L,
    virtualCount: { 'zh-CN': '{count} 虚拟', 'en-US': '{count} virtual' } as L,
    dragOrDoubleClick: { 'zh-CN': '拖拽或双击添加', 'en-US': 'Drag or double-click to add' } as L,
    noModelBound: { 'zh-CN': '该页面未绑定模型，无可用字段', 'en-US': 'No model bound to this page' } as L,

    // Field library groups
    fieldGroup: {
      text: { 'zh-CN': '文本', 'en-US': 'Text' } as L,
      number: { 'zh-CN': '数值', 'en-US': 'Number' } as L,
      choice: { 'zh-CN': '选项', 'en-US': 'Choice' } as L,
      datetime: { 'zh-CN': '日期时间', 'en-US': 'Date & time' } as L,
      boolean: { 'zh-CN': '布尔', 'en-US': 'Boolean' } as L,
      relation: { 'zh-CN': '关联', 'en-US': 'Relation' } as L,
      file: { 'zh-CN': '附件', 'en-US': 'File' } as L,
      json: { 'zh-CN': 'JSON', 'en-US': 'JSON' } as L,
      other: { 'zh-CN': '其他', 'en-US': 'Other' } as L,
    },

    // Toolbar
    untitled: { 'zh-CN': '未命名设计器', 'en-US': 'Unified Designer' } as L,
    pages: { 'zh-CN': '页面', 'en-US': 'Pages' } as L,
    // C4 — page-kind switch control.
    kindSwitchLabel: { 'zh-CN': '页面类型', 'en-US': 'Page kind' } as L,
    kindForm: { 'zh-CN': '表单', 'en-US': 'Form' } as L,
    kindList: { 'zh-CN': '列表', 'en-US': 'List' } as L,
    kindDetail: { 'zh-CN': '详情', 'en-US': 'Detail' } as L,
    kindDashboard: { 'zh-CN': '仪表盘', 'en-US': 'Dashboard' } as L,
    kindSwitchBlocked: {
      'zh-CN': '当前有 {n} 个块在该类型下不兼容,请先移除',
      'en-US': '{n} block(s) are incompatible with this kind — remove them first',
    } as L,
    preview: { 'zh-CN': '预览', 'en-US': 'Preview' } as L,
    history: { 'zh-CN': '历史', 'en-US': 'History' } as L,
    undo: { 'zh-CN': '撤销', 'en-US': 'Undo' } as L,
    redo: { 'zh-CN': '重做', 'en-US': 'Redo' } as L,
    edit: { 'zh-CN': '编辑', 'en-US': 'Edit' } as L,
    layout: { 'zh-CN': '布局', 'en-US': 'Layout' } as L,
    save: { 'zh-CN': '保存', 'en-US': 'Save' } as L,
    saving: { 'zh-CN': '保存中', 'en-US': 'Saving' } as L,
    statusSaved: { 'zh-CN': '已保存', 'en-US': 'Saved' } as L,
    statusUnsaved: { 'zh-CN': '未保存', 'en-US': 'Unsaved' } as L,
    statusInvalid: { 'zh-CN': '校验失败 {count}', 'en-US': 'Invalid {count}' } as L,
    statusError: { 'zh-CN': '保存失败', 'en-US': 'Save failed' } as L,
    unsavedChanges: { 'zh-CN': '有未保存的更改', 'en-US': 'Unsaved changes' } as L,
    stay: { 'zh-CN': '留下', 'en-US': 'Stay' } as L,
    leave: { 'zh-CN': '离开', 'en-US': 'Leave' } as L,

    // Publish / unpublish (POST /api/pages/{pid}/publish — page.page.manage)
    publish: { 'zh-CN': '发布', 'en-US': 'Publish' } as L,
    publishing: { 'zh-CN': '发布中', 'en-US': 'Publishing' } as L,
    published: { 'zh-CN': '已发布', 'en-US': 'Published' } as L,
    unpublish: { 'zh-CN': '取消发布', 'en-US': 'Unpublish' } as L,
    unpublishing: { 'zh-CN': '取消发布中', 'en-US': 'Unpublishing' } as L,
    publishSaveFirst: {
      'zh-CN': '请先保存页面再发布',
      'en-US': 'Save the page before publishing',
    } as L,

    // Export / import (pure client-side JSON download / upload)
    exportPage: { 'zh-CN': '导出', 'en-US': 'Export' } as L,
    importPage: { 'zh-CN': '导入', 'en-US': 'Import' } as L,
    importInvalid: {
      'zh-CN': '导入失败：不是有效的页面 JSON（需 schemaVersion 3）',
      'en-US': 'Import failed: not a valid page JSON (schemaVersion 3 required)',
    } as L,

    // Version history / snapshot / rollback
    // (GET/POST /api/pages/{pid}/versions, POST .../rollback/{historyId})
    versions: { 'zh-CN': '版本', 'en-US': 'Versions' } as L,
    versionHistory: { 'zh-CN': '版本历史', 'en-US': 'Version history' } as L,
    versionsSaveFirst: {
      'zh-CN': '请先保存页面再查看版本',
      'en-US': 'Save the page before viewing versions',
    } as L,
    versionCreateSnapshot: { 'zh-CN': '创建快照', 'en-US': 'Create snapshot' } as L,
    versionCreatingSnapshot: { 'zh-CN': '创建中…', 'en-US': 'Creating…' } as L,
    versionSnapshotReason: {
      'zh-CN': '快照说明（可选）',
      'en-US': 'Snapshot description (optional)',
    } as L,
    versionRollback: { 'zh-CN': '回滚', 'en-US': 'Roll back' } as L,
    versionRollingBack: { 'zh-CN': '回滚中…', 'en-US': 'Rolling back…' } as L,
    versionRollbackConfirm: {
      'zh-CN': '确认回滚到此版本？当前画布将被替换。',
      'en-US': 'Roll back to this version? The current canvas will be replaced.',
    } as L,
    versionRollbackConfirmYes: { 'zh-CN': '确认回滚', 'en-US': 'Confirm rollback' } as L,
    versionRollbackCancel: { 'zh-CN': '取消', 'en-US': 'Cancel' } as L,
    versionCurrent: { 'zh-CN': '当前', 'en-US': 'Current' } as L,
    versionLoading: { 'zh-CN': '加载版本中…', 'en-US': 'Loading versions…' } as L,
    versionEmpty: { 'zh-CN': '暂无版本记录', 'en-US': 'No versions yet' } as L,
    versionClose: { 'zh-CN': '关闭', 'en-US': 'Close' } as L,
    versionNumber: { 'zh-CN': '版本', 'en-US': 'Version' } as L,
    versionOperationCreate: { 'zh-CN': '创建', 'en-US': 'Create' } as L,
    versionOperationUpdate: { 'zh-CN': '更新', 'en-US': 'Update' } as L,
    versionOperationPublish: { 'zh-CN': '发布', 'en-US': 'Publish' } as L,
    versionOperationArchive: { 'zh-CN': '归档', 'en-US': 'Archive' } as L,
    versionOperationDelete: { 'zh-CN': '删除', 'en-US': 'Delete' } as L,
    versionOperationRestore: { 'zh-CN': '回滚', 'en-US': 'Rollback' } as L,
    versionOperationSnapshot: { 'zh-CN': '快照', 'en-US': 'Snapshot' } as L,

    // Version compare / diff viewer
    // (GET /api/pages/{pid}/versions/{from}/compare/{to})
    versionCompareEnter: { 'zh-CN': '对比版本', 'en-US': 'Compare versions' } as L,
    versionCompareExit: { 'zh-CN': '退出对比', 'en-US': 'Exit compare' } as L,
    versionCompareHint: {
      'zh-CN': '选择两个版本进行对比',
      'en-US': 'Select two versions to compare',
    } as L,
    versionCompareRun: { 'zh-CN': '对比所选版本', 'en-US': 'Compare selected' } as L,
    versionCompareComputing: { 'zh-CN': '对比中…', 'en-US': 'Comparing…' } as L,
    versionCompareBack: { 'zh-CN': '返回版本列表', 'en-US': 'Back to versions' } as L,
    versionCompareSelectedCount: {
      'zh-CN': '已选 {n} / 2',
      'en-US': '{n} of 2 selected',
    } as L,
    versionDiffSource: { 'zh-CN': '源版本', 'en-US': 'Source' } as L,
    versionDiffTarget: { 'zh-CN': '目标版本', 'en-US': 'Target' } as L,
    versionDiffAdded: { 'zh-CN': '新增', 'en-US': 'Added' } as L,
    versionDiffRemoved: { 'zh-CN': '删除', 'en-US': 'Removed' } as L,
    versionDiffModified: { 'zh-CN': '修改', 'en-US': 'Modified' } as L,
    versionDiffSummary: {
      'zh-CN': '{added} 新增 / {removed} 删除 / {modified} 修改',
      'en-US': '{added} added / {removed} removed / {modified} modified',
    } as L,
    versionDiffNoChanges: {
      'zh-CN': '两个版本之间没有差异',
      'en-US': 'No differences between the two versions',
    } as L,
    versionDiffSourceValue: { 'zh-CN': '源值', 'en-US': 'Source value' } as L,
    versionDiffTargetValue: { 'zh-CN': '目标值', 'en-US': 'Target value' } as L,
    versionDiffExpand: { 'zh-CN': '展开', 'en-US': 'Expand' } as L,
    versionDiffCollapse: { 'zh-CN': '收起', 'en-US': 'Collapse' } as L,
    versionDiffEmptyValue: { 'zh-CN': '（空）', 'en-US': '(empty)' } as L,

    // Runtime preview (RecursiveBlockRenderer)
    runtime: {
      aiReviewHint: {
        'zh-CN': '应用到表单前请先检查生成的建议。',
        'en-US': 'Review generated suggestions before applying them to the form.',
      } as L,
      suggestionsApplied: { 'zh-CN': '建议已应用', 'en-US': 'Suggestions applied' } as L,
      noSuggestions: { 'zh-CN': '暂无建议', 'en-US': 'No suggestions' } as L,
      noWorkflowTasks: { 'zh-CN': '暂无流程任务', 'en-US': 'No workflow tasks' } as L,
      assignee: { 'zh-CN': '处理人', 'en-US': 'Assignee' } as L,
      due: { 'zh-CN': '截止', 'en-US': 'Due' } as L,
      noActivity: { 'zh-CN': '暂无活动', 'en-US': 'No activity yet' } as L,
      noFieldChanges: { 'zh-CN': '暂无字段变更', 'en-US': 'No field changes' } as L,
      field: { 'zh-CN': '字段', 'en-US': 'Field' } as L,
      from: { 'zh-CN': '原值', 'en-US': 'From' } as L,
      to: { 'zh-CN': '新值', 'en-US': 'To' } as L,
      by: { 'zh-CN': '操作人', 'en-US': 'By' } as L,
      link: { 'zh-CN': '链接', 'en-US': 'Link' } as L,
      noOptionsConfigured: { 'zh-CN': '未配置选项', 'en-US': 'No options configured' } as L,
      selectPlaceholder: { 'zh-CN': '请选择…', 'en-US': 'Select...' } as L,
      all: { 'zh-CN': '全部', 'en-US': 'All' } as L,
      allRecords: { 'zh-CN': '全部记录…', 'en-US': 'All records...' } as L,
      pickerOptionsFailed: { 'zh-CN': '选择项加载失败', 'en-US': 'Picker options failed' } as L,
      required: { 'zh-CN': '必填', 'en-US': 'Required' } as L,
      noRecords: { 'zh-CN': '暂无记录', 'en-US': 'No records' } as L,
      confirm: { 'zh-CN': '确认', 'en-US': 'Confirm' } as L,
      running: { 'zh-CN': '执行中', 'en-US': 'Running' } as L,
      noData: { 'zh-CN': '暂无数据', 'en-US': 'No data' } as L,
      submitted: { 'zh-CN': '已提交', 'en-US': 'Submitted' } as L,
      noTabsConfigured: { 'zh-CN': '未配置标签页', 'en-US': 'No tabs configured.' } as L,
      searchRecords: { 'zh-CN': '搜索记录…', 'en-US': 'Search records...' } as L,
      loadingRecords: { 'zh-CN': '加载记录中…', 'en-US': 'Loading records...' } as L,
      loadingLiveData: { 'zh-CN': '加载实时数据中…', 'en-US': 'Loading live data...' } as L,
      selectRecord: { 'zh-CN': '选择记录…', 'en-US': 'Select record...' } as L,
      // Workbench-block (metric-strip / status-banner) representative preview.
      // The designer canvas shows a config-driven placeholder; the live /p/ page
      // renders the fully data-bound platform renderer.
      noMetricsConfigured: {
        'zh-CN': '未配置指标',
        'en-US': 'No metrics configured',
      } as L,
      statusBannerNotConfigured: {
        'zh-CN': '未配置状态映射',
        'en-US': 'No status mapping configured',
      } as L,
      workbenchPreviewHint: {
        'zh-CN': '设计器内为代表性预览,实时数据在已发布页面渲染',
        'en-US': 'Representative preview — live data renders on the published page',
      } as L,
      metricPlaceholderValue: { 'zh-CN': '—', 'en-US': '—' } as L,
      // Workbench-family batch 2 (workbench-action-bar / review-drawer /
      // evidence-panel / record-inspector / candidate-list / artifact-timeline)
      // representative previews. Same contract as metric-strip / status-banner:
      // the designer canvas shows a config-driven placeholder; the live /p/ page
      // renders the fully data-bound platform renderer.
      noActionsConfigured: { 'zh-CN': '未配置操作', 'en-US': 'No actions configured' } as L,
      noEvidenceSections: {
        'zh-CN': '未配置证据分区',
        'en-US': 'No evidence sections configured',
      } as L,
      noInspectorFields: {
        'zh-CN': '未配置检视字段',
        'en-US': 'No inspector fields configured',
      } as L,
      noCandidateFields: {
        'zh-CN': '未配置候选字段',
        'en-US': 'No candidate fields configured',
      } as L,
      noTimelineFields: {
        'zh-CN': '未配置时间线字段',
        'en-US': 'No timeline fields configured',
      } as L,
      reviewDrawerPreview: {
        'zh-CN': '行级复核浮层',
        'en-US': 'Row review drawer',
      } as L,
      reviewDrawerNotConfigured: {
        'zh-CN': '未配置复核上下文',
        'en-US': 'No review context configured',
      } as L,
      candidatesLabel: { 'zh-CN': '候选', 'en-US': 'Candidates' } as L,
      decisionLabel: { 'zh-CN': '决策', 'en-US': 'Decision' } as L,
      // Display / data blocks (stat-card / description / record-comments /
      // embedded-list) representative previews. Same contract as the workbench
      // family: the designer canvas shows a config-driven placeholder; the live
      // /p/ page renders the fully data-bound platform renderer.
      statCardNotConfigured: {
        'zh-CN': '未配置指标卡',
        'en-US': 'No stat card configured',
      } as L,
      descriptionEmpty: {
        'zh-CN': '未配置描述内容',
        'en-US': 'No description content',
      } as L,
      recordCommentsPreview: {
        'zh-CN': '记录评论(实时数据在记录详情页加载)',
        'en-US': 'Record comments — live thread loads on the record detail page',
      } as L,
      embeddedListNotConfigured: {
        'zh-CN': '未配置内嵌列表(需设置模型编码)',
        'en-US': 'No embedded list configured (set a model code)',
      } as L,
      embeddedListPreview: {
        'zh-CN': '内嵌列表(实时记录在已发布页面加载)',
        'en-US': 'Embedded list — live records load on the published page',
      } as L,
      // E2 batch (chart / rich-text / divider / toolbar / form-buttons / filters /
      // form-wizard / trace-graph / selection-info / gerber-viewer) representative
      // previews. Same contract: the designer canvas shows a config-driven
      // placeholder; the live /p/ page renders the fully data-bound platform renderer.
      chartNotConfigured: {
        'zh-CN': '未配置图表(需设置数据源或字段)',
        'en-US': 'No chart configured (set a data source or fields)',
      } as L,
      richTextEmpty: {
        'zh-CN': '未配置富文本内容',
        'en-US': 'No rich-text content',
      } as L,
      toolbarNoButtons: {
        'zh-CN': '未配置按钮',
        'en-US': 'No buttons configured',
      } as L,
      filtersNoFields: {
        'zh-CN': '未配置筛选字段',
        'en-US': 'No filter fields configured',
      } as L,
      filtersReset: { 'zh-CN': '重置', 'en-US': 'Reset' } as L,
      filtersSearch: { 'zh-CN': '搜索', 'en-US': 'Search' } as L,
      formWizardNoSteps: {
        'zh-CN': '未配置步骤',
        'en-US': 'No wizard steps configured',
      } as L,
      traceGraphNotConfigured: {
        'zh-CN': '未配置追溯数据源',
        'en-US': 'No trace data source configured',
      } as L,
      traceGraphNodeA: { 'zh-CN': '上游节点', 'en-US': 'Source node' } as L,
      traceGraphNodeB: { 'zh-CN': '下游节点', 'en-US': 'Target node' } as L,
      selectionInfoBoundTo: { 'zh-CN': '绑定状态', 'en-US': 'Bound to' } as L,
      gerberViewerNotConfigured: {
        'zh-CN': '未配置 Gerber 数据源',
        'en-US': 'No Gerber data source configured',
      } as L,
    },

    // Inspector shell
    inspector: { 'zh-CN': '检查器', 'en-US': 'Inspector' } as L,
    basic: { 'zh-CN': '基础', 'en-US': 'Basic' } as L,
    advancedJson: { 'zh-CN': '高级 JSON', 'en-US': 'Advanced JSON' } as L,
    unset: { 'zh-CN': '未设置', 'en-US': 'Unset' } as L,
    noSelection: { 'zh-CN': '未选择区块', 'en-US': 'No block selected' } as L,
    selectBlockHint: {
      'zh-CN': '在画布或大纲中选择一个区块',
      'en-US': 'Select a block on the canvas or outline.',
    } as L,
    apply: { 'zh-CN': '应用', 'en-US': 'Apply' } as L,
    invalidJson: { 'zh-CN': 'JSON 格式错误', 'en-US': 'Invalid JSON' } as L,
    jsonProps: { 'zh-CN': '属性', 'en-US': 'Props' } as L,
    jsonLayout: { 'zh-CN': '布局', 'en-US': 'Layout' } as L,
    jsonDataSource: { 'zh-CN': '数据源', 'en-US': 'Data source' } as L,
    jsonExtension: { 'zh-CN': '扩展', 'en-US': 'Extension' } as L,

    // Inspector field labels — keyed by the schema's English label, English fallback
    // for any label not listed (e.g. the advanced "* JSON" power-user fields).
    inspectorLabels: {
      Field: { 'zh-CN': '字段', 'en-US': 'Field' } as L,
      Label: { 'zh-CN': '标签', 'en-US': 'Label' } as L,
      Component: { 'zh-CN': '组件', 'en-US': 'Component' } as L,
      'Data type': { 'zh-CN': '数据类型', 'en-US': 'Data type' } as L,
      'Dict code': { 'zh-CN': '字典编码', 'en-US': 'Dict code' } as L,
      Required: { 'zh-CN': '必填', 'en-US': 'Required' } as L,
      'Read only': { 'zh-CN': '只读', 'en-US': 'Read only' } as L,
      'Permission code': { 'zh-CN': '权限编码', 'en-US': 'Permission code' } as L,
      Title: { 'zh-CN': '标题', 'en-US': 'Title' } as L,
      Subtitle: { 'zh-CN': '副标题', 'en-US': 'Subtitle' } as L,
      Description: { 'zh-CN': '描述', 'en-US': 'Description' } as L,
      Placeholder: { 'zh-CN': '占位提示', 'en-US': 'Placeholder' } as L,
      'Help text': { 'zh-CN': '帮助文本', 'en-US': 'Help text' } as L,
      'Empty text': { 'zh-CN': '空状态文本', 'en-US': 'Empty text' } as L,
      'Error text': { 'zh-CN': '错误文本', 'en-US': 'Error text' } as L,
      Width: { 'zh-CN': '宽度', 'en-US': 'Width' } as L,
      Height: { 'zh-CN': '高度', 'en-US': 'Height' } as L,
      Span: { 'zh-CN': '栅格宽度', 'en-US': 'Span' } as L,
      Gap: { 'zh-CN': '间距', 'en-US': 'Gap' } as L,
      Align: { 'zh-CN': '对齐', 'en-US': 'Align' } as L,
      Region: { 'zh-CN': '区域', 'en-US': 'Region' } as L,
      Columns: { 'zh-CN': '列数', 'en-US': 'Columns' } as L,
      'Row height': { 'zh-CN': '行高', 'en-US': 'Row height' } as L,
      Model: { 'zh-CN': '模型', 'en-US': 'Model' } as L,
      'Data source': { 'zh-CN': '数据源', 'en-US': 'Data source' } as L,
      'Widget type': { 'zh-CN': '组件类型', 'en-US': 'Widget type' } as L,
      'Action type': { 'zh-CN': '操作类型', 'en-US': 'Action type' } as L,
      Command: { 'zh-CN': '命令', 'en-US': 'Command' } as L,
      Operator: { 'zh-CN': '运算符', 'en-US': 'Operator' } as L,
      Format: { 'zh-CN': '格式', 'en-US': 'Format' } as L,
      Status: { 'zh-CN': '状态', 'en-US': 'Status' } as L,
      Metric: { 'zh-CN': '指标', 'en-US': 'Metric' } as L,
      Target: { 'zh-CN': '目标', 'en-US': 'Target' } as L,
      Route: { 'zh-CN': '路由', 'en-US': 'Route' } as L,
      'Open mode': { 'zh-CN': '打开方式', 'en-US': 'Open mode' } as L,
      Selection: { 'zh-CN': '选择', 'en-US': 'Selection' } as L,
      Searchable: { 'zh-CN': '可搜索', 'en-US': 'Searchable' } as L,
      'Search field': { 'zh-CN': '搜索字段', 'en-US': 'Search field' } as L,
      'Search placeholder': { 'zh-CN': '搜索占位提示', 'en-US': 'Search placeholder' } as L,
      'Search parameter': { 'zh-CN': '搜索参数', 'en-US': 'Search parameter' } as L,
      'Display field': { 'zh-CN': '显示字段', 'en-US': 'Display field' } as L,
      'Value field': { 'zh-CN': '取值字段', 'en-US': 'Value field' } as L,
      'Parent field': { 'zh-CN': '父字段', 'en-US': 'Parent field' } as L,
      'Child field': { 'zh-CN': '子字段', 'en-US': 'Child field' } as L,
      'Picker source': { 'zh-CN': '选择器来源', 'en-US': 'Picker source' } as L,
      'Picker data source': { 'zh-CN': '选择器数据源', 'en-US': 'Picker data source' } as L,
      Collapsible: { 'zh-CN': '可折叠', 'en-US': 'Collapsible' } as L,
      'Confirm first': { 'zh-CN': '需先确认', 'en-US': 'Confirm first' } as L,
      'New tab': { 'zh-CN': '新标签页打开', 'en-US': 'New tab' } as L,
      'Max files': { 'zh-CN': '最大文件数', 'en-US': 'Max files' } as L,
      'Multiple files': { 'zh-CN': '允许多文件', 'en-US': 'Multiple files' } as L,
      'Accepted file types': { 'zh-CN': '允许的文件类型', 'en-US': 'Accepted file types' } as L,
      'Page key': { 'zh-CN': '页面标识', 'en-US': 'Page key' } as L,
      'Page size': { 'zh-CN': '每页条数', 'en-US': 'Page size' } as L,
      'Refresh seconds': { 'zh-CN': '刷新间隔（秒）', 'en-US': 'Refresh seconds' } as L,
      'Workflow key': { 'zh-CN': '流程标识', 'en-US': 'Workflow key' } as L,
      'Business key': { 'zh-CN': '业务标识', 'en-US': 'Business key' } as L,
      'Named query code': { 'zh-CN': '命名查询编码', 'en-US': 'Named query code' } as L,
      'Success message': { 'zh-CN': '成功提示', 'en-US': 'Success message' } as L,
      Assignee: { 'zh-CN': '处理人', 'en-US': 'Assignee' } as L,
      'Due at': { 'zh-CN': '截止时间', 'en-US': 'Due at' } as L,
      'Drilldown route': { 'zh-CN': '下钻路由', 'en-US': 'Drilldown route' } as L,
      Currency: { 'zh-CN': '货币', 'en-US': 'Currency' } as L,
      Page: { 'zh-CN': '页面', 'en-US': 'Page' } as L,
      'Current page': { 'zh-CN': '当前页', 'en-US': 'Current page' } as L,
      // Component options
      Input: { 'zh-CN': '输入框', 'en-US': 'Input' } as L,
      Textarea: { 'zh-CN': '多行文本', 'en-US': 'Textarea' } as L,
      Select: { 'zh-CN': '下拉选择', 'en-US': 'Select' } as L,
      Date: { 'zh-CN': '日期', 'en-US': 'Date' } as L,
      Checkbox: { 'zh-CN': '复选框', 'en-US': 'Checkbox' } as L,
      Radio: { 'zh-CN': '单选框', 'en-US': 'Radio' } as L,
      Switch: { 'zh-CN': '开关', 'en-US': 'Switch' } as L,
      Number: { 'zh-CN': '数字', 'en-US': 'Number' } as L,
      Money: { 'zh-CN': '金额', 'en-US': 'Money' } as L,
      Picker: { 'zh-CN': '选择器', 'en-US': 'Picker' } as L,
      'Rich text': { 'zh-CN': '富文本', 'en-US': 'Rich text' } as L,
      Markdown: { 'zh-CN': 'Markdown', 'en-US': 'Markdown' } as L,
      Plain: { 'zh-CN': '纯文本', 'en-US': 'Plain' } as L,
      Upload: { 'zh-CN': '上传', 'en-US': 'Upload' } as L,
      // Chart / widget types
      'Bar chart': { 'zh-CN': '柱状图', 'en-US': 'Bar chart' } as L,
      'Line chart': { 'zh-CN': '折线图', 'en-US': 'Line chart' } as L,
      'Number card': { 'zh-CN': '数字卡片', 'en-US': 'Number card' } as L,
      Percent: { 'zh-CN': '百分比', 'en-US': 'Percent' } as L,
      // Operators
      Equals: { 'zh-CN': '等于', 'en-US': 'Equals' } as L,
      Contains: { 'zh-CN': '包含', 'en-US': 'Contains' } as L,
      Between: { 'zh-CN': '介于', 'en-US': 'Between' } as L,
      'Greater than': { 'zh-CN': '大于', 'en-US': 'Greater than' } as L,
      'Less than': { 'zh-CN': '小于', 'en-US': 'Less than' } as L,
      // Open modes
      Modal: { 'zh-CN': '弹窗', 'en-US': 'Modal' } as L,
      Drawer: { 'zh-CN': '抽屉', 'en-US': 'Drawer' } as L,
      Navigate: { 'zh-CN': '跳转', 'en-US': 'Navigate' } as L,
      // Align
      Left: { 'zh-CN': '左对齐', 'en-US': 'Left' } as L,
      Center: { 'zh-CN': '居中', 'en-US': 'Center' } as L,
      Right: { 'zh-CN': '右对齐', 'en-US': 'Right' } as L,
      // Status / enum option values
      Draft: { 'zh-CN': '草稿', 'en-US': 'Draft' } as L,
      Pending: { 'zh-CN': '待处理', 'en-US': 'Pending' } as L,
      Approved: { 'zh-CN': '已批准', 'en-US': 'Approved' } as L,
      Rejected: { 'zh-CN': '已拒绝', 'en-US': 'Rejected' } as L,
      Completed: { 'zh-CN': '已完成', 'en-US': 'Completed' } as L,
      // Actions / behaviors
      Create: { 'zh-CN': '创建', 'en-US': 'Create' } as L,
      Submit: { 'zh-CN': '提交', 'en-US': 'Submit' } as L,
      'Validate form': { 'zh-CN': '校验表单', 'en-US': 'Validate form' } as L,
      'Run data source': { 'zh-CN': '运行数据源', 'en-US': 'Run data source' } as L,
      'Query builder': { 'zh-CN': '查询构建器', 'en-US': 'Query builder' } as L,
      'Named query': { 'zh-CN': '命名查询', 'en-US': 'Named query' } as L,
      Workflow: { 'zh-CN': '工作流', 'en-US': 'Workflow' } as L,
      Execution: { 'zh-CN': '执行', 'en-US': 'Execution' } as L,
      'Data execution': { 'zh-CN': '数据执行', 'en-US': 'Data execution' } as L,
      'Live API': { 'zh-CN': '实时 API', 'en-US': 'Live API' } as L,
      'Static options': { 'zh-CN': '静态选项', 'en-US': 'Static options' } as L,
      'Static preview': { 'zh-CN': '静态预览', 'en-US': 'Static preview' } as L,
      'Preview only': { 'zh-CN': '仅预览', 'en-US': 'Preview only' } as L,
      'Preview value': { 'zh-CN': '预览值', 'en-US': 'Preview value' } as L,
      'Single row': { 'zh-CN': '单行', 'en-US': 'Single row' } as L,
      'Multiple rows': { 'zh-CN': '多行', 'en-US': 'Multiple rows' } as L,
      'Apply feedback': { 'zh-CN': '应用反馈', 'en-US': 'Apply feedback' } as L,
      Feedback: { 'zh-CN': '反馈', 'en-US': 'Feedback' } as L,
      'Suggested fields': { 'zh-CN': '建议字段', 'en-US': 'Suggested fields' } as L,
      // Advanced JSON power-user labels
      'Actions JSON': { 'zh-CN': '操作 JSON', 'en-US': 'Actions JSON' } as L,
      'Columns JSON': { 'zh-CN': '列 JSON', 'en-US': 'Columns JSON' } as L,
      'Disabled when JSON': { 'zh-CN': '禁用条件 JSON', 'en-US': 'Disabled when JSON' } as L,
      'Entries JSON': { 'zh-CN': '条目 JSON', 'en-US': 'Entries JSON' } as L,
      'Items JSON': { 'zh-CN': '项目 JSON', 'en-US': 'Items JSON' } as L,
      'Named query params JSON': { 'zh-CN': '命名查询参数 JSON', 'en-US': 'Named query params JSON' } as L,
      'Options JSON': { 'zh-CN': '选项 JSON', 'en-US': 'Options JSON' } as L,
      'Params JSON': { 'zh-CN': '参数 JSON', 'en-US': 'Params JSON' } as L,
      'Payload JSON': { 'zh-CN': '负载 JSON', 'en-US': 'Payload JSON' } as L,
      'Picker parameters JSON': { 'zh-CN': '选择器参数 JSON', 'en-US': 'Picker parameters JSON' } as L,
      'Preview rows JSON': { 'zh-CN': '预览行 JSON', 'en-US': 'Preview rows JSON' } as L,
      'Query builder JSON': { 'zh-CN': '查询构建器 JSON', 'en-US': 'Query builder JSON' } as L,
      'Rich text toolbar JSON': { 'zh-CN': '富文本工具栏 JSON', 'en-US': 'Rich text toolbar JSON' } as L,
      'Rows JSON': { 'zh-CN': '行 JSON', 'en-US': 'Rows JSON' } as L,
      'Run query JSON': { 'zh-CN': '运行查询 JSON', 'en-US': 'Run query JSON' } as L,
      'Series JSON': { 'zh-CN': '系列 JSON', 'en-US': 'Series JSON' } as L,
      'Suggested fields JSON': { 'zh-CN': '建议字段 JSON', 'en-US': 'Suggested fields JSON' } as L,
      'Thresholds JSON': { 'zh-CN': '阈值 JSON', 'en-US': 'Thresholds JSON' } as L,
      'Validation rules JSON': { 'zh-CN': '校验规则 JSON', 'en-US': 'Validation rules JSON' } as L,
      'Visible when JSON': { 'zh-CN': '显示条件 JSON', 'en-US': 'Visible when JSON' } as L,
    },
  },
} as const;

/**
 * Helper to resolve a LocalizedText value from DESIGNER_I18N.
 * Supports simple {param} interpolation.
 */
export function resolveDesignerText(
  text: Record<string, string>,
  locale: string,
  params?: Record<string, string | number>,
): string {
  let result = text[locale] || text['en-US'] || text['zh-CN'] || Object.values(text)[0] || '';
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      result = result.split(`{${key}}`).join(String(value));
    });
  }
  return result;
}
