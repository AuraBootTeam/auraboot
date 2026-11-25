import { describe, expect, it } from 'vitest';
import { getChartComponent } from '~/framework/smart/charts/SharedChartFactory';

// Importing the viewer must be enough to resolve this plugin's workbench widgets.
// The home page is server-rendered, and the async plugin boot that evaluates the
// plugin entry (which registers them) only runs in the browser, after the first render.
import '../DashboardViewer';

describe('DashboardViewer workbench widget registration', () => {
  // Every type the plugin registers except `calendar`, which is also a built-in
  // chart type and therefore resolves with or without the plugin.
  it.each(['inbox', 'recent', 'shortcuts', 'stats-row', 'announcement', 'quick-note', 'stats-card'])(
    'resolves the %s widget without the plugin boot',
    (type) => {
      expect(getChartComponent(type)).not.toBeNull();
    },
  );
});
