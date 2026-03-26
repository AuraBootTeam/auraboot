/**
 * PM Project — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId, todayStr, dateOffsetStr } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PM_PROJECT_CONFIG: ModelTestConfig = {
  modelCode: 'pm_project',
  pageKey: 'pm-project',
  namespace: 'pm',
  commands: {
    create: 'create_project',
    update: 'update_project',
    activate: 'activate_project',
    complete: 'complete_project',
    archive: 'archive_project',
  },
  defaultData: () => ({
    pm_project_name: `E2E Project ${uniqueId('PM')}`,
    pm_project_description: 'E2E test project',
    pm_planned_start: todayStr(),
    pm_planned_end: dateOffsetStr(30),
    pm_project_priority: 'medium',
  }),
};
