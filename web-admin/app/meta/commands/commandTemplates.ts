/**
 * Command Template definitions
 *
 * Pre-built command configurations for common business patterns.
 * Each template contains a set of commands that can be applied to a model
 * in one click.
 */

export interface CommandDef {
  code: string;
  name: string;
  type: 'create' | 'update' | 'delete' | 'state_change';
  description: string;
  /** Required status precondition (for state-change commands) */
  fromStatus?: string[];
  /** Target status after execution */
  toStatus?: string;
}

export interface CommandTemplate {
  id: string;
  nameKey: string; // i18n key
  descriptionKey: string; // i18n key
  category: 'basic' | 'lifecycle' | 'industry';
  icon: string;
  tags: string[];
  /** Applicable model categories */
  applicableTo: ('document' | 'master' | 'lookup')[];
  commands: CommandDef[];
  /** Status values this template introduces */
  statuses?: string[];
}

// ---------------------------------------------------------------------------
// Template definitions
// ---------------------------------------------------------------------------

export const commandTemplates: CommandTemplate[] = [
  {
    id: 'simple-crud',
    nameKey: 'onboarding.configCommand.template.simpleCrud',
    descriptionKey: 'onboarding.configCommand.template.simpleCrud.desc',
    category: 'basic',
    icon: 'Database',
    tags: ['crud', 'basic', 'create', 'update', 'delete'],
    applicableTo: ['document', 'master', 'lookup'],
    commands: [
      {
        code: 'create',
        name: 'Create',
        type: 'create',
        description: 'Create a new record.',
      },
      {
        code: 'update',
        name: 'Update',
        type: 'update',
        description: 'Update an existing record.',
      },
      {
        code: 'delete',
        name: 'Delete',
        type: 'delete',
        description: 'Delete a record.',
      },
    ],
  },
  {
    id: 'document-lifecycle',
    nameKey: 'onboarding.configCommand.template.docLifecycle',
    descriptionKey: 'onboarding.configCommand.template.docLifecycle.desc',
    category: 'lifecycle',
    icon: 'FileCheck',
    tags: ['document', 'lifecycle', 'approve', 'reject', 'archive'],
    applicableTo: ['document'],
    statuses: ['draft', 'submitted', 'approved', 'rejected', 'archived'],
    commands: [
      {
        code: 'create',
        name: 'Create',
        type: 'create',
        description: 'Create a new draft document.',
        toStatus: 'draft',
      },
      {
        code: 'submit',
        name: 'Submit',
        type: 'state_change',
        description: 'Submit document for review.',
        fromStatus: ['draft', 'rejected'],
        toStatus: 'submitted',
      },
      {
        code: 'approve',
        name: 'Approve',
        type: 'state_change',
        description: 'Approve the submitted document.',
        fromStatus: ['submitted'],
        toStatus: 'approved',
      },
      {
        code: 'reject',
        name: 'Reject',
        type: 'state_change',
        description: 'Reject the submitted document.',
        fromStatus: ['submitted'],
        toStatus: 'rejected',
      },
      {
        code: 'archive',
        name: 'Archive',
        type: 'state_change',
        description: 'Archive the document.',
        fromStatus: ['approved'],
        toStatus: 'archived',
      },
    ],
  },
  {
    id: 'approval-flow',
    nameKey: 'onboarding.configCommand.template.approval',
    descriptionKey: 'onboarding.configCommand.template.approval.desc',
    category: 'lifecycle',
    icon: 'CheckCircle',
    tags: ['approval', 'workflow', 'revise', 'submit'],
    applicableTo: ['document'],
    statuses: ['draft', 'pending_approval', 'approved', 'rejected', 'revised'],
    commands: [
      {
        code: 'create',
        name: 'Create',
        type: 'create',
        description: 'Create a new request.',
        toStatus: 'draft',
      },
      {
        code: 'submit_for_approval',
        name: 'Submit for Approval',
        type: 'state_change',
        description: 'Submit for approval review.',
        fromStatus: ['draft', 'revised'],
        toStatus: 'pending_approval',
      },
      {
        code: 'approve',
        name: 'Approve',
        type: 'state_change',
        description: 'Approve the request.',
        fromStatus: ['pending_approval'],
        toStatus: 'approved',
      },
      {
        code: 'reject',
        name: 'Reject',
        type: 'state_change',
        description: 'Reject with feedback.',
        fromStatus: ['pending_approval'],
        toStatus: 'rejected',
      },
      {
        code: 'revise',
        name: 'Revise',
        type: 'state_change',
        description: 'Revise and resubmit.',
        fromStatus: ['rejected'],
        toStatus: 'revised',
      },
    ],
  },
  {
    id: 'inventory-movement',
    nameKey: 'onboarding.configCommand.template.inventory',
    descriptionKey: 'onboarding.configCommand.template.inventory.desc',
    category: 'industry',
    icon: 'Package',
    tags: ['inventory', 'warehouse', 'shipping', 'logistics'],
    applicableTo: ['document'],
    statuses: ['draft', 'confirmed', 'shipped', 'received', 'closed'],
    commands: [
      {
        code: 'create',
        name: 'Create',
        type: 'create',
        description: 'Create a new movement order.',
        toStatus: 'draft',
      },
      {
        code: 'confirm',
        name: 'Confirm',
        type: 'state_change',
        description: 'Confirm the movement order.',
        fromStatus: ['draft'],
        toStatus: 'confirmed',
      },
      {
        code: 'ship',
        name: 'Ship',
        type: 'state_change',
        description: 'Mark as shipped.',
        fromStatus: ['confirmed'],
        toStatus: 'shipped',
      },
      {
        code: 'receive',
        name: 'Receive',
        type: 'state_change',
        description: 'Confirm receipt.',
        fromStatus: ['shipped'],
        toStatus: 'received',
      },
      {
        code: 'close',
        name: 'Close',
        type: 'state_change',
        description: 'Close the movement order.',
        fromStatus: ['received'],
        toStatus: 'closed',
      },
    ],
  },
  {
    id: 'project-task',
    nameKey: 'onboarding.configCommand.template.projectTask',
    descriptionKey: 'onboarding.configCommand.template.projectTask.desc',
    category: 'industry',
    icon: 'ClipboardList',
    tags: ['project', 'task', 'assign', 'kanban'],
    applicableTo: ['document'],
    statuses: ['todo', 'assigned', 'in_progress', 'completed', 'closed'],
    commands: [
      {
        code: 'create',
        name: 'Create',
        type: 'create',
        description: 'Create a new task.',
        toStatus: 'todo',
      },
      {
        code: 'assign',
        name: 'Assign',
        type: 'state_change',
        description: 'Assign to a team member.',
        fromStatus: ['todo'],
        toStatus: 'assigned',
      },
      {
        code: 'start',
        name: 'Start',
        type: 'state_change',
        description: 'Begin working on this task.',
        fromStatus: ['assigned'],
        toStatus: 'in_progress',
      },
      {
        code: 'complete',
        name: 'Complete',
        type: 'state_change',
        description: 'Mark task as completed.',
        fromStatus: ['in_progress'],
        toStatus: 'completed',
      },
      {
        code: 'close',
        name: 'Close',
        type: 'state_change',
        description: 'Close the task.',
        fromStatus: ['completed'],
        toStatus: 'closed',
      },
    ],
  },
];

// ---------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------

export const commandTemplateCategories = [
  { key: 'all' as const, labelKey: 'commandTemplate.gallery.category.all' },
  { key: 'basic' as const, labelKey: 'commandTemplate.gallery.category.basic' },
  { key: 'lifecycle' as const, labelKey: 'commandTemplate.gallery.category.lifecycle' },
  { key: 'industry' as const, labelKey: 'commandTemplate.gallery.category.industry' },
];

export type CommandTemplateCategory = (typeof commandTemplateCategories)[number]['key'];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function getCommandTemplateById(id: string): CommandTemplate | undefined {
  return commandTemplates.find((t) => t.id === id);
}

export function filterCommandTemplates(
  category: CommandTemplateCategory,
  query: string = '',
): CommandTemplate[] {
  let results = commandTemplates;
  if (category !== 'all') {
    results = results.filter((t) => t.category === category);
  }
  const q = query.toLowerCase().trim();
  if (q) {
    results = results.filter(
      (t) =>
        t.nameKey.toLowerCase().includes(q) ||
        t.descriptionKey.toLowerCase().includes(q) ||
        t.tags.some((tag) => tag.includes(q)),
    );
  }
  return results;
}
