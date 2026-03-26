// ============================================================================
// PM Shared Types & Constants
// ============================================================================

export interface TaskRecord {
  pid: string;
  pm_task_title: string;
  pm_task_type: string;
  pm_task_status: string;
  pm_task_priority: string;
  pm_task_assignee_id?: string;
  pm_task_start_date?: string;
  pm_task_due_date?: string;
  pm_task_estimated_hours?: number;
  pm_task_actual_hours?: number;
  pm_task_progress?: number;
  pm_task_sort_key?: number;
  pm_task_parent_id?: string;
  pm_task_description?: string;
  pm_task_resolution?: string;
  pm_task_done_at?: string;
  pm_task_files?: string;
  pm_task_project_id?: string;
  created_at?: string;
  updated_at?: string;
  [key: string]: unknown;
}

export interface ProjectRecord {
  pid: string;
  pm_project_code: string;
  pm_project_name: string;
  pm_project_status: string;
  pm_project_owner_user_id?: string;
  pm_start_date?: string;
  pm_end_date?: string;
  pm_description?: string;
}

export const STATUS_COLORS: Record<string, string> = {
  TODO: 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300',
  in_progress: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-300',
  DONE: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-300',
  cancelled: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300',
};

export const PRIORITY_COLORS: Record<string, string> = {
  CRITICAL: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-yellow-500',
  LOW: 'bg-blue-500',
  NONE: 'bg-gray-400',
};

export const TYPE_COLORS: Record<string, string> = {
  EPIC: 'bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-300',
  STORY: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-300',
  TASK: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-300',
  BUG: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300',
  MILESTONE: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
};

export const PROJECT_STATUS_COLORS: Record<string, string> = {
  PLANNING: 'bg-blue-100 text-blue-800',
  in_progress: 'bg-amber-100 text-amber-800',
  completed: 'bg-green-100 text-green-800',
  archived: 'bg-gray-100 text-gray-800',
  cancelled: 'bg-red-100 text-red-800',
};
