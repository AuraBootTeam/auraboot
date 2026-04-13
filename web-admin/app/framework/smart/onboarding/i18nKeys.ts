/**
 * i18n key constants for the onboarding wizard and learning-curve features.
 *
 * All user-visible text is referenced via these keys so that the I18nProvider
 * can resolve them to the current locale.  Fallback strings are co-located
 * here for convenience — they double as the English default.
 */

export const ONBOARDING_KEYS = {
  // ── Wizard chrome ──────────────────────────────────────────────
  wizardTitle: 'onboarding.wizard.title',
  wizardSkip: 'onboarding.wizard.skip',
  wizardNext: 'onboarding.wizard.next',
  wizardPrev: 'onboarding.wizard.prev',
  wizardFinish: 'onboarding.wizard.finish',
  stepOf: 'onboarding.wizard.stepOf', // "{current} of {total}"

  // ── Step 1 – Welcome ──────────────────────────────────────────
  welcomeTitle: 'onboarding.welcome.title',
  welcomeSubtitle: 'onboarding.welcome.subtitle',
  welcomeDesc: 'onboarding.welcome.description',
  conceptModel: 'onboarding.welcome.concept.model',
  conceptModelDesc: 'onboarding.welcome.concept.model.desc',
  conceptField: 'onboarding.welcome.concept.field',
  conceptFieldDesc: 'onboarding.welcome.concept.field.desc',
  conceptCommand: 'onboarding.welcome.concept.command',
  conceptCommandDesc: 'onboarding.welcome.concept.command.desc',
  conceptPage: 'onboarding.welcome.concept.page',
  conceptPageDesc: 'onboarding.welcome.concept.page.desc',

  // ── Step 2 – Create Model ─────────────────────────────────────
  createModelTitle: 'onboarding.createModel.title',
  createModelDesc: 'onboarding.createModel.description',
  modelNameLabel: 'onboarding.createModel.name.label',
  modelNamePlaceholder: 'onboarding.createModel.name.placeholder',
  modelCategoryLabel: 'onboarding.createModel.category.label',
  categoryDocument: 'onboarding.createModel.category.document',
  categoryDocumentDesc: 'onboarding.createModel.category.document.desc',
  categoryMaster: 'onboarding.createModel.category.master',
  categoryMasterDesc: 'onboarding.createModel.category.master.desc',
  categoryLookup: 'onboarding.createModel.category.lookup',
  categoryLookupDesc: 'onboarding.createModel.category.lookup.desc',

  // ── Step 3 – Add Fields ───────────────────────────────────────
  addFieldsTitle: 'onboarding.addFields.title',
  addFieldsDesc: 'onboarding.addFields.description',
  fieldGroupBasic: 'onboarding.addFields.group.basic',
  fieldGroupStatus: 'onboarding.addFields.group.status',
  fieldGroupTime: 'onboarding.addFields.group.time',
  fieldGroupAmount: 'onboarding.addFields.group.amount',
  fieldGroupContact: 'onboarding.addFields.group.contact',
  fieldGroupAddress: 'onboarding.addFields.group.address',
  addGroup: 'onboarding.addFields.addGroup',
  removeField: 'onboarding.addFields.removeField',
  selectedFields: 'onboarding.addFields.selectedFields',

  // ── Step 4 – Configure Commands ───────────────────────────────
  configCommandTitle: 'onboarding.configCommand.title',
  configCommandDesc: 'onboarding.configCommand.description',
  templateSimpleCrud: 'onboarding.configCommand.template.simpleCrud',
  templateSimpleCrudDesc: 'onboarding.configCommand.template.simpleCrud.desc',
  templateDocLifecycle: 'onboarding.configCommand.template.docLifecycle',
  templateDocLifecycleDesc: 'onboarding.configCommand.template.docLifecycle.desc',
  templateApproval: 'onboarding.configCommand.template.approval',
  templateApprovalDesc: 'onboarding.configCommand.template.approval.desc',
  templateInventory: 'onboarding.configCommand.template.inventory',
  templateInventoryDesc: 'onboarding.configCommand.template.inventory.desc',
  templateProjectTask: 'onboarding.configCommand.template.projectTask',
  templateProjectTaskDesc: 'onboarding.configCommand.template.projectTask.desc',

  // ── Step 5 – Preview ──────────────────────────────────────────
  previewTitle: 'onboarding.preview.title',
  previewDesc: 'onboarding.preview.description',
  previewModel: 'onboarding.preview.model',
  previewFields: 'onboarding.preview.fields',
  previewCommands: 'onboarding.preview.commands',

  // ── Step 6 – Complete ─────────────────────────────────────────
  completeTitle: 'onboarding.complete.title',
  completeDesc: 'onboarding.complete.description',
  completeGoDesigner: 'onboarding.complete.goDesigner',
  completeGoTemplates: 'onboarding.complete.goTemplates',
  completeGoDocs: 'onboarding.complete.goDocs',

  // ── Command Template Gallery ──────────────────────────────────
  galleryTitle: 'commandTemplate.gallery.title',
  gallerySubtitle: 'commandTemplate.gallery.subtitle',
  gallerySearch: 'commandTemplate.gallery.search',
  galleryAll: 'commandTemplate.gallery.category.all',
  galleryBasic: 'commandTemplate.gallery.category.basic',
  galleryLifecycle: 'commandTemplate.gallery.category.lifecycle',
  galleryIndustry: 'commandTemplate.gallery.category.industry',
  galleryUseTemplate: 'commandTemplate.gallery.useTemplate',
  galleryPreview: 'commandTemplate.gallery.preview',
  galleryNoResults: 'commandTemplate.gallery.noResults',
  galleryApplicable: 'commandTemplate.gallery.applicable',
  galleryCommands: 'commandTemplate.gallery.commands',

  // ── Field Template Presets ────────────────────────────────────
  fieldPresetTitle: 'fieldPreset.title',
  fieldPresetQuickAdd: 'fieldPreset.quickAdd',
  fieldPresetAddAll: 'fieldPreset.addAll',

  // ── Progressive Disclosure ────────────────────────────────────
  disclosureBasic: 'commandEditor.section.basic',
  disclosureAdvanced: 'commandEditor.section.advanced',
  disclosureExpert: 'commandEditor.section.expert',
  disclosureBasicDesc: 'commandEditor.section.basic.desc',
  disclosureAdvancedDesc: 'commandEditor.section.advanced.desc',
  disclosureExpertDesc: 'commandEditor.section.expert.desc',

  // ── Contextual Help ───────────────────────────────────────────
  helpPanelTitle: 'contextualHelp.panel.title',
  helpLearnMore: 'contextualHelp.learnMore',
} as const;

/**
 * English fallback translations.
 * These are registered with the I18nProvider at boot time.
 */
export const ONBOARDING_EN: Record<string, string> = {
  [ONBOARDING_KEYS.wizardTitle]: 'Welcome to AuraBoot',
  [ONBOARDING_KEYS.wizardSkip]: 'Skip',
  [ONBOARDING_KEYS.wizardNext]: 'Next',
  [ONBOARDING_KEYS.wizardPrev]: 'Back',
  [ONBOARDING_KEYS.wizardFinish]: 'Get Started',
  [ONBOARDING_KEYS.stepOf]: '{current} of {total}',

  [ONBOARDING_KEYS.welcomeTitle]: 'Welcome to AuraBoot',
  [ONBOARDING_KEYS.welcomeSubtitle]: 'Build business apps without code',
  [ONBOARDING_KEYS.welcomeDesc]:
    'AuraBoot lets you create models, fields, commands, and pages through a visual interface. Let us walk you through the core concepts.',
  [ONBOARDING_KEYS.conceptModel]: 'Model',
  [ONBOARDING_KEYS.conceptModelDesc]:
    'A data table that stores your business records (e.g. Orders, Customers).',
  [ONBOARDING_KEYS.conceptField]: 'Field',
  [ONBOARDING_KEYS.conceptFieldDesc]: 'A column in your model (e.g. Name, Status, Amount).',
  [ONBOARDING_KEYS.conceptCommand]: 'Command',
  [ONBOARDING_KEYS.conceptCommandDesc]:
    'An action users can take on records (e.g. Create, Approve, Archive).',
  [ONBOARDING_KEYS.conceptPage]: 'Page',
  [ONBOARDING_KEYS.conceptPageDesc]: 'A visual interface generated from your model and commands.',

  [ONBOARDING_KEYS.createModelTitle]: 'Create Your First Model',
  [ONBOARDING_KEYS.createModelDesc]: 'Choose a name and category for your business model.',
  [ONBOARDING_KEYS.modelNameLabel]: 'Model Name',
  [ONBOARDING_KEYS.modelNamePlaceholder]: 'e.g. Sales Order',
  [ONBOARDING_KEYS.modelCategoryLabel]: 'Category',
  [ONBOARDING_KEYS.categoryDocument]: 'Document',
  [ONBOARDING_KEYS.categoryDocumentDesc]: 'Records with a lifecycle (Orders, Invoices, Requests).',
  [ONBOARDING_KEYS.categoryMaster]: 'Master Data',
  [ONBOARDING_KEYS.categoryMasterDesc]: 'Reference data that rarely changes (Products, Customers).',
  [ONBOARDING_KEYS.categoryLookup]: 'Lookup',
  [ONBOARDING_KEYS.categoryLookupDesc]: 'Simple code-value pairs (Status codes, Categories).',

  [ONBOARDING_KEYS.addFieldsTitle]: 'Add Fields to Your Model',
  [ONBOARDING_KEYS.addFieldsDesc]: 'Select field groups to quickly add common fields.',
  [ONBOARDING_KEYS.fieldGroupBasic]: 'Basic Info',
  [ONBOARDING_KEYS.fieldGroupStatus]: 'Status & Priority',
  [ONBOARDING_KEYS.fieldGroupTime]: 'Time Tracking',
  [ONBOARDING_KEYS.fieldGroupAmount]: 'Amount & Currency',
  [ONBOARDING_KEYS.fieldGroupContact]: 'Contact Info',
  [ONBOARDING_KEYS.fieldGroupAddress]: 'Address',
  [ONBOARDING_KEYS.addGroup]: 'Add',
  [ONBOARDING_KEYS.removeField]: 'Remove',
  [ONBOARDING_KEYS.selectedFields]: 'Selected Fields',

  [ONBOARDING_KEYS.configCommandTitle]: 'Choose a Command Template',
  [ONBOARDING_KEYS.configCommandDesc]:
    'Templates provide pre-built command sets for common business patterns.',
  [ONBOARDING_KEYS.templateSimpleCrud]: 'Simple CRUD',
  [ONBOARDING_KEYS.templateSimpleCrudDesc]: 'Basic Create, Update, Delete operations.',
  [ONBOARDING_KEYS.templateDocLifecycle]: 'Document Lifecycle',
  [ONBOARDING_KEYS.templateDocLifecycleDesc]: 'Create, Submit, Approve, Reject, Archive.',
  [ONBOARDING_KEYS.templateApproval]: 'Approval Flow',
  [ONBOARDING_KEYS.templateApprovalDesc]: 'Create, Submit for Approval, Approve, Reject, Revise.',
  [ONBOARDING_KEYS.templateInventory]: 'Inventory Movement',
  [ONBOARDING_KEYS.templateInventoryDesc]: 'Create, Confirm, Ship, Receive, Close.',
  [ONBOARDING_KEYS.templateProjectTask]: 'Project Task',
  [ONBOARDING_KEYS.templateProjectTaskDesc]: 'Create, Assign, Start, Complete, Close.',

  [ONBOARDING_KEYS.previewTitle]: 'Preview Your Configuration',
  [ONBOARDING_KEYS.previewDesc]: 'Here is a summary of what will be created.',
  [ONBOARDING_KEYS.previewModel]: 'Model',
  [ONBOARDING_KEYS.previewFields]: 'Fields',
  [ONBOARDING_KEYS.previewCommands]: 'Commands',

  [ONBOARDING_KEYS.completeTitle]: "You're All Set!",
  [ONBOARDING_KEYS.completeDesc]: 'Your model is ready. Here are some things you can do next.',
  [ONBOARDING_KEYS.completeGoDesigner]: 'Open Page Designer',
  [ONBOARDING_KEYS.completeGoTemplates]: 'Browse Command Templates',
  [ONBOARDING_KEYS.completeGoDocs]: 'Read Documentation',

  [ONBOARDING_KEYS.galleryTitle]: 'Command Templates',
  [ONBOARDING_KEYS.gallerySubtitle]:
    'Pre-built command configurations for common business patterns',
  [ONBOARDING_KEYS.gallerySearch]: 'Search templates...',
  [ONBOARDING_KEYS.galleryAll]: 'All',
  [ONBOARDING_KEYS.galleryBasic]: 'Basic',
  [ONBOARDING_KEYS.galleryLifecycle]: 'Lifecycle',
  [ONBOARDING_KEYS.galleryIndustry]: 'Industry',
  [ONBOARDING_KEYS.galleryUseTemplate]: 'Use Template',
  [ONBOARDING_KEYS.galleryPreview]: 'Preview',
  [ONBOARDING_KEYS.galleryNoResults]: 'No templates match your search.',
  [ONBOARDING_KEYS.galleryApplicable]: 'Best for',
  [ONBOARDING_KEYS.galleryCommands]: 'Commands',

  [ONBOARDING_KEYS.fieldPresetTitle]: 'Field Templates',
  [ONBOARDING_KEYS.fieldPresetQuickAdd]: 'Quick Add',
  [ONBOARDING_KEYS.fieldPresetAddAll]: 'Add All',

  [ONBOARDING_KEYS.disclosureBasic]: 'Basic',
  [ONBOARDING_KEYS.disclosureAdvanced]: 'Advanced',
  [ONBOARDING_KEYS.disclosureExpert]: 'Expert',
  [ONBOARDING_KEYS.disclosureBasicDesc]:
    'Command name, type, and fields — required for all commands.',
  [ONBOARDING_KEYS.disclosureAdvancedDesc]: 'Preconditions, side effects, and BPM triggers.',
  [ONBOARDING_KEYS.disclosureExpertDesc]: 'Execution config, custom handlers, and script hooks.',

  [ONBOARDING_KEYS.helpPanelTitle]: 'Help',
  [ONBOARDING_KEYS.helpLearnMore]: 'Learn more',
};

/**
 * Chinese (Simplified) translations.
 */
export const ONBOARDING_ZH: Record<string, string> = {
  [ONBOARDING_KEYS.wizardTitle]: '\u6b22\u8fce\u4f7f\u7528 AuraBoot',
  [ONBOARDING_KEYS.wizardSkip]: '\u8df3\u8fc7',
  [ONBOARDING_KEYS.wizardNext]: '\u4e0b\u4e00\u6b65',
  [ONBOARDING_KEYS.wizardPrev]: '\u4e0a\u4e00\u6b65',
  [ONBOARDING_KEYS.wizardFinish]: '\u5f00\u59cb\u4f7f\u7528',
  [ONBOARDING_KEYS.stepOf]: '{current} / {total}',

  [ONBOARDING_KEYS.welcomeTitle]: '\u6b22\u8fce\u4f7f\u7528 AuraBoot',
  [ONBOARDING_KEYS.welcomeSubtitle]: '\u65e0\u4ee3\u7801\u6784\u5efa\u4e1a\u52a1\u5e94\u7528',
  [ONBOARDING_KEYS.welcomeDesc]:
    'AuraBoot \u8ba9\u60a8\u901a\u8fc7\u53ef\u89c6\u5316\u754c\u9762\u521b\u5efa\u6a21\u578b\u3001\u5b57\u6bb5\u3001\u547d\u4ee4\u548c\u9875\u9762\u3002\u8ba9\u6211\u4eec\u5e26\u60a8\u4e86\u89e3\u6838\u5fc3\u6982\u5ff5\u3002',
  [ONBOARDING_KEYS.conceptModel]: '\u6a21\u578b',
  [ONBOARDING_KEYS.conceptModelDesc]:
    '\u5b58\u50a8\u4e1a\u52a1\u8bb0\u5f55\u7684\u6570\u636e\u8868\uff08\u5982\u8ba2\u5355\u3001\u5ba2\u6237\uff09\u3002',
  [ONBOARDING_KEYS.conceptField]: '\u5b57\u6bb5',
  [ONBOARDING_KEYS.conceptFieldDesc]:
    '\u6a21\u578b\u4e2d\u7684\u5217\uff08\u5982\u540d\u79f0\u3001\u72b6\u6001\u3001\u91d1\u989d\uff09\u3002',
  [ONBOARDING_KEYS.conceptCommand]: '\u547d\u4ee4',
  [ONBOARDING_KEYS.conceptCommandDesc]:
    '\u7528\u6237\u53ef\u4ee5\u5bf9\u8bb0\u5f55\u6267\u884c\u7684\u64cd\u4f5c\uff08\u5982\u521b\u5efa\u3001\u5ba1\u6279\u3001\u5f52\u6863\uff09\u3002',
  [ONBOARDING_KEYS.conceptPage]: '\u9875\u9762',
  [ONBOARDING_KEYS.conceptPageDesc]:
    '\u6839\u636e\u6a21\u578b\u548c\u547d\u4ee4\u81ea\u52a8\u751f\u6210\u7684\u53ef\u89c6\u5316\u754c\u9762\u3002',

  [ONBOARDING_KEYS.createModelTitle]: '\u521b\u5efa\u60a8\u7684\u7b2c\u4e00\u4e2a\u6a21\u578b',
  [ONBOARDING_KEYS.createModelDesc]:
    '\u4e3a\u60a8\u7684\u4e1a\u52a1\u6a21\u578b\u9009\u62e9\u540d\u79f0\u548c\u7c7b\u522b\u3002',
  [ONBOARDING_KEYS.modelNameLabel]: '\u6a21\u578b\u540d\u79f0',
  [ONBOARDING_KEYS.modelNamePlaceholder]: '\u4f8b\u5982\uff1a\u9500\u552e\u8ba2\u5355',
  [ONBOARDING_KEYS.modelCategoryLabel]: '\u7c7b\u522b',
  [ONBOARDING_KEYS.categoryDocument]: '\u5355\u636e',
  [ONBOARDING_KEYS.categoryDocumentDesc]:
    '\u5177\u6709\u751f\u547d\u5468\u671f\u7684\u8bb0\u5f55\uff08\u8ba2\u5355\u3001\u53d1\u7968\u3001\u7533\u8bf7\uff09\u3002',
  [ONBOARDING_KEYS.categoryMaster]: '\u4e3b\u6570\u636e',
  [ONBOARDING_KEYS.categoryMasterDesc]:
    '\u5f88\u5c11\u53d8\u5316\u7684\u53c2\u8003\u6570\u636e\uff08\u4ea7\u54c1\u3001\u5ba2\u6237\uff09\u3002',
  [ONBOARDING_KEYS.categoryLookup]: '\u67e5\u627e\u8868',
  [ONBOARDING_KEYS.categoryLookupDesc]:
    '\u7b80\u5355\u7684\u7f16\u7801-\u503c\u5bf9\uff08\u72b6\u6001\u7801\u3001\u5206\u7c7b\uff09\u3002',

  [ONBOARDING_KEYS.addFieldsTitle]: '\u6dfb\u52a0\u5b57\u6bb5',
  [ONBOARDING_KEYS.addFieldsDesc]:
    '\u9009\u62e9\u5b57\u6bb5\u7ec4\u4ee5\u5feb\u901f\u6dfb\u52a0\u5e38\u7528\u5b57\u6bb5\u3002',
  [ONBOARDING_KEYS.fieldGroupBasic]: '\u57fa\u672c\u4fe1\u606f',
  [ONBOARDING_KEYS.fieldGroupStatus]: '\u72b6\u6001\u4e0e\u4f18\u5148\u7ea7',
  [ONBOARDING_KEYS.fieldGroupTime]: '\u65f6\u95f4\u8ddf\u8e2a',
  [ONBOARDING_KEYS.fieldGroupAmount]: '\u91d1\u989d\u4e0e\u8d27\u5e01',
  [ONBOARDING_KEYS.fieldGroupContact]: '\u8054\u7cfb\u4eba',
  [ONBOARDING_KEYS.fieldGroupAddress]: '\u5730\u5740',
  [ONBOARDING_KEYS.addGroup]: '\u6dfb\u52a0',
  [ONBOARDING_KEYS.removeField]: '\u79fb\u9664',
  [ONBOARDING_KEYS.selectedFields]: '\u5df2\u9009\u5b57\u6bb5',

  [ONBOARDING_KEYS.configCommandTitle]: '\u9009\u62e9\u547d\u4ee4\u6a21\u677f',
  [ONBOARDING_KEYS.configCommandDesc]:
    '\u6a21\u677f\u63d0\u4f9b\u4e86\u5e38\u89c1\u4e1a\u52a1\u6a21\u5f0f\u7684\u9884\u5efa\u547d\u4ee4\u96c6\u3002',
  [ONBOARDING_KEYS.templateSimpleCrud]: '\u57fa\u672c CRUD',
  [ONBOARDING_KEYS.templateSimpleCrudDesc]:
    '\u57fa\u7840\u7684\u521b\u5efa\u3001\u66f4\u65b0\u3001\u5220\u9664\u64cd\u4f5c\u3002',
  [ONBOARDING_KEYS.templateDocLifecycle]: '\u5355\u636e\u751f\u547d\u5468\u671f',
  [ONBOARDING_KEYS.templateDocLifecycleDesc]:
    '\u521b\u5efa\u3001\u63d0\u4ea4\u3001\u5ba1\u6279\u3001\u62d2\u7edd\u3001\u5f52\u6863\u3002',
  [ONBOARDING_KEYS.templateApproval]: '\u5ba1\u6279\u6d41\u7a0b',
  [ONBOARDING_KEYS.templateApprovalDesc]:
    '\u521b\u5efa\u3001\u63d0\u4ea4\u5ba1\u6279\u3001\u5ba1\u6279\u3001\u62d2\u7edd\u3001\u4fee\u6539\u3002',
  [ONBOARDING_KEYS.templateInventory]: '\u5e93\u5b58\u79fb\u52a8',
  [ONBOARDING_KEYS.templateInventoryDesc]:
    '\u521b\u5efa\u3001\u786e\u8ba4\u3001\u53d1\u8d27\u3001\u6536\u8d27\u3001\u5173\u95ed\u3002',
  [ONBOARDING_KEYS.templateProjectTask]: '\u9879\u76ee\u4efb\u52a1',
  [ONBOARDING_KEYS.templateProjectTaskDesc]:
    '\u521b\u5efa\u3001\u5206\u914d\u3001\u5f00\u59cb\u3001\u5b8c\u6210\u3001\u5173\u95ed\u3002',

  [ONBOARDING_KEYS.previewTitle]: '\u9884\u89c8\u914d\u7f6e',
  [ONBOARDING_KEYS.previewDesc]:
    '\u4ee5\u4e0b\u662f\u5c06\u8981\u521b\u5efa\u7684\u5185\u5bb9\u6458\u8981\u3002',
  [ONBOARDING_KEYS.previewModel]: '\u6a21\u578b',
  [ONBOARDING_KEYS.previewFields]: '\u5b57\u6bb5',
  [ONBOARDING_KEYS.previewCommands]: '\u547d\u4ee4',

  [ONBOARDING_KEYS.completeTitle]: '\u5168\u90e8\u5b8c\u6210\uff01',
  [ONBOARDING_KEYS.completeDesc]:
    '\u60a8\u7684\u6a21\u578b\u5df2\u51c6\u5907\u5c31\u7eea\u3002\u4ee5\u4e0b\u662f\u4e00\u4e9b\u63a8\u8350\u7684\u4e0b\u4e00\u6b65\u64cd\u4f5c\u3002',
  [ONBOARDING_KEYS.completeGoDesigner]: '\u6253\u5f00\u9875\u9762\u8bbe\u8ba1\u5668',
  [ONBOARDING_KEYS.completeGoTemplates]: '\u6d4f\u89c8\u547d\u4ee4\u6a21\u677f',
  [ONBOARDING_KEYS.completeGoDocs]: '\u9605\u8bfb\u6587\u6863',

  [ONBOARDING_KEYS.galleryTitle]: '\u547d\u4ee4\u6a21\u677f',
  [ONBOARDING_KEYS.gallerySubtitle]:
    '\u5e38\u89c1\u4e1a\u52a1\u6a21\u5f0f\u7684\u9884\u5efa\u547d\u4ee4\u914d\u7f6e',
  [ONBOARDING_KEYS.gallerySearch]: '\u641c\u7d22\u6a21\u677f...',
  [ONBOARDING_KEYS.galleryAll]: '\u5168\u90e8',
  [ONBOARDING_KEYS.galleryBasic]: '\u57fa\u672c',
  [ONBOARDING_KEYS.galleryLifecycle]: '\u751f\u547d\u5468\u671f',
  [ONBOARDING_KEYS.galleryIndustry]: '\u884c\u4e1a',
  [ONBOARDING_KEYS.galleryUseTemplate]: '\u4f7f\u7528\u6a21\u677f',
  [ONBOARDING_KEYS.galleryPreview]: '\u9884\u89c8',
  [ONBOARDING_KEYS.galleryNoResults]: '\u6ca1\u6709\u5339\u914d\u7684\u6a21\u677f\u3002',
  [ONBOARDING_KEYS.galleryApplicable]: '\u9002\u7528\u4e8e',
  [ONBOARDING_KEYS.galleryCommands]: '\u547d\u4ee4',

  [ONBOARDING_KEYS.fieldPresetTitle]: '\u5b57\u6bb5\u6a21\u677f',
  [ONBOARDING_KEYS.fieldPresetQuickAdd]: '\u5feb\u901f\u6dfb\u52a0',
  [ONBOARDING_KEYS.fieldPresetAddAll]: '\u6dfb\u52a0\u5168\u90e8',

  [ONBOARDING_KEYS.disclosureBasic]: '\u57fa\u672c',
  [ONBOARDING_KEYS.disclosureAdvanced]: '\u9ad8\u7ea7',
  [ONBOARDING_KEYS.disclosureExpert]: '\u4e13\u5bb6',
  [ONBOARDING_KEYS.disclosureBasicDesc]:
    '\u547d\u4ee4\u540d\u79f0\u3001\u7c7b\u578b\u548c\u5b57\u6bb5 \u2014 \u6240\u6709\u547d\u4ee4\u5fc5\u586b\u3002',
  [ONBOARDING_KEYS.disclosureAdvancedDesc]:
    '\u524d\u7f6e\u6761\u4ef6\u3001\u526f\u4f5c\u7528\u548c BPM \u89e6\u53d1\u5668\u3002',
  [ONBOARDING_KEYS.disclosureExpertDesc]:
    '\u6267\u884c\u914d\u7f6e\u3001\u81ea\u5b9a\u4e49\u5904\u7406\u5668\u548c\u811a\u672c\u94a9\u5b50\u3002',

  [ONBOARDING_KEYS.helpPanelTitle]: '\u5e2e\u52a9',
  [ONBOARDING_KEYS.helpLearnMore]: '\u4e86\u89e3\u66f4\u591a',
};
