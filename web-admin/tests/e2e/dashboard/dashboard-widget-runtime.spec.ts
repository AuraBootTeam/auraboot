/**
 * Dashboard Widget Runtime E2E Tests
 *
 * Covers representative Dashboard Viewer runtime semantics that are not proven
 * by designer saved-payload readback tests:
 * - static data widgets render computed values
 * - foundational chart widgets render through the published viewer registry
 * - content/embed widgets pass authored props into real DOM nodes
 * - workbench widgets resolve through the shared runtime registry
 *
 * API calls in this spec are deterministic fixture setup/cleanup only. Product
 * evidence comes from opening the published `/dashboards/view/:code` route in
 * the browser and asserting the rendered DashboardViewer DOM.
 */

import { test, expect } from '../../fixtures';
import type { APIResponse, Page, Locator, Response as PlaywrightResponse } from '@playwright/test';
import { existsSync, readFileSync } from 'fs';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';
import { executeCommandViaApi, uniqueId } from '../helpers';

type DashboardWidgetFixture = {
  id: string;
  type: string;
  title: string;
  x: number;
  y: number;
  w: number;
  h: number;
  config: Record<string, unknown>;
};

type CreatedDashboard = {
  pid: string;
  code: string;
  title: string;
};

type CreatedEngagement = {
  id: string;
  targetId: string;
  targetLabel: string;
  targetContext?: {
    path?: string;
  };
};

type CreatedAnnouncement = {
  id: string;
  title: string;
  content: string | null;
  priority: string;
  pinned: boolean;
};

type CreatedInboxFixture = {
  recordIds: string[];
  testRunId: string;
  metadata?: {
    itemType?: string;
    tenantId?: string | number;
    userId?: string | number;
  };
};

type CurrentUserContext = {
  tenantId: string;
  userId: string;
};

type CreatedCrmWorkbenchFixture = {
  accountId: string;
  leadId: string;
  opportunityId: string;
  activityId: string;
  leadCompany: string;
  leadContact: string;
  activitySubject: string;
  opportunityStage: string;
  opportunityAmount: number;
};

type CreatedBpmWorkbenchFixture = {
  processPid: string;
  processKey: string;
  processName: string;
  processInstanceId: string;
  businessKey: string;
};

type DynamicListResponse<T> = {
  records?: T[];
  total?: number;
};

type CrmLeadRecord = {
  id?: string | number;
  pid?: string | number;
  crm_lead_company?: string;
  crm_lead_contact_name?: string;
  crm_lead_status?: string;
  created_at?: string;
};

type CrmActivityRecord = {
  id?: string | number;
  pid?: string | number;
  crm_act_type?: string;
  crm_act_subject?: string;
  crm_activity_subject?: string;
  created_at?: string;
};

type PipelineStageRecord = {
  code?: string;
  label?: string;
  count?: number;
  amount?: string | number;
};

type BpmStartedProcessRecord = {
  instanceId?: string;
  processInstanceId?: string;
  processDefinitionId?: string;
  processDefinitionKey?: string;
  bizUniqueId?: string;
  businessKey?: string;
  status?: string;
};

type BpmStatsRecord = {
  completionRate?: number;
  avgDurationHours?: number;
  runningCount?: number;
  completedThisWeek?: number;
};

const SVG_DATA_URL =
  'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%22480%22%20height%3D%22200%22%20viewBox%3D%220%200%20480%20200%22%3E%3Crect%20width%3D%22480%22%20height%3D%22200%22%20fill%3D%22%23eef6ff%22%2F%3E%3Ctext%20x%3D%22240%22%20y%3D%22108%22%20font-size%3D%2232%22%20text-anchor%3D%22middle%22%20fill%3D%22%231d4ed8%22%3ERuntime%20Image%3C%2Ftext%3E%3C%2Fsvg%3E';

const __dirname = dirname(fileURLToPath(import.meta.url));
const BACKEND_PLUGIN_ROOT =
  process.env.OSS_PLUGIN_ROOT ??
  process.env.BACKEND_PLUGIN_ROOT ??
  resolve(__dirname, '../../../../plugins');

function futureIsoDate(daysFromNow: number): string {
  return new Date(Date.now() + daysFromNow * 24 * 60 * 60 * 1000).toISOString();
}

function runtimeWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-progress',
      type: 'smart-progress',
      title: 'Runtime Progress',
      x: 0,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Progress',
        dataSource: {
          type: 'static',
          staticData: [{ value: 75 }],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: {
          target: 100,
          format: 'percent',
          shape: 'bar',
        },
      },
    },
    {
      id: 'runtime-leaderboard',
      type: 'smart-leaderboard',
      title: 'Runtime Leaderboard',
      x: 3,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Leaderboard',
        dataSource: {
          type: 'static',
          staticData: [
            { region: 'North Zone', score: 9800 },
            { region: 'South Zone', score: 7200 },
            { region: 'West Zone', score: 4100 },
          ],
          dimensions: ['region'],
          metrics: [{ field: 'score', aggregation: 'sum', alias: 'score' }],
        },
        visualization: {
          rankField: 'region',
          valueField: 'score',
          maxItems: 3,
        },
      },
    },
    {
      id: 'runtime-rich-text',
      type: 'smart-rich-text',
      title: 'Runtime Rich Text Card',
      x: 6,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Rich Text Card',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          format: 'html',
          content: '<h2>Runtime Rich Text</h2><p>Sanitized semantic content</p>',
        },
      },
    },
    {
      id: 'runtime-image',
      type: 'smart-image',
      title: 'Runtime Image',
      x: 9,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Image',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          src: SVG_DATA_URL,
          alt: 'Runtime SVG Image',
          objectFit: 'contain',
        },
      },
    },
    {
      id: 'runtime-iframe',
      type: 'smart-iframe',
      title: 'Runtime Frame',
      x: 0,
      y: 3,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Frame',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          src: 'about:blank',
        },
      },
    },
    {
      id: 'runtime-countdown',
      type: 'smart-countdown',
      title: 'Runtime Countdown',
      x: 4,
      y: 3,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Countdown',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          targetDate: futureIsoDate(7),
          format: 'full',
          labels: {
            days: 'Days',
            hours: 'Hours',
            minutes: 'Minutes',
            seconds: 'Seconds',
          },
        },
      },
    },
    {
      id: 'runtime-stats-row',
      type: 'smart-stats-row',
      title: 'Runtime Stats Row',
      x: 0,
      y: 6,
      w: 12,
      h: 2,
      config: {
        title: 'Runtime Stats Row',
        dataSource: { type: 'static' },
      },
    },
    {
      id: 'runtime-stats-card',
      type: 'smart-stats-card',
      title: 'Runtime Stats Card',
      x: 0,
      y: 8,
      w: 3,
      h: 2,
      config: {
        title: 'Runtime Stats Card',
        dataSource: { type: 'static' },
        visualization: {
          statKey: 'inbox_pending',
        },
      },
    },
  ];
}

function foundationalChartWidgets(): DashboardWidgetFixture[] {
  const categoryRows = [
    { category: 'Alpha', value: 42, secondary: 11 },
    { category: 'Beta', value: 27, secondary: 18 },
  ];
  const scatterRows = [
    { x: 12, y: 30 },
    { x: 28, y: 54 },
  ];
  const heatmapRows = [
    { weekday: 'Mon', segment: 'AM', intensity: 7 },
    { weekday: 'Tue', segment: 'PM', intensity: 12 },
  ];

  return [
    {
      id: 'runtime-number-card',
      type: 'smart-number-card',
      title: 'Runtime Number Card',
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      config: {
        title: 'Runtime Number Card',
        suffix: ' units',
        dataSource: {
          type: 'static',
          staticData: [{ count: 1234 }],
          metrics: [{ field: 'count', aggregation: 'sum', alias: 'count' }],
        },
      },
    },
    {
      id: 'runtime-bar-chart',
      type: 'smart-bar-chart',
      title: 'Runtime Bar Chart',
      x: 3,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Bar Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
      },
    },
    {
      id: 'runtime-line-chart',
      type: 'smart-line-chart',
      title: 'Runtime Line Chart',
      x: 6,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Line Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { smooth: true, showArea: false },
      },
    },
    {
      id: 'runtime-pie-chart',
      type: 'smart-pie-chart',
      title: 'Runtime Pie Chart',
      x: 9,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Pie Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { donut: true, showLabels: true },
      },
    },
    {
      id: 'runtime-area-chart',
      type: 'smart-area-chart',
      title: 'Runtime Area Chart',
      x: 0,
      y: 3,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Area Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { smooth: true, fillOpacity: 0.65 },
      },
    },
    {
      id: 'runtime-funnel-chart',
      type: 'smart-funnel-chart',
      title: 'Runtime Funnel Chart',
      x: 3,
      y: 3,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Funnel Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { sort: 'descending' },
      },
    },
    {
      id: 'runtime-scatter-chart',
      type: 'smart-scatter-chart',
      title: 'Runtime Scatter Chart',
      x: 6,
      y: 3,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Scatter Chart',
        dataSource: {
          type: 'static',
          staticData: scatterRows,
          metrics: [
            { field: 'x', aggregation: 'sum', alias: 'x' },
            { field: 'y', aggregation: 'sum', alias: 'y' },
          ],
        },
      },
    },
    {
      id: 'runtime-radar-chart',
      type: 'smart-radar-chart',
      title: 'Runtime Radar Chart',
      x: 9,
      y: 3,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Radar Chart',
        dataSource: {
          type: 'static',
          staticData: [
            { name: 'Alpha', quality: 82, speed: 76 },
            { name: 'Beta', quality: 68, speed: 91 },
          ],
          dimensions: ['name'],
          metrics: [
            { field: 'quality', aggregation: 'sum', alias: 'quality' },
            { field: 'speed', aggregation: 'sum', alias: 'speed' },
          ],
        },
      },
    },
    {
      id: 'runtime-table-chart',
      type: 'smart-table-chart',
      title: 'Runtime Table Chart',
      x: 0,
      y: 6,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Table Chart',
        dataSource: {
          type: 'static',
          staticData: [
            { region: 'North', cases: 42 },
            { region: 'South', cases: 27 },
          ],
          dimensions: ['region'],
          metrics: [{ field: 'cases', aggregation: 'sum', alias: 'cases' }],
        },
        table: {
          columns: [
            { field: 'region', label: 'Region' },
            { field: 'cases', label: 'Cases', align: 'right' },
          ],
        },
        pageSize: 5,
      },
    },
    {
      id: 'runtime-gauge-chart',
      type: 'smart-gauge-chart',
      title: 'Runtime Gauge Chart',
      x: 4,
      y: 6,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Gauge Chart',
        dataSource: {
          type: 'static',
          staticData: [{ value: 68 }],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { min: 0, max: 100, splitNumber: 5 },
      },
    },
    {
      id: 'runtime-heatmap-chart',
      type: 'smart-heatmap-chart',
      title: 'Runtime Heatmap Chart',
      x: 8,
      y: 6,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Heatmap Chart',
        dataSource: {
          type: 'static',
          staticData: heatmapRows,
          dimensions: ['weekday', 'segment'],
          metrics: [{ field: 'intensity', aggregation: 'sum', alias: 'intensity' }],
        },
        visualization: { xField: 'weekday', yField: 'segment', valueField: 'intensity' },
      },
    },
    {
      id: 'runtime-treemap-chart',
      type: 'smart-treemap-chart',
      title: 'Runtime Treemap Chart',
      x: 0,
      y: 9,
      w: 6,
      h: 3,
      config: {
        title: 'Runtime Treemap Chart',
        dataSource: {
          type: 'static',
          staticData: categoryRows,
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { nameField: 'category', valueField: 'value' },
      },
    },
    {
      id: 'runtime-map-chart',
      type: 'smart-map-chart',
      title: 'Runtime Map Chart',
      x: 6,
      y: 9,
      w: 6,
      h: 3,
      config: {
        title: 'Runtime Map Chart',
        dataSource: {
          type: 'static',
          staticData: [{ region: 'East', value: 9 }],
          dimensions: ['region'],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: { mapRegion: 'china', regionField: 'region', valueField: 'value' },
      },
    },
  ];
}

function quickNoteWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-quick-note',
      type: 'smart-quick-note',
      title: 'Runtime Quick Note',
      x: 0,
      y: 0,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Quick Note',
        dataSource: { type: 'static' },
      },
    },
  ];
}

function shortcutWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-shortcuts',
      type: 'smart-shortcuts',
      title: 'Runtime Shortcuts',
      x: 0,
      y: 0,
      w: 6,
      h: 2,
      config: {
        title: 'Runtime Shortcuts',
        dataSource: { type: 'static' },
        shortcuts: [
          {
            label: 'Runtime Dashboards',
            icon: '>',
            path: '/dashboards',
            color: 'bg-blue-50',
          },
        ],
      },
    },
  ];
}

function recentWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-recent',
      type: 'smart-recent',
      title: 'Runtime Recent',
      x: 0,
      y: 0,
      w: 6,
      h: 3,
      config: {
        title: 'Runtime Recent',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 6,
        },
      },
    },
  ];
}

function announcementWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-announcement',
      type: 'smart-announcement',
      title: 'Runtime Announcement',
      x: 0,
      y: 0,
      w: 6,
      h: 3,
      config: {
        title: 'Runtime Announcement',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 5,
        },
      },
    },
  ];
}

function inboxCalendarWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-inbox',
      type: 'smart-inbox',
      title: 'Runtime Inbox',
      x: 0,
      y: 0,
      w: 7,
      h: 4,
      config: {
        title: 'Runtime Inbox',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 5,
          itemTypes: 'approval',
        },
      },
    },
    {
      id: 'runtime-calendar',
      type: 'smart-calendar',
      title: 'Runtime Calendar',
      x: 7,
      y: 0,
      w: 5,
      h: 4,
      config: {
        title: 'Runtime Calendar',
        dataSource: { type: 'static' },
      },
    },
  ];
}

function crmWorkbenchWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-pipeline',
      type: 'smart-pipeline',
      title: 'Runtime CRM Pipeline',
      x: 0,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime CRM Pipeline',
        dataSource: { type: 'static' },
      },
    },
    {
      id: 'runtime-leads',
      type: 'smart-leads',
      title: 'Runtime CRM Leads',
      x: 4,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime CRM Leads',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 5,
        },
      },
    },
    {
      id: 'runtime-activities',
      type: 'smart-activities',
      title: 'Runtime CRM Activities',
      x: 8,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime CRM Activities',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 6,
        },
      },
    },
  ];
}

function bpmWorkbenchWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-my-process',
      type: 'smart-my-process',
      title: 'Runtime BPM My Process',
      x: 0,
      y: 0,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime BPM My Process',
        dataSource: { type: 'static' },
        visualization: {
          maxItems: 5,
        },
      },
    },
    {
      id: 'runtime-process-stats',
      type: 'smart-process-stats',
      title: 'Runtime BPM Process Stats',
      x: 6,
      y: 0,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime BPM Process Stats',
        dataSource: { type: 'static' },
      },
    },
  ];
}

function advancedRuntimeWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-wordcloud',
      type: 'smart-wordcloud-chart',
      title: 'Runtime Word Cloud',
      x: 0,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime Word Cloud',
        dataSource: {
          type: 'static',
          staticData: [
            { keyword: 'Runtime', weight: 42 },
            { keyword: 'Coverage', weight: 21 },
          ],
          dimensions: ['keyword'],
          metrics: [{ field: 'weight', aggregation: 'sum', alias: 'weight' }],
        },
        visualization: {
          colorTheme: 'brand',
          gridSize: 6,
        },
      },
    },
    {
      id: 'runtime-combo',
      type: 'smart-combo-chart',
      title: 'Runtime Combo',
      x: 4,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime Combo',
        dataSource: {
          type: 'static',
          staticData: [
            { quarter: 'Q1', revenue: 120, conversion: 30 },
            { quarter: 'Q2', revenue: 180, conversion: 42 },
          ],
          dimensions: ['quarter'],
          metrics: [
            { field: 'revenue', aggregation: 'sum', alias: 'revenue' },
            { field: 'conversion', aggregation: 'sum', alias: 'conversion' },
          ],
        },
        visualization: {
          seriesConfig: [
            { metricIndex: 0, chartType: 'bar', yAxisIndex: 0, showLabel: true },
            { metricIndex: 1, chartType: 'line', yAxisIndex: 1, showLabel: true },
          ],
          yAxisLeft: { name: 'Revenue' },
          yAxisRight: { name: 'Conversion' },
        },
      },
    },
    {
      id: 'runtime-nps',
      type: 'smart-nps-chart',
      title: 'Runtime NPS',
      x: 8,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime NPS',
        dataSource: {
          type: 'static',
          staticData: [{ score: 10 }, { score: 9 }, { score: 8 }, { score: 4 }],
          dimensions: ['score'],
          metrics: [{ field: 'score', aggregation: 'sum', alias: 'score' }],
        },
        visualization: {
          scoreField: 'score',
          showLegend: true,
          ringWidth: 28,
        },
      },
    },
    {
      id: 'runtime-gallery',
      type: 'smart-gallery',
      title: 'Runtime Gallery',
      x: 0,
      y: 4,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime Gallery',
        dataSource: { type: 'static' },
        visualization: {
          staticItems: [
            {
              image: SVG_DATA_URL,
              title: 'Runtime Gallery Alpha',
              description: 'Gallery item from authored visualization props',
            },
          ],
          columns: 2,
          imageFit: 'contain',
        },
      },
    },
    {
      id: 'runtime-kanban',
      type: 'smart-kanban',
      title: 'Runtime Kanban',
      x: 6,
      y: 4,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime Kanban',
        dataSource: {
          type: 'static',
          staticData: [
            {
              id: 'card-a',
              stage: 'Backlog',
              title: 'Runtime Card A',
              description: 'Backlog card rendered from static rows',
            },
            {
              id: 'card-b',
              stage: 'Done',
              title: 'Runtime Card B',
              description: 'Done card rendered from static rows',
            },
          ],
          dimensions: ['stage'],
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
        visualization: {
          groupField: 'stage',
          titleField: 'title',
          descriptionField: 'description',
          columnOrder: ['Backlog', 'Done'],
          showCount: true,
        },
      },
    },
  ];
}

function generateMinimalBpmn(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="runtimeApproval" name="Runtime BPM Approval"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="runtimeApproval"/>
    <sequenceFlow id="flow2" sourceRef="runtimeApproval" targetRef="end"/>
  </process>
</definitions>`;
}

async function parseJsonResponse<T>(
  response: APIResponse | PlaywrightResponse,
  context: string,
): Promise<T> {
  const text = await response.text();
  expect(response.ok(), `${context} failed: status=${response.status()} body=${text}`).toBe(true);
  return JSON.parse(text) as T;
}

async function createPublishedDashboard(
  page: Page,
  widgets: DashboardWidgetFixture[] = runtimeWidgets(),
  titlePrefix = 'Runtime Widget Matrix',
): Promise<CreatedDashboard> {
  const title = `${titlePrefix} ${Date.now()}`;
  const createResponse = await page.request.post('/api/dashboards', {
    data: {
      title,
      scope: 'global',
      layoutConfig: {
        columns: 12,
        rowHeight: 96,
        gap: 12,
        compactType: 'vertical',
      },
      widgets,
    },
  });
  const createBody = await parseJsonResponse<{ data?: { pid?: string; code?: string } }>(
    createResponse,
    'create dashboard',
  );
  const pid = createBody.data?.pid;
  const code = createBody.data?.code;
  expect(pid, 'created dashboard pid').toBeTruthy();
  expect(code, 'created dashboard code').toBeTruthy();

  const publishResponse = await page.request.post(`/api/dashboards/${pid}/publish`);
  await parseJsonResponse(publishResponse, 'publish dashboard');

  return { pid: pid!, code: code!, title };
}

async function cleanupDashboard(page: Page, pid?: string): Promise<void> {
  if (!pid) return;
  await page.request.post(`/api/dashboards/${pid}/unpublish`).catch(() => undefined);
  await page.request.delete(`/api/dashboards/${pid}`).catch(() => undefined);
}

async function createRecentEngagementFixture(page: Page): Promise<CreatedEngagement> {
  const targetId = `runtime-recent-${Date.now()}`;
  const targetPath = `/runtime/recent/${targetId}`;
  const response = await page.request.post('/api/user-engagement', {
    data: {
      targetType: 'page',
      targetId,
      targetLabel: `Runtime Recent ${targetId}`,
      targetContext: {
        path: targetPath,
        icon: 'layout-dashboard',
        modelCode: 'dashboard',
      },
      engagementType: 'recent_view',
      sortOrder: -100,
    },
  });
  const body = await parseJsonResponse<{ data?: CreatedEngagement }>(
    response,
    'create recent engagement',
  );
  expect(body.data?.id, 'created recent engagement id').toMatch(/^\d+$/);
  expect(body.data?.targetId, 'created recent target id').toBe(targetId);
  expect(typeof body.data?.id, 'created recent id should be a JS-safe string').toBe('string');
  await expectRecentListContains(page, targetId, 'recent list immediately after create');
  return body.data!;
}

async function cleanupEngagement(page: Page, id?: string): Promise<void> {
  if (!id) return;
  await page.request.delete(`/api/user-engagement/${id}`).catch(() => undefined);
}

async function expectRecentListContains(
  page: Page,
  targetId: string,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<CreatedEngagement> {
  const listResponse = response ?? await page.request.get('/api/user-engagement', {
    params: {
      engagementType: 'recent_view',
      targetType: 'page',
    },
  });
  const listBody = await parseJsonResponse<{ data?: CreatedEngagement[] }>(
    listResponse,
    context,
  );
  const match = listBody.data?.find((item) => item.targetId === targetId);
  expect(match, `${context} should include ${targetId}`).toBeTruthy();
  return match!;
}

async function createAnnouncementFixture(page: Page): Promise<CreatedAnnouncement> {
  const title = `Runtime Announcement ${Date.now()}`;
  const response = await page.request.post('/api/announcements', {
    data: {
      title,
      content: `Runtime announcement body ${title}`,
      priority: 'urgent',
      status: 'active',
      pinned: true,
    },
  });
  const body = await parseJsonResponse<{ data?: CreatedAnnouncement }>(
    response,
    'create announcement',
  );
  expect(body.data?.id, 'created announcement id').toMatch(/^\d+$/);
  expect(typeof body.data?.id, 'created announcement id should be a JS-safe string').toBe('string');
  expect(body.data?.title, 'created announcement title').toBe(title);
  await expectAnnouncementListContains(page, title, 'announcement list immediately after create');
  return body.data!;
}

async function cleanupAnnouncement(page: Page, id?: string): Promise<void> {
  if (!id) return;
  await page.request.delete(`/api/announcements/${id}`).catch(() => undefined);
}

async function expectAnnouncementListContains(
  page: Page,
  title: string,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<CreatedAnnouncement> {
  const listResponse = response ?? await page.request.get('/api/announcements', {
    params: { limit: '10' },
  });
  const listBody = await parseJsonResponse<{ data?: CreatedAnnouncement[] }>(
    listResponse,
    context,
  );
  const match = listBody.data?.find((item) => item.title === title);
  expect(match, `${context} should include ${title}`).toBeTruthy();
  return match!;
}

async function createInboxFixture(page: Page): Promise<CreatedInboxFixture> {
  const testRunId = `dwr-inbox-${Date.now()}`;
  const currentUser = await getCurrentUserContext(page);
  const response = await page.request.post('/api/test/fixture', {
    data: {
      name: 'approval',
      testRunId,
      params: {
        count: 1,
        tenantId: currentUser.tenantId,
        userId: currentUser.userId,
      },
    },
  });
  const body = await parseJsonResponse<CreatedInboxFixture>(response, 'create inbox fixture');
  expect(body.recordIds, 'created inbox record ids').toHaveLength(1);
  expect(body.metadata?.itemType, 'created inbox item type').toBe('approval');
  expect(String(body.metadata?.tenantId), 'created inbox tenant id').toBe(currentUser.tenantId);
  expect(String(body.metadata?.userId), 'created inbox user id').toBe(currentUser.userId);
  await expectInboxListContains(page, testRunId, 'inbox list immediately after fixture create');
  return body;
}

async function getCurrentUserContext(page: Page): Promise<CurrentUserContext> {
  const response = await page.request.get('/api/auth/me');
  const body = await parseJsonResponse<{
    data?: {
      user?: {
        id?: string | number;
        tenantId?: string | number;
      };
      tenantId?: string | number;
    };
  }>(response, 'read current user');
  const userId = body.data?.user?.id;
  const tenantId = body.data?.user?.tenantId ?? body.data?.tenantId;
  expect(userId, 'current user id').toBeTruthy();
  expect(tenantId, 'current tenant id').toBeTruthy();
  return {
    userId: String(userId),
    tenantId: String(tenantId),
  };
}

async function expectInboxListContains(
  page: Page,
  testRunId: string,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const listResponse = response ?? await page.request.get('/api/inbox', {
    params: {
      status: 'pending',
      itemType: 'approval',
      pageNum: '1',
      pageSize: '10',
    },
  });
  const body = await parseJsonResponse<{ data?: { records?: Array<{ title?: string }> } }>(
    listResponse,
    context,
  );
  const match = body.data?.records?.find((item) => item.title?.includes(testRunId));
  expect(match, `${context} should include ${testRunId}`).toBeTruthy();
}

async function createCrmWorkbenchFixture(page: Page): Promise<CreatedCrmWorkbenchFixture> {
  await ensureCrmStarterPluginImported(page);

  const suffix = uniqueId('DWRCRM');
  const leadCompany = `DWR CRM Lead ${suffix}`;
  const leadContact = `DWR Contact ${suffix}`;
  const activitySubject = `DWR CRM Activity ${suffix}`;
  const opportunityAmount = 87654321;

  const account = await executeCommandViaApi(
    page,
    'crm:create_account',
    {
      crm_acc_name: `DWR CRM Account ${suffix}`,
      crm_acc_industry: 'technology',
      crm_acc_phone: '555-0101',
    },
    undefined,
    'create',
  );
  expect(account.code, 'create CRM account command code').toBe('0');
  expect(account.recordId, 'created CRM account id').toBeTruthy();

  const lead = await executeCommandViaApi(
    page,
    'crm:create_lead',
    {
      crm_lead_company: leadCompany,
      crm_lead_contact_name: leadContact,
      crm_lead_source: 'referral',
      crm_lead_contact_email: `${suffix.toLowerCase()}@example.test`,
    },
    undefined,
    'create',
  );
  expect(lead.code, 'create CRM lead command code').toBe('0');
  expect(lead.recordId, 'created CRM lead id').toBeTruthy();

  const opportunity = await executeCommandViaApi(
    page,
    'crm:create_opportunity',
    {
      crm_opp_name: `DWR CRM Opportunity ${suffix}`,
      crm_opp_account_id: account.recordId,
      crm_opp_expected_amount: opportunityAmount,
      crm_opp_probability: 80,
    },
    undefined,
    'create',
  );
  expect(opportunity.code, 'create CRM opportunity command code').toBe('0');
  expect(opportunity.recordId, 'created CRM opportunity id').toBeTruthy();

  const qualify = await executeCommandViaApi(
    page,
    'crm:qualify_opportunity',
    {},
    opportunity.recordId,
    'state_transition',
  );
  expect(qualify.code, 'qualify CRM opportunity command code').toBe('0');

  const activity = await executeCommandViaApi(
    page,
    'crm:create_activity',
    {
      crm_act_type: 'call',
      crm_act_subject: activitySubject,
      crm_act_content: `Runtime activity content ${suffix}`,
    },
    undefined,
    'create',
  );
  expect(activity.code, 'create CRM activity command code').toBe('0');
  expect(activity.recordId, 'created CRM activity id').toBeTruthy();

  const fixture = {
    accountId: account.recordId,
    leadId: lead.recordId,
    opportunityId: opportunity.recordId,
    activityId: activity.recordId,
    leadCompany,
    leadContact,
    activitySubject,
    opportunityStage: 'qualification',
    opportunityAmount,
  };

  await expectCrmLeadListContains(page, fixture, 'CRM lead list immediately after fixture create');
  await expectCrmActivityListContains(
    page,
    fixture,
    'CRM activity list immediately after fixture create',
  );
  await expectPipelineIncludesStageAmount(
    page,
    fixture,
    'CRM pipeline immediately after fixture create',
  );

  return fixture;
}

async function ensureCrmStarterPluginImported(page: Page): Promise<void> {
  if (await hasCommand(page, 'crm_account', 'crm:create_account')) {
    return;
  }

  const pluginDir = resolve(BACKEND_PLUGIN_ROOT, 'crm-starter');
  const manifestPath = resolve(pluginDir, 'plugin.json');
  expect(existsSync(manifestPath), `crm-starter manifest missing: ${manifestPath}`).toBe(true);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8'));
  expect(manifest?.pluginId, `unexpected crm-starter manifest: ${manifestPath}`).toBe(
    'com.auraboot.crm-starter',
  );

  const importResponse = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: pluginDir,
      conflictStrategy: 'OVERWRITE',
      validateReferences: true,
      autoDeployProcesses: false,
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
      createResourcePermissions: true,
    },
    timeout: 120_000,
  });
  const rawBody = await importResponse.text();
  expect(importResponse.ok(), `import crm-starter HTTP ${importResponse.status()}: ${rawBody}`).toBe(
    true,
  );
  const body = JSON.parse(rawBody) as {
    data?: { success?: boolean; status?: string; errorMessage?: string };
    success?: boolean;
    status?: string;
    errorMessage?: string;
  };
  const result = body?.data && typeof body.data === 'object' ? body.data : body;
  expect(
    result?.success,
    `import crm-starter did not succeed (status=${result?.status ?? '?'}, msg=${
      result?.errorMessage ?? '?'
    })`,
  ).toBe(true);
  expect(await hasCommand(page, 'crm_account', 'crm:create_account')).toBe(true);
}

async function hasCommand(page: Page, modelCode: string, commandCode: string): Promise<boolean> {
  const response = await page.request.get('/api/meta/commands', {
    params: { modelCode },
  });
  if (!response.ok()) return false;
  const body = await response.json().catch(() => ({}));
  const commands = Array.isArray(body?.data) ? body.data : [];
  return commands.some((command: { code?: string }) => command?.code === commandCode);
}

async function cleanupCrmWorkbenchFixture(
  page: Page,
  fixture?: CreatedCrmWorkbenchFixture,
): Promise<void> {
  if (!fixture) return;
  await executeCommandViaApi(
    page,
    'crm:delete_activity',
    {},
    fixture.activityId,
    'delete',
    { allowHttpError: true },
  ).catch(() => undefined);
  await executeCommandViaApi(
    page,
    'crm:delete_lead',
    {},
    fixture.leadId,
    'delete',
    { allowHttpError: true },
  ).catch(() => undefined);
  await executeCommandViaApi(
    page,
    'crm:delete_opportunity',
    {},
    fixture.opportunityId,
    'delete',
    { allowHttpError: true },
  ).catch(() => undefined);
  await executeCommandViaApi(
    page,
    'crm:delete_account',
    {},
    fixture.accountId,
    'delete',
    { allowHttpError: true },
  ).catch(() => undefined);
}

async function expectCrmLeadListContains(
  page: Page,
  fixture: CreatedCrmWorkbenchFixture,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const listResponse = response ?? await page.request.get('/api/dynamic/crm_lead/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      keyword: fixture.leadCompany,
    },
  });
  const body = await parseJsonResponse<{ data?: DynamicListResponse<CrmLeadRecord> }>(
    listResponse,
    context,
  );
  const match = body.data?.records?.find((record) =>
    record.crm_lead_company === fixture.leadCompany &&
    record.crm_lead_contact_name === fixture.leadContact,
  );
  expect(match, `${context} should include ${fixture.leadCompany}`).toBeTruthy();
}

async function expectCrmActivityListContains(
  page: Page,
  fixture: CreatedCrmWorkbenchFixture,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const listResponse = response ?? await page.request.get('/api/dynamic/crm_activity/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      keyword: fixture.activitySubject,
    },
  });
  const body = await parseJsonResponse<{ data?: DynamicListResponse<CrmActivityRecord> }>(
    listResponse,
    context,
  );
  const match = body.data?.records?.find((record) =>
    (record.crm_act_subject ?? record.crm_activity_subject) === fixture.activitySubject,
  );
  expect(match, `${context} should include ${fixture.activitySubject}`).toBeTruthy();
}

async function expectPipelineIncludesStageAmount(
  page: Page,
  fixture: CreatedCrmWorkbenchFixture,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const pipelineResponse = response ?? await page.request.get('/api/workbench/pipeline');
  const body = await parseJsonResponse<{ data?: { stages?: PipelineStageRecord[] } }>(
    pipelineResponse,
    context,
  );
  const stage = body.data?.stages?.find((item) => item.code === fixture.opportunityStage);
  expect(stage, `${context} should include stage ${fixture.opportunityStage}`).toBeTruthy();
  expect(Number(stage?.count ?? 0), `${context} stage count`).toBeGreaterThanOrEqual(1);
  expect(Number(stage?.amount ?? 0), `${context} stage amount`).toBeGreaterThanOrEqual(
    fixture.opportunityAmount,
  );
}

async function createBpmWorkbenchFixture(page: Page): Promise<CreatedBpmWorkbenchFixture> {
  const suffix = uniqueId('DWRBPM');
  const processKey = `dwr_bpm_${suffix.toLowerCase()}`;
  const processName = `DWR BPM Process ${suffix}`;
  const businessKey = `DWR-BPM-${suffix}`;
  const bpmnContent = generateMinimalBpmn(processKey, processName);

  const createResponse = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey,
      processName,
      description: 'Runtime dashboard BPM widget fixture',
      category: 'dashboard-runtime',
      bpmnContent,
    },
    timeout: 30_000,
  });
  const createBody = await parseJsonResponse<{ data?: { pid?: string } }>(
    createResponse,
    'create BPM process definition',
  );
  const processPid = createBody.data?.pid;
  expect(processPid, 'created BPM process pid').toBeTruthy();

  const deployResponse = await page.request.post(`/api/bpm/process-definitions/${processPid}/deploy`, {
    timeout: 30_000,
  });
  await parseJsonResponse(deployResponse, 'deploy BPM process definition');

  const startResponse = await page.request.post('/api/bpm/process-instances', {
    data: {
      processDefinitionId: processKey,
      businessKey,
      variables: {
        title: processName,
        source: 'dashboard-widget-runtime',
      },
    },
    timeout: 30_000,
  });
  const startBody = await parseJsonResponse<{
    data?: { instanceId?: string; processInstanceId?: string; id?: string };
  }>(startResponse, 'start BPM process instance');
  const processInstanceId =
    startBody.data?.instanceId ?? startBody.data?.processInstanceId ?? startBody.data?.id;
  expect(processInstanceId, 'started BPM process instance id').toBeTruthy();

  const fixture = {
    processPid: processPid!,
    processKey,
    processName,
    processInstanceId: processInstanceId!,
    businessKey,
  };
  await expectBpmWorkbenchContainsProcess(
    page,
    fixture,
    'BPM workbench immediately after fixture create',
  );
  await expectBpmStatsIncludesRunning(page, 'BPM stats immediately after fixture create');
  return fixture;
}

async function cleanupBpmWorkbenchFixture(
  page: Page,
  fixture?: CreatedBpmWorkbenchFixture,
): Promise<void> {
  if (!fixture) return;
  await page.request
    .post(`/api/bpm/process-instances/${fixture.processInstanceId}/terminate`, {
      data: { reason: 'dashboard runtime fixture cleanup' },
    })
    .catch(() => undefined);
  await page.request
    .post(`/api/bpm/process-definitions/${fixture.processPid}/undeploy`)
    .catch(() => undefined);
  await page.request
    .delete(`/api/bpm/process-definitions/${fixture.processPid}`)
    .catch(() => undefined);
}

async function expectBpmWorkbenchContainsProcess(
  page: Page,
  fixture: CreatedBpmWorkbenchFixture,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const workbenchResponse = response ?? await page.request.get('/api/bpm/workbench');
  const body = await parseJsonResponse<{
    data?: { startedProcesses?: BpmStartedProcessRecord[] };
  }>(workbenchResponse, context);
  const match = body.data?.startedProcesses?.find((process) => {
    const instanceId = process.instanceId ?? process.processInstanceId;
    const businessKey = process.businessKey ?? process.bizUniqueId;
    return instanceId === fixture.processInstanceId || businessKey === fixture.businessKey;
  });
  expect(match, `${context} should include ${fixture.businessKey}`).toBeTruthy();
}

async function expectBpmStatsIncludesRunning(
  page: Page,
  context: string,
  response?: APIResponse | PlaywrightResponse,
): Promise<void> {
  const statsResponse = response ?? await page.request.get('/api/workbench/bpm-stats');
  const body = await parseJsonResponse<{ data?: BpmStatsRecord }>(statsResponse, context);
  // The seeded BPM instance creates a lower-bound contract; pre-existing live data may be higher.
  expect(body.data?.runningCount ?? 0, `${context} runningCount`).toBeGreaterThanOrEqual(1);
}

async function expectRuntimeBlock(page: Page, id: string, type: string): Promise<Locator> {
  const block = page.getByTestId(`dashboard-block-${id}`);
  await block.scrollIntoViewIfNeeded();
  await expect(block).toBeVisible({ timeout: 10_000 });
  await expect(block.locator(`[data-widget-type="${type}"]`)).toBeVisible({ timeout: 10_000 });
  await expect(block).not.toContainText('Unknown widget');
  return block;
}

async function expectRenderedChartSurface(block: Locator, label: string): Promise<void> {
  const surface = block.locator(
    '[data-widget-type] .echarts-for-react, [data-widget-type] canvas, [data-widget-type] svg',
  );
  await expect(surface.first(), `${label} should render an ECharts surface`).toBeVisible({
    timeout: 10_000,
  });
  const largestSurface = await surface.evaluateAll((nodes) =>
    nodes.reduce(
      (largest, node) => {
        const rect = node.getBoundingClientRect();
        const area = rect.width * rect.height;
        return area > largest.area
          ? { area, width: rect.width, height: rect.height }
          : largest;
      },
      { area: 0, width: 0, height: 0 },
    ),
  );
  expect(largestSurface.width, `${label} chart width`).toBeGreaterThan(40);
  expect(largestSurface.height, `${label} chart height`).toBeGreaterThan(40);
  await expect(block, `${label} should exit loading state`).not.toContainText('Loading...', {
    timeout: 10_000,
  });
  await expect(block, `${label} should not show chart error`).not.toContainText(
    /Failed to load|Please configure|No data/,
  );
}

async function currentUserNoteContent(page: Page): Promise<string> {
  const response = await page.request.get('/api/user-notes');
  const body = await parseJsonResponse<{ data?: { content?: string | null } }>(
    response,
    'read user note',
  );
  return body.data?.content ?? '';
}

async function saveQuickNoteThroughUi(page: Page, content: string): Promise<void> {
  const textarea = page.getByTestId('quick-note-textarea');
  await textarea.scrollIntoViewIfNeeded();
  await expect(textarea).toBeVisible({ timeout: 10_000 });

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/user-notes') &&
      response.request().method() === 'PUT' &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await textarea.fill(content);
  await textarea.evaluate((node: HTMLTextAreaElement) => node.blur());
  await saveResponse;
}

test.describe('Dashboard Widget Runtime Semantics', () => {
  test('DWR-014: published viewer renders foundational chart widget runtime semantics', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    try {
      dashboard = await createPublishedDashboard(
        page,
        foundationalChartWidgets(),
        'Foundational Chart Runtime Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const numberCard = await expectRuntimeBlock(page, 'runtime-number-card', 'smart-number-card');
      await expect(numberCard).toContainText('Runtime Number Card');
      await expect(numberCard).toContainText('1,234 units');

      for (const [id, type] of [
        ['runtime-bar-chart', 'smart-bar-chart'],
        ['runtime-line-chart', 'smart-line-chart'],
        ['runtime-pie-chart', 'smart-pie-chart'],
        ['runtime-area-chart', 'smart-area-chart'],
        ['runtime-funnel-chart', 'smart-funnel-chart'],
        ['runtime-scatter-chart', 'smart-scatter-chart'],
        ['runtime-radar-chart', 'smart-radar-chart'],
        ['runtime-gauge-chart', 'smart-gauge-chart'],
        ['runtime-heatmap-chart', 'smart-heatmap-chart'],
        ['runtime-treemap-chart', 'smart-treemap-chart'],
      ] as const) {
        const block = await expectRuntimeBlock(page, id, type);
        await expectRenderedChartSurface(block, type);
      }

      const table = await expectRuntimeBlock(page, 'runtime-table-chart', 'smart-table-chart');
      await expect(table).toContainText('Runtime Table Chart');
      await expect(table).toContainText('Region');
      await expect(table).toContainText('Cases');
      await expect(table).toContainText('North');
      await expect(table).toContainText('42');

      const map = await expectRuntimeBlock(page, 'runtime-map-chart', 'smart-map-chart');
      await expect(map).toContainText('Runtime Map Chart');
      await expect(map.locator('[data-widget-type="smart-map-chart"] svg').last()).toBeVisible();
      await expect(map).not.toContainText('Unknown widget');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-001: published viewer renders representative widget runtime semantics', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    try {
      dashboard = await createPublishedDashboard(page);

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const progress = await expectRuntimeBlock(page, 'runtime-progress', 'smart-progress');
      await expect(progress).toContainText('75%');

      const leaderboard = await expectRuntimeBlock(
        page,
        'runtime-leaderboard',
        'smart-leaderboard',
      );
      await expect(leaderboard).toContainText('North Zone');
      await expect(leaderboard).toContainText('9.8K');
      await expect(leaderboard).toContainText('South Zone');

      const richText = await expectRuntimeBlock(page, 'runtime-rich-text', 'smart-rich-text');
      await expect(richText.getByRole('heading', { name: 'Runtime Rich Text' })).toBeVisible();
      await expect(richText).toContainText('Sanitized semantic content');

      const imageBlock = await expectRuntimeBlock(page, 'runtime-image', 'smart-image');
      const image = imageBlock.locator('img[alt="Runtime SVG Image"]');
      await expect(image).toBeVisible();
      await expect(image).toHaveAttribute('src', /data:image\/svg\+xml/);
      await expect(image).toHaveCSS('object-fit', 'contain');

      const iframeBlock = await expectRuntimeBlock(page, 'runtime-iframe', 'smart-iframe');
      const iframe = iframeBlock.locator('iframe[title="Runtime Frame"]');
      await expect(iframe).toBeVisible();
      await expect(iframe).toHaveAttribute('src', 'about:blank');

      const countdown = await expectRuntimeBlock(page, 'runtime-countdown', 'smart-countdown');
      await expect(countdown).toContainText('Runtime Countdown');
      await expect(countdown).toContainText('Days');
      await expect(countdown).toContainText('Hours');

      const statsRow = await expectRuntimeBlock(page, 'runtime-stats-row', 'smart-stats-row');
      await expect(statsRow.getByTestId('stats-row')).toBeVisible();
      await expect(statsRow.locator('[data-testid^="stat-card-"]')).toHaveCount(4);

      const statsCard = await expectRuntimeBlock(page, 'runtime-stats-card', 'smart-stats-card');
      const singleCard = statsCard.getByTestId('stat-card-inbox_pending');
      await expect(singleCard).toBeVisible();
      await expect(singleCard).not.toContainText('—', { timeout: 10_000 });
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-002: quick-note widget persists note through viewer interaction', async ({ page }) => {
    const originalNote = await currentUserNoteContent(page);
    const noteContent = `Quick note runtime ${Date.now()}`;
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        quickNoteWidgets(),
        'Runtime Quick Note Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      await expectRuntimeBlock(page, 'runtime-quick-note', 'smart-quick-note');
      await saveQuickNoteThroughUi(page, noteContent);
      await expect(page.getByText(/刚刚保存|Just saved|Last saved/)).toBeVisible({
        timeout: 10_000,
      });

      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('quick-note-textarea')).toHaveValue(noteContent, {
        timeout: 10_000,
      });
    } finally {
      if (dashboard) {
        await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' }).catch(
          () => undefined,
        );
        await saveQuickNoteThroughUi(page, originalNote).catch(() => undefined);
      }
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-003: shortcuts widget navigates from published viewer interaction', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        shortcutWidgets(),
        'Runtime Shortcuts Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const shortcuts = await expectRuntimeBlock(page, 'runtime-shortcuts', 'smart-shortcuts');
      await expect(shortcuts.getByTestId('shortcuts-list')).toBeVisible();

      const shortcut = shortcuts.getByRole('link', { name: /Runtime Dashboards/ });
      await expect(shortcut).toHaveAttribute('href', /\/dashboards$/);

      await shortcut.click();
      await expect(page).toHaveURL(/\/dashboards$/);
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-004: published viewer renders advanced chart and view widgets', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        advancedRuntimeWidgets(),
        'Runtime Advanced Widget Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const wordCloud = await expectRuntimeBlock(
        page,
        'runtime-wordcloud',
        'smart-wordcloud-chart',
      );
      await expectRenderedChartSurface(wordCloud, 'word cloud');

      const combo = await expectRuntimeBlock(page, 'runtime-combo', 'smart-combo-chart');
      await expectRenderedChartSurface(combo, 'combo chart');

      const nps = await expectRuntimeBlock(page, 'runtime-nps', 'smart-nps-chart');
      await expectRenderedChartSurface(nps, 'NPS chart');

      const gallery = await expectRuntimeBlock(page, 'runtime-gallery', 'smart-gallery');
      await expect(gallery).toContainText('Runtime Gallery Alpha');
      await expect(gallery).toContainText('Gallery item from authored visualization props');
      await expect(gallery.locator('img[alt="Runtime Gallery Alpha"]')).toBeVisible();

      const kanban = await expectRuntimeBlock(page, 'runtime-kanban', 'smart-kanban');
      await expect(kanban).toContainText('Backlog');
      await expect(kanban).toContainText('Done');
      await expect(kanban).toContainText('Runtime Card A');
      await expect(kanban).toContainText('Runtime Card B');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-005: recent widget renders engagement API recent page visits', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let recent: CreatedEngagement | undefined;

    try {
      recent = await createRecentEngagementFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        recentWidgets(),
        'Runtime Recent Widget Matrix',
      );

      await page.addInitScript(() => {
        window.localStorage.removeItem('auraboot:recent-visits');
      });
      const recentListResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/user-engagement') &&
          response.url().includes('engagementType=recent_view') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });
      await expectRecentListContains(
        page,
        recent.targetId,
        'recent list consumed by viewer',
        await recentListResponse,
      );

      const recentBlock = await expectRuntimeBlock(page, 'runtime-recent', 'smart-recent');
      await expect(recentBlock).toContainText('Runtime Recent');
      await expect(recentBlock.getByRole('link', { name: new RegExp(recent.targetLabel) })).toBeVisible({
        timeout: 10_000,
      });
      await expect(recentBlock.getByRole('link', { name: new RegExp(recent.targetLabel) }))
        .toHaveAttribute('href', recent.targetContext?.path ?? '');
      await expect(recentBlock).not.toContainText(/暂无访问记录|No recent visits/);
    } finally {
      await cleanupEngagement(page, recent?.id);
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-006: announcement widget renders active announcement API data', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let announcement: CreatedAnnouncement | undefined;

    try {
      announcement = await createAnnouncementFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        announcementWidgets(),
        'Runtime Announcement Widget Matrix',
      );

      const announcementListResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/announcements') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });
      await expectAnnouncementListContains(
        page,
        announcement.title,
        'announcement list consumed by viewer',
        await announcementListResponse,
      );

      const announcementBlock = await expectRuntimeBlock(
        page,
        'runtime-announcement',
        'smart-announcement',
      );
      await expect(announcementBlock.getByTestId('announcement-widget')).toBeVisible();
      await expect(announcementBlock).toContainText(announcement.title);
      await expect(announcementBlock).toContainText(announcement.content ?? '');
      await expect(announcementBlock).toContainText(/Pinned|置顶/);
      await expect(announcementBlock).toContainText(/Urgent|紧急/);
    } finally {
      await cleanupAnnouncement(page, announcement?.id);
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-007: inbox and calendar widgets render pending inbox API data', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let inboxFixture: CreatedInboxFixture | undefined;

    try {
      inboxFixture = await createInboxFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        inboxCalendarWidgets(),
        'Runtime Inbox Calendar Widget Matrix',
      );

      const inboxResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/inbox') &&
          response.url().includes('status=pending') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });
      await expectInboxListContains(
        page,
        inboxFixture.testRunId,
        'inbox list consumed by viewer',
        await inboxResponse,
      );

      const inboxBlock = await expectRuntimeBlock(page, 'runtime-inbox', 'smart-inbox');
      const expectedInboxTitle = `E2E Approval Request [${inboxFixture.testRunId}-1]`;
      await expect(inboxBlock).toContainText(expectedInboxTitle);
      const inboxRow = inboxBlock.getByRole('row').filter({ hasText: expectedInboxTitle });
      await expect(inboxRow).toBeVisible();
      await expect(inboxRow.getByTestId('inbox-type-badge')).toContainText(/Approval|审批/);

      const calendarBlock = await expectRuntimeBlock(page, 'runtime-calendar', 'smart-calendar');
      await expect(calendarBlock.getByTestId('calendar-widget')).toBeVisible();
      await expect(calendarBlock.getByTestId('calendar-dot-red')).toBeVisible({
        timeout: 10_000,
      });
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-008: CRM workbench widgets render live dynamic CRM data', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let crmFixture: CreatedCrmWorkbenchFixture | undefined;

    try {
      crmFixture = await createCrmWorkbenchFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        crmWorkbenchWidgets(),
        'Runtime CRM Workbench Widget Matrix',
      );

      const pipelineResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/workbench/pipeline') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      const leadResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/dynamic/crm_lead/list') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      const activityResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/dynamic/crm_activity/list') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });
      await expectPipelineIncludesStageAmount(
        page,
        crmFixture,
        'CRM pipeline consumed by viewer',
        await pipelineResponse,
      );
      await expectCrmLeadListContains(page, crmFixture, 'CRM lead list consumed by viewer', await leadResponse);
      await expectCrmActivityListContains(
        page,
        crmFixture,
        'CRM activity list consumed by viewer',
        await activityResponse,
      );

      const pipelineBlock = await expectRuntimeBlock(page, 'runtime-pipeline', 'smart-pipeline');
      await expect(pipelineBlock.getByTestId('pipeline-widget')).toBeVisible();
      const qualificationStage = pipelineBlock.getByTestId('pipeline-stage-qualification');
      await expect(qualificationStage).toBeVisible({ timeout: 10_000 });
      await expect(qualificationStage).toContainText(/Qualification|资质审查/);
      await expect(pipelineBlock).not.toContainText('workbench.pipeline.');

      const leadsBlock = await expectRuntimeBlock(page, 'runtime-leads', 'smart-leads');
      await expect(leadsBlock.getByTestId('leads-widget')).toBeVisible();
      await expect(leadsBlock).toContainText(crmFixture.leadCompany);
      await expect(leadsBlock).toContainText(crmFixture.leadContact);
      await expect(leadsBlock).not.toContainText(/No leads yet|暂无线索|CRM module not installed/);

      const activitiesBlock = await expectRuntimeBlock(
        page,
        'runtime-activities',
        'smart-activities',
      );
      await expect(activitiesBlock.getByTestId('activities-widget')).toBeVisible();
      await expect(activitiesBlock).toContainText(crmFixture.activitySubject);
      await expect(activitiesBlock).not.toContainText(/No recent activities|暂无活动/);
    } finally {
      await cleanupCrmWorkbenchFixture(page, crmFixture);
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-010: CRM workbench widget clicks use canonical dynamic page navigation', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let crmFixture: CreatedCrmWorkbenchFixture | undefined;

    try {
      crmFixture = await createCrmWorkbenchFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        crmWorkbenchWidgets(),
        'Runtime CRM Workbench Navigation Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const pipelineBlock = await expectRuntimeBlock(page, 'runtime-pipeline', 'smart-pipeline');
      const qualificationStage = pipelineBlock.getByTestId('pipeline-stage-qualification');
      await expect(qualificationStage).toBeVisible({ timeout: 10_000 });
      await qualificationStage.click();
      await expect(page).toHaveURL(/\/p\/crm_opportunity\?/);
      const filters = new URL(page.url()).searchParams.get('filters') ?? '';
      expect(filters, 'pipeline stage URL filters').toContain('crm_opp_stage');
      expect(filters, 'pipeline stage URL filters').toContain('qualification');

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      const leadsBlock = await expectRuntimeBlock(page, 'runtime-leads', 'smart-leads');
      const leadRow = leadsBlock.getByTestId(`lead-row-${crmFixture.leadId}`);
      await expect(leadRow).toContainText(crmFixture.leadCompany, { timeout: 10_000 });
      await leadRow.click();
      await expect(page).toHaveURL(new RegExp(`/p/crm_lead/view/${crmFixture.leadId}`));

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      const activitiesBlock = await expectRuntimeBlock(
        page,
        'runtime-activities',
        'smart-activities',
      );
      const activityRow = activitiesBlock.getByTestId(`activity-row-${crmFixture.activityId}`);
      await expect(activityRow).toContainText(crmFixture.activitySubject, { timeout: 10_000 });
      await activityRow.click();
      await expect(page).toHaveURL(new RegExp(`/p/crm_activity/view/${crmFixture.activityId}`));
    } finally {
      await cleanupCrmWorkbenchFixture(page, crmFixture);
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-011: CRM workbench widgets render empty and unavailable states', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        crmWorkbenchWidgets(),
        'Runtime CRM Workbench Empty State Matrix',
      );
      await page.route('**/api/workbench/pipeline**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: { stages: [], totalAmount: 0, totalCount: 0 },
          }),
        });
      });
      await page.route('**/api/dynamic/crm_lead/list**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: { records: [], total: 0 },
          }),
        });
      });
      await page.route('**/api/dynamic/crm_activity/list**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: { records: [], total: 0 },
          }),
        });
      });

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const pipelineBlock = await expectRuntimeBlock(page, 'runtime-pipeline', 'smart-pipeline');
      await expect(pipelineBlock.getByTestId('pipeline-crm-unavailable')).toBeVisible();
      await expect(pipelineBlock).not.toContainText('workbench.pipeline.');

      const leadsBlock = await expectRuntimeBlock(page, 'runtime-leads', 'smart-leads');
      await expect(leadsBlock.getByTestId('leads-empty')).toBeVisible();
      await expect(leadsBlock).not.toContainText('workbench.leads.');

      const activitiesBlock = await expectRuntimeBlock(
        page,
        'runtime-activities',
        'smart-activities',
      );
      await expect(activitiesBlock.getByTestId('activities-empty')).toBeVisible();
      await expect(activitiesBlock).not.toContainText('workbench.activities.');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-012: CRM workbench widgets render stage, lead status, and activity type variants', async ({
    page,
  }) => {
    let dashboard: CreatedDashboard | undefined;
    const now = new Date().toISOString();
    const pipelineStages = [
      { code: 'discovery', label: 'Discovery', count: 5, amount: 500000, color: '#3B82F6' },
      { code: 'qualification', label: 'Qualification', count: 4, amount: 400000, color: '#8B5CF6' },
      { code: 'proposal', label: 'Proposal', count: 3, amount: 300000, color: '#F59E0B' },
      { code: 'negotiation', label: 'Negotiation', count: 2, amount: 200000, color: '#EF4444' },
      { code: 'closed_won', label: 'Closed Won', count: 1, amount: 100000, color: '#10B981' },
      { code: 'closed_lost', label: 'Closed Lost', count: 1, amount: 50000, color: '#6B7280' },
    ];
    const leadVariants: CrmLeadRecord[] = [
      {
        pid: 'lead-new',
        crm_lead_company: 'Variant New Lead',
        crm_lead_contact_name: 'New Contact',
        crm_lead_status: 'new',
        created_at: now,
      },
      {
        pid: 'lead-following',
        crm_lead_company: 'Variant Follow Lead',
        crm_lead_contact_name: 'Follow Contact',
        crm_lead_status: 'following_up',
        created_at: now,
      },
      {
        pid: 'lead-converted',
        crm_lead_company: 'Variant Converted Lead',
        crm_lead_contact_name: 'Converted Contact',
        crm_lead_status: 'converted',
        created_at: now,
      },
      {
        pid: 'lead-disqualified',
        crm_lead_company: 'Variant Disqualified Lead',
        crm_lead_contact_name: 'Disqualified Contact',
        crm_lead_status: 'disqualified',
        created_at: now,
      },
    ];
    const activityVariants: CrmActivityRecord[] = [
      {
        pid: 'activity-call',
        crm_act_type: 'call',
        crm_act_subject: 'Variant Call Activity',
        created_at: now,
      },
      {
        pid: 'activity-meeting',
        crm_act_type: 'meeting',
        crm_act_subject: 'Variant Meeting Activity',
        created_at: now,
      },
      {
        pid: 'activity-email',
        crm_act_type: 'email',
        crm_act_subject: 'Variant Email Activity',
        created_at: now,
      },
      {
        pid: 'activity-note',
        crm_act_type: 'note',
        crm_act_subject: 'Variant Note Activity',
        created_at: now,
      },
    ];

    try {
      dashboard = await createPublishedDashboard(
        page,
        crmWorkbenchWidgets(),
        'Runtime CRM Workbench Variant Matrix',
      );
      await page.route('**/api/workbench/pipeline**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: {
              stages: pipelineStages,
              totalAmount: pipelineStages.reduce((sum, stage) => sum + stage.amount, 0),
              totalCount: pipelineStages.reduce((sum, stage) => sum + stage.count, 0),
            },
          }),
        });
      });
      await page.route('**/api/dynamic/crm_lead/list**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: { records: leadVariants, total: leadVariants.length },
          }),
        });
      });
      await page.route('**/api/dynamic/crm_activity/list**', async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: '0',
            data: { records: activityVariants, total: activityVariants.length },
          }),
        });
      });

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const pipelineBlock = await expectRuntimeBlock(page, 'runtime-pipeline', 'smart-pipeline');
      for (const stage of pipelineStages) {
        const stageRow = pipelineBlock.getByTestId(`pipeline-stage-${stage.code}`);
        await expect(stageRow).toBeVisible({ timeout: 10_000 });
        await expect(stageRow).toContainText(stage.label);
      }
      await expect(pipelineBlock).not.toContainText('workbench.pipeline.');

      const leadsBlock = await expectRuntimeBlock(page, 'runtime-leads', 'smart-leads');
      for (const lead of leadVariants) {
        const row = leadsBlock.getByTestId(`lead-row-${lead.pid}`);
        await expect(row).toContainText(lead.crm_lead_company ?? '');
        await expect(row.getByTestId(`lead-status-${lead.crm_lead_status}`)).toBeVisible();
      }
      await expect(leadsBlock).not.toContainText('workbench.leads.');

      const activitiesBlock = await expectRuntimeBlock(
        page,
        'runtime-activities',
        'smart-activities',
      );
      for (const activity of activityVariants) {
        const row = activitiesBlock.getByTestId(`activity-row-${activity.pid}`);
        await expect(row).toContainText(activity.crm_act_subject ?? '');
        await expect(row.getByTestId(`activity-type-${activity.crm_act_type}`)).toBeVisible();
      }
      await expect(activitiesBlock).not.toContainText('workbench.activities.');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-013: CRM workbench widgets distinguish permission denial from empty CRM data', async ({
    page,
  }) => {
    let dashboard: CreatedDashboard | undefined;
    const forbiddenBody = {
      code: '403',
      message: 'Access forbidden',
      data: null,
    };

    try {
      dashboard = await createPublishedDashboard(
        page,
        crmWorkbenchWidgets(),
        'Runtime CRM Workbench Permission Matrix',
      );
      await page.route('**/api/workbench/pipeline**', async (route) => {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify(forbiddenBody),
        });
      });
      await page.route('**/api/dynamic/crm_lead/list**', async (route) => {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify(forbiddenBody),
        });
      });
      await page.route('**/api/dynamic/crm_activity/list**', async (route) => {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify(forbiddenBody),
        });
      });

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const pipelineBlock = await expectRuntimeBlock(page, 'runtime-pipeline', 'smart-pipeline');
      await expect(pipelineBlock.getByTestId('pipeline-permission-denied')).toBeVisible();
      await expect(pipelineBlock).not.toContainText('No opportunity data');
      await expect(pipelineBlock).not.toContainText('CRM module not installed');

      const leadsBlock = await expectRuntimeBlock(page, 'runtime-leads', 'smart-leads');
      await expect(leadsBlock.getByTestId('leads-permission-denied')).toBeVisible();
      await expect(leadsBlock).not.toContainText('No leads yet');
      await expect(leadsBlock).not.toContainText('CRM module not installed');

      const activitiesBlock = await expectRuntimeBlock(
        page,
        'runtime-activities',
        'smart-activities',
      );
      await expect(activitiesBlock.getByTestId('activities-permission-denied')).toBeVisible();
      await expect(activitiesBlock).not.toContainText('No recent activities');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-009: BPM workbench widgets render live BPM runtime data', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    let bpmFixture: CreatedBpmWorkbenchFixture | undefined;

    try {
      bpmFixture = await createBpmWorkbenchFixture(page);
      dashboard = await createPublishedDashboard(
        page,
        bpmWorkbenchWidgets(),
        'Runtime BPM Workbench Widget Matrix',
      );

      const workbenchResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/bpm/workbench') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      const statsResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/workbench/bpm-stats') &&
          response.request().method() === 'GET' &&
          response.status() === 200,
        { timeout: 10_000 },
      );
      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });
      await expectBpmWorkbenchContainsProcess(
        page,
        bpmFixture,
        'BPM workbench consumed by viewer',
        await workbenchResponse,
      );
      await expectBpmStatsIncludesRunning(page, 'BPM stats consumed by viewer', await statsResponse);

      const myProcessBlock = await expectRuntimeBlock(
        page,
        'runtime-my-process',
        'smart-my-process',
      );
      await expect(myProcessBlock.getByTestId('my-process-widget')).toBeVisible();
      await expect(myProcessBlock).toContainText(bpmFixture.businessKey);
      await expect(myProcessBlock).not.toContainText(/No processes started|暂无流程/);

      const runningFilter = myProcessBlock.getByRole('button', { name: /Running|运行中/ });
      await runningFilter.click();
      await expect(myProcessBlock).toContainText(bpmFixture.businessKey);

      const processRow = myProcessBlock.getByTestId(`my-process-row-${bpmFixture.processInstanceId}`);
      await processRow.click();
      await expect(page).toHaveURL(
        new RegExp(`/bpm/process-status\\?processInstanceId=${bpmFixture.processInstanceId}`),
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      const statsBlock = await expectRuntimeBlock(
        page,
        'runtime-process-stats',
        'smart-process-stats',
      );
      await expect(statsBlock.getByTestId('process-stats-widget')).toBeVisible();
      await expect(statsBlock.getByTestId('process-stats-running-count')).not.toHaveText('0', {
        timeout: 10_000,
      });
      await expect(statsBlock).toContainText(/Completion Rate|完成率/);
    } finally {
      await cleanupBpmWorkbenchFixture(page, bpmFixture);
      await cleanupDashboard(page, dashboard?.pid);
    }
  });
});
