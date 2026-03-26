/**
 * Admin Profile — encapsulates the current admin-style rendering capabilities
 *
 * This profile registers all existing block renderers and page renderers
 * as the "admin" profile, making them the default rendering style.
 *
 * Performance: Block renderers and page renderers are lazy-loaded via React.lazy()
 * so only the renderers needed for the current page type are downloaded.
 */

import React from 'react';
import type { DslProfile } from '../types';
import { profileRegistry } from '../ProfileRegistry';

// Lazy block renderers — each block type is a separate chunk
const FormBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/FormBlockRenderer').then((m) => ({
    default: m.FormBlockRenderer,
  })),
);
const FormSectionBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/FormSectionBlockRenderer').then((m) => ({
    default: m.FormSectionBlockRenderer,
  })),
);
const FormButtonsBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/FormButtonsBlockRenderer').then((m) => ({
    default: m.FormButtonsBlockRenderer,
  })),
);
const FormWizardBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/FormWizardBlockRenderer').then((m) => ({
    default: m.FormWizardBlockRenderer,
  })),
);
const TableBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/TableBlockRenderer').then((m) => ({
    default: m.TableBlockRenderer,
  })),
);
const FiltersBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/FiltersBlockRenderer').then((m) => ({
    default: m.FiltersBlockRenderer,
  })),
);
const ToolbarBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/ToolbarBlockRenderer').then((m) => ({
    default: m.ToolbarBlockRenderer,
  })),
);
const DescriptionBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/DescriptionBlockRenderer').then((m) => ({
    default: m.DescriptionBlockRenderer,
  })),
);
const ChartBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/ChartBlockRenderer').then((m) => ({
    default: m.ChartBlockRenderer,
  })),
);
const TabsBlockRenderer = React.lazy(() =>
  import('~/meta/rendering/blocks/TabsBlockRenderer').then((m) => ({
    default: m.TabsBlockRenderer,
  })),
);
const ApprovalCommentsBlock = React.lazy(() =>
  import('~/meta/rendering/blocks/ApprovalCommentsBlock').then((m) => ({
    default: m.ApprovalCommentsBlock,
  })),
);

// Lazy page content renderers — largest components, biggest savings
const ListPageContent = React.lazy(() =>
  import('~/meta/rendering/pages/ListPageContent').then((m) => ({ default: m.ListPageContent })),
);
const FormPageContent = React.lazy(() =>
  import('~/meta/rendering/pages/FormPageContent').then((m) => ({ default: m.FormPageContent })),
);
const DetailPageContent = React.lazy(() =>
  import('~/meta/rendering/pages/DetailPageContent').then((m) => ({
    default: m.DetailPageContent,
  })),
);
const RecordPageRenderer = React.lazy(() =>
  import('~/meta/rendering/pages/RecordPageRenderer').then((m) => ({
    default: m.RecordPageRenderer,
  })),
);
const TransactionPageRenderer = React.lazy(() =>
  import('~/meta/rendering/pages/TransactionPageRenderer').then((m) => ({
    default: m.TransactionPageRenderer,
  })),
);

// Skeletons stay static — they're tiny and shown during lazy loading
import { ListPageSkeleton } from '~/meta/rendering/skeletons/ListPageSkeleton';
import { FormPageSkeleton } from '~/meta/rendering/skeletons/FormPageSkeleton';
import { DetailPageSkeleton } from '~/meta/rendering/skeletons/DetailPageSkeleton';

const adminProfile: DslProfile = {
  name: 'admin',

  blockTypes: [
    'form',
    'form-section',
    'form-buttons',
    'form-wizard',
    'table',
    'data-table',
    'filters',
    'filter-form',
    'toolbar',
    'action',
    'description',
    'chart',
    'tabs',
    'list-tabs',
    'sub-table',
    'monthly-grid',
    'custom',
    'approval-comments',
  ],

  blockRenderers: new Map<string, any>([
    ['form', FormBlockRenderer],
    ['form-section', FormSectionBlockRenderer],
    ['form-buttons', FormButtonsBlockRenderer],
    ['form-wizard', FormWizardBlockRenderer],
    ['table', TableBlockRenderer],
    ['data-table', TableBlockRenderer],
    ['filters', FiltersBlockRenderer],
    ['filter-form', FiltersBlockRenderer],
    ['toolbar', ToolbarBlockRenderer],
    ['action', ToolbarBlockRenderer],
    ['description', DescriptionBlockRenderer],
    ['chart', ChartBlockRenderer],
    ['tabs', TabsBlockRenderer],
    ['approval-comments', ApprovalCommentsBlock],
    // list-tabs, sub-table, monthly-grid are handled inline by page renderers
  ]),

  kinds: ['Page', 'List', 'Form', 'Detail', 'PageLayout', 'Dashboard', 'Record', 'Transaction'],

  pageRenderers: new Map<string, any>([
    ['List', ListPageContent],
    ['Form', FormPageContent],
    ['Detail', DetailPageContent],
    ['Dashboard', ListPageContent], // Dashboard uses list page content (handles isDashboard internally)
    ['Record', RecordPageRenderer], // GAP-086: ERP header+lines layout
    ['Transaction', TransactionPageRenderer], // GAP-086: Read-only ledger view
  ]),

  skeletons: new Map<string, any>([
    ['List', ListPageSkeleton],
    ['Form', FormPageSkeleton],
    ['Detail', DetailPageSkeleton],
  ]),
};

// Register the admin profile
profileRegistry.register(adminProfile);

export { adminProfile };
