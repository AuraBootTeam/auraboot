/**
 * Report Profile — registers report-specific renderers
 *
 * Following "Shell unified, DSL independent" principle:
 * Report has its own DslProfile, separate from admin.
 */

import React from 'react';
import type { DslProfile } from '../types';
import { profileRegistry } from '../ProfileRegistry';

// Lazy-loaded renderers
const ReportPageContent = React.lazy(() =>
  import('~/report-designer/renderers/ReportPageContent').then((m) => ({
    default: m.ReportPageContent as any,
  })),
);

// Skeleton
import { ReportPageSkeleton } from '~/report-designer/renderers/ReportPageSkeleton';

const reportProfile: DslProfile = {
  name: 'report',

  blockTypes: ['report-data-table', 'report-header', 'report-footer'],

  blockRenderers: new Map(),

  kinds: ['Report'],

  pageRenderers: new Map<string, any>([['Report', ReportPageContent]]),

  skeletons: new Map<string, any>([['Report', ReportPageSkeleton]]),
};

profileRegistry.register(reportProfile);

export { reportProfile };
