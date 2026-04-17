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
import type { RenderProfile } from '../types';
import { profileRegistry } from '../ProfileRegistry';

// Lazy block renderers — each block type is a separate chunk
const FormBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/FormBlockRenderer').then((m) => ({
    default: m.FormBlockRenderer,
  })),
);
const FormSectionBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/FormSectionBlockRenderer').then((m) => ({
    default: m.FormSectionBlockRenderer,
  })),
);
const FormButtonsBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/FormButtonsBlockRenderer').then((m) => ({
    default: m.FormButtonsBlockRenderer,
  })),
);
const FormWizardBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/FormWizardBlockRenderer').then((m) => ({
    default: m.FormWizardBlockRenderer,
  })),
);
const TableBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/TableBlockRenderer').then((m) => ({
    default: m.TableBlockRenderer,
  })),
);
const FiltersBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/FiltersBlockRenderer').then((m) => ({
    default: m.FiltersBlockRenderer,
  })),
);
const ToolbarBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/ToolbarBlockRenderer').then((m) => ({
    default: m.ToolbarBlockRenderer,
  })),
);
const DescriptionBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/DescriptionBlockRenderer').then((m) => ({
    default: m.DescriptionBlockRenderer,
  })),
);
const ChartBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/ChartBlockRenderer').then((m) => ({
    default: m.ChartBlockRenderer,
  })),
);
const TabsBlockRenderer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/TabsBlockRenderer').then((m) => ({
    default: m.TabsBlockRenderer,
  })),
);
// Lazy page content renderers — largest components, biggest savings
const ListPageContent = React.lazy(() =>
  import('~/framework/meta/rendering/pages/ListPageContent').then((m) => ({ default: m.ListPageContent })),
);
const FormPageContent = React.lazy(() =>
  import('~/framework/meta/rendering/pages/FormPageContent').then((m) => ({ default: m.FormPageContent })),
);
const DetailPageContent = React.lazy(() =>
  import('~/framework/meta/rendering/pages/DetailPageContent').then((m) => ({
    default: m.DetailPageContent,
  })),
);
// Skeletons stay static — they're tiny and shown during lazy loading
import { ListPageSkeleton } from '~/framework/meta/rendering/skeletons/ListPageSkeleton';
import { FormPageSkeleton } from '~/framework/meta/rendering/skeletons/FormPageSkeleton';
import { DetailPageSkeleton } from '~/framework/meta/rendering/skeletons/DetailPageSkeleton';

const adminProfile: RenderProfile = {
  name: 'admin',

  blockTypes: [
    'form',
    'form-section',
    'form-buttons',
    'form-wizard',
    'table',
    'filters',
    'toolbar',
    'action',
    'description',
    'chart',
    'tabs',
    'sub-table',
    'monthly-grid',
    'custom',
  ],

  blockRenderers: new Map<string, any>([
    ['form', FormBlockRenderer],
    ['form-section', FormSectionBlockRenderer],
    ['form-buttons', FormButtonsBlockRenderer],
    ['form-wizard', FormWizardBlockRenderer],
    ['table', TableBlockRenderer],
    ['filters', FiltersBlockRenderer],
    ['toolbar', ToolbarBlockRenderer],
    ['action', ToolbarBlockRenderer],
    ['description', DescriptionBlockRenderer],
    ['chart', ChartBlockRenderer],
    ['tabs', TabsBlockRenderer],
    // tabs, sub-table, monthly-grid are handled inline by page renderers
  ]),

  kinds: ['page', 'list', 'form', 'detail', 'page_layout'],

  pageRenderers: new Map<string, any>([
    ['list', ListPageContent],
    ['form', FormPageContent],
    ['detail', DetailPageContent],
  ]),

  skeletons: new Map<string, any>([
    ['list', ListPageSkeleton],
    ['form', FormPageSkeleton],
    ['detail', DetailPageSkeleton],
  ]),
};

// Register the admin profile
profileRegistry.register(adminProfile);

export { adminProfile };
