import { definePlugin } from '@auraboot/plugin-sdk'

// Register this plugin's workbench widgets with the shared chart factory
// (the factory no longer imports plugin internals).
import { registerChartComponent } from '~/framework/smart/charts/SharedChartFactory';
import { InboxWidget } from './widgets/workbench/InboxWidget';
import { RecentWidget } from './widgets/workbench/RecentWidget';
import { ShortcutsWidget } from './widgets/workbench/ShortcutsWidget';
import { StatsRowWidget } from './widgets/workbench/StatsRowWidget';
import { CalendarWidget } from './widgets/workbench/CalendarWidget';
import { AnnouncementWidget } from './widgets/workbench/AnnouncementWidget';
import { QuickNoteWidget } from './widgets/workbench/QuickNoteWidget';
import { StatsCardWidget } from './widgets/workbench/StatsCardWidget';

registerChartComponent('inbox', InboxWidget);
registerChartComponent('recent', RecentWidget);
registerChartComponent('shortcuts', ShortcutsWidget);
registerChartComponent('stats-row', StatsRowWidget);
registerChartComponent('calendar', CalendarWidget);
registerChartComponent('announcement', AnnouncementWidget);
registerChartComponent('quick-note', QuickNoteWidget);
registerChartComponent('stats-card', StatsCardWidget);

import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

/**
 * core-dashboard plugin — dashboard viewer + designer.
 *
 * The legacy module barrel lives in ./module.ts and remains the entry used by
 * route-level lazy imports for both viewer and authoring surfaces.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
