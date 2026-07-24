import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { RuntimeExecutionServices } from '../runtime/runtimeExecution';
import type { PageSchemaV3, ModelFieldDefinition } from '../types';

// Isolate the WYSIWYG wiring from the real platform field renderer internals
// (ComponentLoader / smart controls / data sources). We only assert that the
// designer feeds the correct FieldConfig into the platform renderer.
vi.mock('~/framework/meta/rendering/ControlledFieldRenderer', () => ({
  ControlledFieldRenderer: ({ field }: { field: { field: string; label: unknown; component?: string } }) => (
    <div
      data-testid={`controlled-field-${field.field}`}
      data-component={field.component ?? ''}
      data-label={typeof field.label === 'string' ? field.label : JSON.stringify(field.label)}
    >
      controlled-field
    </div>
  ),
}));

describe('RecursiveBlockRenderer', () => {
  it('renders a PageSchema V3 composite page directly', () => {
    render(<RecursiveBlockRenderer schema={samplePageSchemaV3} />);

    expect(screen.getByTestId('runtime-page-customer_workspace')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-block-form_customer')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-block-list_customer')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-block-dashboard_sales')).toBeInTheDocument();
  });

  const wysiwygSchema = (): PageSchemaV3 => ({
    schemaVersion: 3,
    kind: 'form',
    id: 'wysiwyg_page',
    modelCode: 'demo_model',
    blocks: [
      {
        id: 'form_root',
        blockType: 'form',
        blocks: [
          {
            id: 'section_basic',
            blockType: 'form-section',
            blocks: [{ id: 'field_color', blockType: 'field', field: 'demo_color' }],
          },
        ],
      },
    ],
  });

  const demoModelFields: ModelFieldDefinition[] = [
    {
      modelCode: 'demo_model',
      code: 'demo_color',
      label: '颜色标记',
      type: 'string',
      component: 'colorpicker',
    },
  ];

  it('renders the real platform control for field blocks when model metadata is supplied (WYSIWYG)', () => {
    render(<RecursiveBlockRenderer schema={wysiwygSchema()} modelFields={demoModelFields} />);

    const wrapper = screen.getByTestId('runtime-field-field_color');
    expect(wrapper).toHaveAttribute('data-wysiwyg', 'platform');
    expect(wrapper).toHaveAttribute('data-field-component', 'colorpicker');

    // The platform renderer receives the resolved display label + real component,
    // not the raw field code or a collapsed generic input.
    const control = screen.getByTestId('controlled-field-demo_color');
    expect(control).toHaveAttribute('data-component', 'colorpicker');
    expect(control).toHaveAttribute('data-label', '颜色标记');
  });

  it('applies the dataType default control when the field has no explicit renderComponent', () => {
    const schema: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'form',
      id: 'wysiwyg_dt_page',
      modelCode: 'demo_model',
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [{ id: 'field_date', blockType: 'field', field: 'demo_date' }],
        },
      ],
    };
    const modelFields: ModelFieldDefinition[] = [
      { modelCode: 'demo_model', code: 'demo_date', label: '开始日期', type: 'date' },
    ];
    render(<RecursiveBlockRenderer schema={schema} modelFields={modelFields} />);

    // integer/decimal -> SmartNumberInput, date/datetime -> SmartDatePicker, etc.,
    // mirroring the live form's DATA_TYPE_TO_COMPONENT map.
    const control = screen.getByTestId('controlled-field-demo_date');
    expect(control).toHaveAttribute('data-component', 'SmartDatePicker');
  });

  it('falls back to the representative preview when model metadata is absent (backward compatible)', () => {
    render(<RecursiveBlockRenderer schema={wysiwygSchema()} />);

    const wrapper = screen.getByTestId('runtime-field-field_color');
    expect(wrapper).not.toHaveAttribute('data-wysiwyg');
    expect(screen.queryByTestId('controlled-field-demo_color')).not.toBeInTheDocument();
  });

  it('keeps model-backed picker fields on the designer picker so the authored option source still runs', async () => {
    // The designer's `picker` is a data-source component: options come from
    // `pickerDataSource`/`pickerSource` through runtimeServices.loadPickerOptions
    // (/api/query-builder/execute), including server-side search. A platform FieldConfig
    // cannot express that, so a picker must not be handed to the platform control — which
    // would drop the authored source (and the platform registry has no `picker` at all).
    const loadPickerOptions = vi
      .fn()
      .mockResolvedValue([{ label: 'Alice', value: 'alice' }]);
    const schema: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'form',
      id: 'wysiwyg_picker_page',
      modelCode: 'demo_model',
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [
            {
              id: 'field_owner',
              blockType: 'field',
              field: 'demo_owner',
              props: {
                label: 'Owner',
                component: 'picker',
                pickerDataSource: 'model',
                pickerSource: 'user',
                valueField: 'pid',
                displayField: 'displayName',
              },
            },
          ],
        },
      ],
    };
    const modelFields: ModelFieldDefinition[] = [
      {
        modelCode: 'demo_model',
        code: 'demo_owner',
        label: '负责人',
        type: 'relation',
        refTarget: { modelCode: 'user', valueField: 'pid', displayField: 'displayName' },
      },
    ];

    render(
      <RecursiveBlockRenderer
        schema={schema}
        modelFields={modelFields}
        runtimeServices={{ loadPickerOptions } as unknown as RuntimeExecutionServices}
      />,
    );

    const picker = await screen.findByTestId('runtime-picker-field_owner');
    await waitFor(() => expect(picker).toHaveTextContent('Alice'));
    expect(screen.getByTestId('runtime-picker-meta-field_owner')).toHaveTextContent(
      'model / user / displayName / pid',
    );
    expect(loadPickerOptions).toHaveBeenCalled();
    // Not routed through the platform control (which has no `picker`).
    expect(screen.getByTestId('runtime-field-field_owner')).not.toHaveAttribute('data-wysiwyg');
    expect(screen.queryByTestId('controlled-field-demo_owner')).not.toBeInTheDocument();
  });

  it('renders fields, columns, actions, and dashboard widgets from recursive blocks', () => {
    render(<RecursiveBlockRenderer schema={samplePageSchemaV3} />);

    expect(screen.getByTestId('runtime-field-field_customer_name')).toHaveTextContent(
      'Customer name',
    );
    expect(screen.getByTestId('runtime-column-column_title')).toHaveTextContent('Title');
    expect(screen.getByTestId('runtime-action-action_submit')).toHaveTextContent('Submit');
    expect(screen.getByTestId('runtime-widget-widget_revenue')).toHaveTextContent('Revenue');
  });

  it('filters list table rows from filter-field controls', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'filtered_list',
          blocks: [
            {
              id: 'list_root',
              blockType: 'list',
              blocks: [
                {
                  id: 'list_filters',
                  blockType: 'filter-bar',
                  blocks: [
                    {
                      id: 'filter_name',
                      blockType: 'filter-field',
                      field: 'name',
                      props: { label: 'Name', component: 'input', operator: 'contains' },
                    },
                  ],
                },
                {
                  id: 'list_table',
                  blockType: 'table',
                  props: {
                    rows: [
                      { pid: 'row_alpha', name: 'Alpha mission' },
                      { pid: 'row_beta', name: 'Beta mission' },
                    ],
                  },
                  blocks: [
                    {
                      id: 'column_name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const filter = screen.getByTestId('runtime-filter-input-filter_name');
    const table = screen.getByTestId('runtime-table-list_table');
    expect(table).toHaveTextContent('Alpha mission');
    expect(table).toHaveTextContent('Beta mission');

    fireEvent.change(filter, { target: { value: 'Alpha' } });

    expect(table).toHaveTextContent('Alpha mission');
    expect(table).not.toHaveTextContent('Beta mission');
  });

  it('renders relation picker filters and applies selected values to list rows', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadPickerOptions: vi.fn().mockResolvedValue([
        { label: 'Alice', value: 'alice' },
        { label: 'Bob', value: 'bob' },
      ]),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'relation_filtered_list',
          blocks: [
            {
              id: 'list_root',
              blockType: 'list',
              blocks: [
                {
                  id: 'list_filters',
                  blockType: 'filter-bar',
                  blocks: [
                    {
                      id: 'filter_owner',
                      blockType: 'filter-field',
                      field: 'owner',
                      props: {
                        label: 'Owner',
                        component: 'picker',
                        pickerDataSource: 'model',
                        pickerSource: 'user',
                        valueField: 'pid',
                        displayField: 'displayName',
                        operator: 'equals',
                      },
                    },
                  ],
                },
                {
                  id: 'list_table',
                  blockType: 'table',
                  props: {
                    rows: [
                      { pid: 'row_alpha', name: 'Alpha mission', owner: 'alice' },
                      { pid: 'row_beta', name: 'Beta mission', owner: 'bob' },
                    ],
                  },
                  blocks: [
                    {
                      id: 'column_name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const picker = await screen.findByTestId('runtime-picker-filter_owner');
    const table = screen.getByTestId('runtime-table-list_table');
    await waitFor(() => expect(picker).toHaveTextContent('Alice'));
    expect(table).toHaveTextContent('Alpha mission');
    expect(table).toHaveTextContent('Beta mission');

    fireEvent.change(picker, { target: { value: 'alice' } });

    expect(table).toHaveTextContent('Alpha mission');
    expect(table).not.toHaveTextContent('Beta mission');
    expect(runtimeServices.loadPickerOptions).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'filter_owner' }),
      expect.objectContaining({
        pageId: 'relation_filtered_list',
        blockPath: ['list_root', 'list_filters', 'filter_owner'],
      }),
    );
  });

  it('gates form fields and table columns by permission code', () => {
    render(
      <RecursiveBlockRenderer
        permissionEvaluator={(permissionCode) => permissionCode === 'customer.public.read'}
        schema={{
          schemaVersion: 3,
          kind: 'composite',
          id: 'permission_page',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'field_public',
                  blockType: 'field',
                  field: 'publicName',
                  props: {
                    label: 'Public name',
                    component: 'input',
                    permissionCode: 'customer.public.read',
                  },
                },
                {
                  id: 'field_secret',
                  blockType: 'field',
                  field: 'secretName',
                  props: {
                    label: 'Secret name',
                    component: 'input',
                    permissionCode: 'customer.secret.read',
                  },
                },
              ],
            },
            {
              id: 'table_root',
              blockType: 'table',
              props: {
                rows: [{ publicName: 'Visible customer', secretName: 'Private customer' }],
              },
              blocks: [
                {
                  id: 'column_public',
                  blockType: 'column',
                  field: 'publicName',
                  props: {
                    label: 'Public',
                    permissionCode: 'customer.public.read',
                  },
                },
                {
                  id: 'column_secret',
                  blockType: 'column',
                  field: 'secretName',
                  props: {
                    label: 'Secret',
                    permissionCode: 'customer.secret.read',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-input-field_public')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-field-permission-field_secret')).toHaveTextContent(
      'Requires permission: customer.secret.read',
    );
    expect(screen.queryByTestId('runtime-input-field_secret')).not.toBeInTheDocument();

    expect(screen.getByTestId('runtime-column-column_public')).toHaveTextContent('Public');
    expect(screen.queryByTestId('runtime-column-column_secret')).not.toBeInTheDocument();
    expect(screen.getByTestId('runtime-table-table_root')).toHaveTextContent('Visible customer');
    expect(screen.getByTestId('runtime-table-table_root')).not.toHaveTextContent(
      'Private customer',
    );
    expect(screen.queryByTestId('runtime-table-cell-table_root-0-secretName')).not.toBeInTheDocument();
  });

  it('renders dedicated helper blocks for AI fill, BPM, timeline, and field history', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'detail',
          id: 'helper_detail',
          blocks: [
            {
              id: 'detail_root',
              blockType: 'detail',
              blocks: [
                {
                  id: 'helper_ai',
                  blockType: 'ai-fill-banner',
                  title: 'AI suggestions',
                  props: {
                    description: 'Generated from current customer context',
                    feedback: 'Applied to form',
                    suggestedFields: [
                      { field: 'priority', label: 'Priority', value: 'High' },
                    ],
                  },
                },
                {
                  id: 'helper_bpm',
                  blockType: 'bpm-panel',
                  title: 'Approval',
                  props: {
                    status: 'pending',
                    assignee: 'Ada',
                    dueAt: '2026-05-21',
                    actions: [{ label: 'Approve', actionType: 'approve' }],
                  },
                },
                {
                  id: 'helper_timeline',
                  blockType: 'activity-timeline',
                  title: 'Activity',
                  props: {
                    items: [
                      {
                        actor: 'Grace',
                        action: 'Updated amount',
                        time: '2026-05-20 10:00',
                        description: 'Changed forecast value',
                      },
                    ],
                  },
                },
                {
                  id: 'helper_history',
                  blockType: 'field-history',
                  title: 'Field history',
                  props: {
                    entries: [
                      {
                        field: 'status',
                        from: 'draft',
                        to: 'pending',
                        changedBy: 'Lin',
                      },
                    ],
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-ai-fill-banner-helper_ai')).toHaveTextContent(
      'Generated from current customer context',
    );
    expect(screen.getByTestId('runtime-ai-fill-field-helper_ai-0')).toHaveTextContent('High');
    fireEvent.click(screen.getByTestId('runtime-ai-fill-apply-helper_ai'));
    expect(screen.getByTestId('runtime-ai-fill-status-helper_ai')).toHaveTextContent(
      'Applied to form',
    );
    expect(screen.getByTestId('runtime-bpm-status-helper_bpm')).toHaveTextContent('pending');
    expect(screen.getByTestId('runtime-bpm-assignee-helper_bpm')).toHaveTextContent('Ada');
    expect(screen.getByTestId('runtime-bpm-action-helper_bpm-0')).toHaveTextContent('Approve');
    expect(screen.getByTestId('runtime-activity-item-helper_timeline-0')).toHaveTextContent(
      'Updated amount',
    );
    expect(screen.getByTestId('runtime-field-history-entry-helper_history-0')).toHaveTextContent(
      'pending',
    );
  });

  it('applies AI fill suggestions to fields in the current runtime form', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'ai_fill_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'helper_ai',
                  blockType: 'ai-fill-banner',
                  title: 'AI suggestions',
                  props: {
                    feedback: 'AI values copied',
                    suggestedFields: [
                      { field: 'name', label: 'Name', value: 'Ada generated' },
                    ],
                  },
                },
                {
                  id: 'field_name',
                  blockType: 'field',
                  field: 'name',
                  props: { label: 'Name', component: 'input' },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-input-field_name')).toHaveValue('');

    fireEvent.click(screen.getByTestId('runtime-ai-fill-apply-helper_ai'));

    expect(screen.getByTestId('runtime-input-field_name')).toHaveValue('Ada generated');
    expect(screen.getByTestId('runtime-ai-fill-status-helper_ai')).toHaveTextContent(
      'AI values copied',
    );
  });

  it('applies runtime AI fill suggestions loaded from services to form fields', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadHelperBlockData: vi.fn(async () => ({
        source: 'named-query',
        suggestedFields: [
          { field: 'page_key', label: 'Page key', value: 'live-generated-page-key' },
        ],
        feedback: 'Live named-query values copied',
      })),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'ai_live_fill_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'helper_ai_live',
                  blockType: 'ai-fill-banner',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_ai_suggestions',
                  },
                },
                {
                  id: 'field_page_key',
                  blockType: 'field',
                  field: 'page_key',
                  props: { label: 'Page key', component: 'input' },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(await screen.findByTestId('runtime-ai-fill-field-helper_ai_live-0')).toHaveTextContent(
      'live-generated-page-key',
    );
    expect(screen.getByTestId('runtime-input-field_page_key')).toHaveValue('');

    fireEvent.click(screen.getByTestId('runtime-ai-fill-apply-helper_ai_live'));

    expect(screen.getByTestId('runtime-input-field_page_key')).toHaveValue(
      'live-generated-page-key',
    );
    expect(screen.getByTestId('runtime-ai-fill-status-helper_ai_live')).toHaveTextContent(
      'Live named-query values copied',
    );
    await waitFor(() => {
      expect(runtimeServices.loadHelperBlockData).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'helper_ai_live' }),
        expect.objectContaining({
          pageId: 'ai_live_fill_form',
          pageKind: 'form',
          blockId: 'helper_ai_live',
          blockType: 'ai-fill-banner',
          blockPath: ['form_root', 'helper_ai_live'],
        }),
      );
    });
  });

  it('loads helper block data through injected runtime services', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadHelperBlockData: vi.fn(async (block) => {
        if (block.blockType === 'ai-fill-banner') {
          return {
            source: 'named-query',
            suggestedFields: [{ field: 'summary', label: 'Summary', value: 'Generated summary' }],
            feedback: 'Live suggestions applied',
          };
        }
        if (block.blockType === 'bpm-panel') {
          return {
            source: 'named-query',
            status: 'approved',
            assignee: 'Runtime approver',
            actions: [{ label: 'Archive', actionType: 'archive' }],
          };
        }
        if (block.blockType === 'activity-timeline') {
          return {
            source: 'named-query',
            items: [
              {
                actor: 'Runtime user',
                action: 'Loaded activity',
                time: '2026-05-20 12:00',
                description: 'Loaded through dataSource',
              },
            ],
          };
        }
        if (block.blockType === 'field-history') {
          return {
            source: 'named-query',
            entries: [
              {
                field: 'status',
                from: 'draft',
                to: 'approved',
                changedBy: 'Runtime reviewer',
              },
            ],
          };
        }
        return null;
      }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'detail',
          id: 'live_helper_detail',
          blocks: [
            {
              id: 'detail_root',
              blockType: 'detail',
              blocks: [
                {
                  id: 'helper_ai_live',
                  blockType: 'ai-fill-banner',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_ai_suggestions',
                  },
                },
                {
                  id: 'helper_bpm_live',
                  blockType: 'bpm-panel',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_bpm_state',
                  },
                },
                {
                  id: 'helper_timeline_live',
                  blockType: 'activity-timeline',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_activity',
                  },
                },
                {
                  id: 'helper_history_live',
                  blockType: 'field-history',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_history',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(await screen.findByTestId('runtime-ai-fill-field-helper_ai_live-0')).toHaveTextContent(
      'Generated summary',
    );
    fireEvent.click(screen.getByTestId('runtime-ai-fill-apply-helper_ai_live'));
    expect(screen.getByTestId('runtime-ai-fill-status-helper_ai_live')).toHaveTextContent(
      'Live suggestions applied',
    );
    expect(screen.getByTestId('runtime-bpm-status-helper_bpm_live')).toHaveTextContent('approved');
    expect(screen.getByTestId('runtime-bpm-assignee-helper_bpm_live')).toHaveTextContent(
      'Runtime approver',
    );
    expect(screen.getByTestId('runtime-bpm-action-helper_bpm_live-0')).toHaveTextContent('Archive');
    expect(screen.getByTestId('runtime-activity-item-helper_timeline_live-0')).toHaveTextContent(
      'Loaded activity',
    );
    expect(screen.getByTestId('runtime-field-history-entry-helper_history_live-0')).toHaveTextContent(
      'approved',
    );
    expect(screen.getByTestId('runtime-helper-source-helper_history_live')).toHaveTextContent(
      'named-query',
    );
    await waitFor(() => {
      expect(runtimeServices.loadHelperBlockData).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'helper_ai_live' }),
        expect.objectContaining({
          pageId: 'live_helper_detail',
          pageKind: 'detail',
          blockId: 'helper_ai_live',
          blockType: 'ai-fill-banner',
          blockPath: ['detail_root', 'helper_ai_live'],
        }),
      );
    });
  });

  it('blocks helper live data loading when the required permission is missing', () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadHelperBlockData: vi.fn(async () => ({
        source: 'named-query',
        suggestedFields: [{ field: 'summary', label: 'Summary', value: 'Should stay hidden' }],
      })),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'detail',
          id: 'helper_permission_denied_detail',
          blocks: [
            {
              id: 'detail_root',
              blockType: 'detail',
              blocks: [
                {
                  id: 'helper_ai_denied',
                  blockType: 'ai-fill-banner',
                  props: {
                    permissionCode: 'meta.helper.read',
                  },
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_ai_denied',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    const permissionStatus = screen.getByTestId('runtime-helper-permission-helper_ai_denied');
    expect(permissionStatus).toHaveTextContent('Requires permission: meta.helper.read');
    expect(permissionStatus).toHaveAttribute('data-permission-code', 'meta.helper.read');
    expect(permissionStatus).toHaveAttribute('data-permission-allowed', 'false');
    expect(runtimeServices.loadHelperBlockData).not.toHaveBeenCalled();
    expect(screen.queryByTestId('runtime-ai-fill-field-helper_ai_denied-0')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runtime-helper-error-helper_ai_denied')).not.toBeInTheDocument();
  });

  it('loads permission-protected helper live data when the host permission evaluator allows it', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadHelperBlockData: vi.fn(async () => ({
        source: 'named-query',
        suggestedFields: [{ field: 'summary', label: 'Summary', value: 'Allowed summary' }],
      })),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        permissionEvaluator={(permissionCode) => permissionCode === 'meta.helper.read'}
        schema={{
          schemaVersion: 3,
          kind: 'detail',
          id: 'helper_permission_allowed_detail',
          blocks: [
            {
              id: 'detail_root',
              blockType: 'detail',
              blocks: [
                {
                  id: 'helper_ai_allowed',
                  blockType: 'ai-fill-banner',
                  props: {
                    permissionCode: 'meta.helper.read',
                  },
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_ai_allowed',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(await screen.findByTestId('runtime-ai-fill-field-helper_ai_allowed-0')).toHaveTextContent(
      'Allowed summary',
    );
    const permissionStatus = screen.getByTestId('runtime-helper-permission-helper_ai_allowed');
    expect(permissionStatus).toHaveTextContent('Permission: meta.helper.read');
    expect(permissionStatus).toHaveAttribute('data-permission-code', 'meta.helper.read');
    expect(permissionStatus).toHaveAttribute('data-permission-allowed', 'true');
    expect(screen.getByTestId('runtime-helper-source-helper_ai_allowed')).toHaveTextContent(
      'named-query',
    );
    await waitFor(() => {
      expect(runtimeServices.loadHelperBlockData).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'helper_ai_allowed' }),
        expect.objectContaining({
          pageId: 'helper_permission_allowed_detail',
          blockId: 'helper_ai_allowed',
          blockType: 'ai-fill-banner',
        }),
      );
    });
  });

  it('renders helper empty and error states from live data sources', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadHelperBlockData: vi.fn(async (block) => {
        if (block.id === 'helper_ai_error') {
          throw new Error('Helper named query failed');
        }
        return {
          source: 'named-query',
          emptyText: `${block.id} has no live data`,
          suggestedFields: [],
          actions: [],
          items: [],
          entries: [],
        };
      }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'detail',
          id: 'helper_empty_detail',
          blocks: [
            {
              id: 'detail_root',
              blockType: 'detail',
              blocks: [
                {
                  id: 'helper_ai_empty',
                  blockType: 'ai-fill-banner',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_empty_ai',
                  },
                },
                {
                  id: 'helper_bpm_empty',
                  blockType: 'bpm-panel',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_empty_bpm',
                  },
                },
                {
                  id: 'helper_timeline_empty',
                  blockType: 'activity-timeline',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_empty_timeline',
                  },
                },
                {
                  id: 'helper_history_empty',
                  blockType: 'field-history',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_empty_history',
                  },
                },
                {
                  id: 'helper_ai_error',
                  blockType: 'ai-fill-banner',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_missing_helper',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(await screen.findByTestId('runtime-ai-fill-empty-helper_ai_empty')).toHaveTextContent(
      'helper_ai_empty has no live data',
    );
    expect(screen.getByTestId('runtime-bpm-empty-helper_bpm_empty')).toHaveTextContent(
      'helper_bpm_empty has no live data',
    );
    expect(screen.getByTestId('runtime-activity-empty-helper_timeline_empty')).toHaveTextContent(
      'helper_timeline_empty has no live data',
    );
    expect(screen.getByTestId('runtime-field-history-empty-helper_history_empty')).toHaveTextContent(
      'helper_history_empty has no live data',
    );
    expect(await screen.findByTestId('runtime-helper-error-helper_ai_error')).toHaveTextContent(
      'Helper named query failed',
    );
  });

  it('renders configured form field components and keeps their values in form context', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'component_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_components',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_title',
                      blockType: 'field',
                      field: 'title',
                      props: { label: 'Title', component: 'input', placeholder: 'Enter title' },
                    },
                    {
                      id: 'field_notes',
                      blockType: 'field',
                      field: 'notes',
                      props: { label: 'Notes', component: 'textarea' },
                    },
                    {
                      id: 'field_status',
                      blockType: 'field',
                      field: 'status',
                      props: {
                        label: 'Status',
                        component: 'select',
                        options: [
                          { label: 'Open', value: 'open' },
                          { label: 'Closed', value: 'closed' },
                        ],
                      },
                    },
                    {
                      id: 'field_active',
                      blockType: 'field',
                      field: 'active',
                      props: { label: 'Active', component: 'checkbox' },
                    },
                    {
                      id: 'field_due',
                      blockType: 'field',
                      field: 'due',
                      props: { label: 'Due date', component: 'date' },
                    },
                    {
                      id: 'field_amount',
                      blockType: 'field',
                      field: 'amount',
                      props: { label: 'Amount', component: 'number' },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const titleInput = screen.getByTestId('runtime-input-field_title');
    const notesInput = screen.getByTestId('runtime-textarea-field_notes');
    const statusInput = screen.getByTestId('runtime-select-field_status');
    const activeInput = screen.getByTestId('runtime-checkbox-field_active');
    const dueInput = screen.getByTestId('runtime-input-field_due');
    const amountInput = screen.getByTestId('runtime-input-field_amount');

    expect(titleInput).toHaveAttribute('placeholder', 'Enter title');
    expect(dueInput).toHaveAttribute('type', 'date');
    expect(amountInput).toHaveAttribute('type', 'number');
    expect(statusInput).toHaveTextContent('Open');

    fireEvent.change(titleInput, { target: { value: 'Design review' } });
    fireEvent.change(notesInput, { target: { value: 'Use grid layout' } });
    fireEvent.change(statusInput, { target: { value: 'closed' } });
    fireEvent.click(activeInput);
    fireEvent.change(dueInput, { target: { value: '2026-05-20' } });
    fireEvent.change(amountInput, { target: { value: '42' } });

    expect(titleInput).toHaveValue('Design review');
    expect(notesInput).toHaveValue('Use grid layout');
    expect(statusInput).toHaveValue('closed');
    expect(activeInput).toBeChecked();
    expect(dueInput).toHaveValue('2026-05-20');
    expect(amountInput).toHaveValue(42);
  });

  it('renders radio form controls from configured options', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'radio_component_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_components',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_priority',
                      blockType: 'field',
                      field: 'priority',
                      props: {
                        label: 'Priority',
                        component: 'radio',
                        options: [
                          { label: 'Low', value: 'low' },
                          { label: 'High', value: 'high' },
                        ],
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const radioGroup = screen.getByTestId('runtime-radio-field_priority');
    expect(radioGroup).toHaveTextContent('Low');
    expect(radioGroup).toHaveTextContent('High');

    const highRadio = screen.getByTestId('runtime-radio-field_priority-high');
    fireEvent.click(highRadio);

    expect(highRadio).toBeChecked();
  });

  it('renders picker and rich text form controls from field component configuration', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'advanced_component_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_advanced_components',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_owner',
                      blockType: 'field',
                      field: 'owner',
                      props: {
                        label: 'Owner',
                        component: 'picker',
                        placeholder: 'Select owner',
                        options: [
                          { label: 'Alice', value: 'alice' },
                          { label: 'Bob', value: 'bob' },
                        ],
                      },
                    },
                    {
                      id: 'field_description',
                      blockType: 'field',
                      field: 'description',
                      props: {
                        label: 'Description',
                        component: 'rich-text',
                        placeholder: 'Write formatted notes',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const picker = screen.getByTestId('runtime-picker-field_owner');
    const richText = screen.getByTestId('runtime-rich-text-field_description');

    expect(picker).toHaveTextContent('Alice');
    expect(richText).toHaveAttribute('placeholder', 'Write formatted notes');

    fireEvent.change(picker, { target: { value: 'bob' } });
    fireEvent.change(richText, { target: { value: 'Formatted preview value' } });

    expect(picker).toHaveValue('bob');
    expect(richText).toHaveValue('Formatted preview value');
  });

  it('loads picker options through injected runtime services', async () => {
    const loadPickerOptions = vi.fn().mockResolvedValue([
      { label: 'Customer workspace', value: 'customer_workspace' },
      { label: 'System overview', value: 'system_overview' },
    ]);
    const runtimeServices = { loadPickerOptions } as unknown as RuntimeExecutionServices;

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'dynamic_picker_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_picker',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_page',
                      blockType: 'field',
                      field: 'page_key',
                      props: {
                        label: 'Page',
                        component: 'picker',
                        pickerDataSource: 'model',
                        pickerSource: 'page_schema',
                        valueField: 'page_key',
                        displayField: 'name',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const picker = await screen.findByTestId('runtime-picker-field_page');
    await waitFor(() => expect(picker).toHaveTextContent('System overview'));
    fireEvent.change(picker, { target: { value: 'system_overview' } });

    expect(picker).toHaveValue('system_overview');
    expect(screen.getByTestId('runtime-picker-meta-field_page')).toHaveTextContent(
      'model / page_schema / name / page_key',
    );
    expect(loadPickerOptions).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'field_page' }),
      expect.objectContaining({
        source: 'unified-designer-runtime-preview',
        pageId: 'dynamic_picker_form',
        pageKind: 'form',
        schemaVersion: 3,
        blockId: 'field_page',
        blockType: 'field',
        blockPath: ['form_root', 'section_picker', 'field_page'],
      }),
    );
  });

  it('shows named-query picker source metadata from pickerQueryCode', async () => {
    const loadPickerOptions = vi.fn().mockResolvedValue([
      { label: 'Customer workspace', value: 'customer_workspace' },
    ]);
    const runtimeServices = { loadPickerOptions } as unknown as RuntimeExecutionServices;

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'named_query_picker_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_picker',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_page',
                      blockType: 'field',
                      field: 'page_key',
                      props: {
                        label: 'Page',
                        component: 'picker',
                        pickerDataSource: 'named-query',
                        pickerQueryCode: 'udw_page_options',
                        valueField: 'page_key',
                        displayField: 'name',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const picker = await screen.findByTestId('runtime-picker-field_page');
    await waitFor(() => expect(picker).toHaveTextContent('Customer workspace'));

    expect(screen.getByTestId('runtime-picker-meta-field_page')).toHaveTextContent(
      'named-query / udw_page_options / name / page_key',
    );
  });

  it('passes searchable picker input into runtime option loading context', async () => {
    const loadPickerOptions = vi
      .fn()
      .mockResolvedValueOnce([{ label: 'Customer workspace', value: 'customer_workspace' }])
      .mockResolvedValueOnce([{ label: 'System overview', value: 'system_overview' }]);
    const runtimeServices = { loadPickerOptions } as unknown as RuntimeExecutionServices;

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'searchable_picker_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_picker',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_page',
                      blockType: 'field',
                      field: 'page_key',
                      props: {
                        label: 'Page',
                        component: 'picker',
                        pickerDataSource: 'model',
                        pickerSource: 'page_schema',
                        valueField: 'page_key',
                        displayField: 'name',
                        searchable: true,
                        searchPlaceholder: 'Search pages',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const search = await screen.findByTestId('runtime-picker-search-field_page');
    expect(search).toHaveAttribute('placeholder', 'Search pages');
    fireEvent.change(search, { target: { value: 'system' } });

    await waitFor(() => expect(loadPickerOptions).toHaveBeenCalledTimes(2));
    expect(loadPickerOptions).toHaveBeenLastCalledWith(
      expect.objectContaining({ id: 'field_page' }),
      expect.objectContaining({
        pageId: 'searchable_picker_form',
        pickerSearch: 'system',
      }),
    );
    await waitFor(() =>
      expect(screen.getByTestId('runtime-picker-field_page')).toHaveTextContent('System overview'),
    );
  });

  it('renders upload form controls with file constraints and selected file feedback', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'upload_component_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_upload',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_attachment',
                      blockType: 'field',
                      field: 'attachment',
                      props: {
                        label: 'Attachment',
                        component: 'upload',
                        accept: '.pdf,.docx',
                        multiple: true,
                        maxFiles: 2,
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const upload = screen.getByTestId('runtime-upload-field_attachment') as HTMLInputElement;
    expect(upload).toHaveAttribute('accept', '.pdf,.docx');
    expect(upload).toHaveAttribute('multiple');

    fireEvent.change(upload, {
      target: {
        files: [
          new File(['one'], 'one.pdf', { type: 'application/pdf' }),
          new File(['two'], 'two.docx', {
            type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          }),
          new File(['three'], 'three.txt', { type: 'text/plain' }),
        ],
      },
    });

    expect(screen.getByTestId('runtime-upload-files-field_attachment')).toHaveTextContent(
      'one.pdf',
    );
    expect(screen.getByTestId('runtime-upload-files-field_attachment')).toHaveTextContent(
      'two.docx',
    );
    expect(screen.getByTestId('runtime-upload-files-field_attachment')).not.toHaveTextContent(
      'three.txt',
    );
  });

  it('applies visibleWhen rules against runtime form values for fields and sections', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'conditional_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_basic',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_status',
                      blockType: 'field',
                      field: 'status',
                      props: {
                        label: 'Status',
                        component: 'select',
                        options: [
                          { label: 'Draft', value: 'draft' },
                          { label: 'Published', value: 'published' },
                        ],
                      },
                    },
                    {
                      id: 'field_reason',
                      blockType: 'field',
                      field: 'reason',
                      props: {
                        label: 'Reason',
                        component: 'input',
                        visibleWhen: { field: 'status', operator: 'equals', value: 'published' },
                      },
                    },
                    {
                      id: 'section_followup',
                      blockType: 'form-section',
                      title: 'Follow up',
                      props: {
                        visibleWhen: { field: 'status', operator: 'notEmpty' },
                      },
                      blocks: [
                        {
                          id: 'field_followup',
                          blockType: 'field',
                          field: 'followup',
                          props: { label: 'Follow up', component: 'input' },
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.queryByTestId('runtime-field-field_reason')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runtime-block-section_followup')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('runtime-select-field_status'), {
      target: { value: 'draft' },
    });

    expect(screen.queryByTestId('runtime-field-field_reason')).not.toBeInTheDocument();
    expect(screen.getByTestId('runtime-block-section_followup')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('runtime-select-field_status'), {
      target: { value: 'published' },
    });

    expect(screen.getByTestId('runtime-field-field_reason')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-block-section_followup')).toBeInTheDocument();
  });

  it('renders form sub-tables with configured columns and preview rows', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'form_sub_table_preview',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_basic',
                  blockType: 'form-section',
                  title: 'Basic',
                  blocks: [
                    {
                      id: 'sub_table_line_items',
                      blockType: 'sub-table',
                      title: 'Line items',
                      props: {
                        rows: [
                          { name: 'Setup', amount: 120 },
                          { name: 'Review', amount: 80 },
                        ],
                      },
                      blocks: [
                        {
                          id: 'column_name',
                          blockType: 'column',
                          field: 'name',
                          props: { label: 'Name' },
                        },
                        {
                          id: 'column_amount',
                          blockType: 'column',
                          field: 'amount',
                          props: { label: 'Amount' },
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-block-sub_table_line_items')).toHaveTextContent(
      'Line items',
    );
    expect(screen.getByTestId('runtime-column-column_name')).toHaveTextContent('Name');
    expect(screen.getByTestId('runtime-table-cell-sub_table_line_items-0-name')).toHaveTextContent(
      'Setup',
    );
    expect(
      screen.getByTestId('runtime-table-cell-sub_table_line_items-1-amount'),
    ).toHaveTextContent('80');
  });

  it('renders editable repeater rows and passes row values to form actions', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Submitted' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'repeater_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_lines',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'line_items',
                      blockType: 'repeater',
                      title: 'Line items',
                      props: { rows: [{ sku: 'A-001', qty: 1 }] },
                      blocks: [
                        {
                          id: 'field_sku',
                          blockType: 'field',
                          field: 'sku',
                          props: { label: 'SKU' },
                        },
                        {
                          id: 'field_qty',
                          blockType: 'field',
                          field: 'qty',
                          props: { label: 'Qty', component: 'number' },
                        },
                      ],
                    },
                  ],
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit',
                        command: 'order.submit',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-repeater-line_items')).toHaveTextContent('Line items');
    fireEvent.change(screen.getByTestId('runtime-repeater-input-line_items-0-field_sku'), {
      target: { value: 'B-002' },
    });
    fireEvent.click(screen.getByTestId('runtime-repeater-add-line_items'));
    fireEvent.change(screen.getByTestId('runtime-repeater-input-line_items-1-field_sku'), {
      target: { value: 'C-003' },
    });
    fireEvent.change(screen.getByTestId('runtime-repeater-input-line_items-1-field_qty'), {
      target: { value: '3' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_submit' }),
        expect.objectContaining({
          formValues: expect.objectContaining({
            line_items: [
              { sku: 'B-002', qty: 1 },
              { sku: 'C-003', qty: '3' },
            ],
          }),
        }),
      );
    });
  });

  it('renders nested subform row editors and passes row values to form actions', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Submitted' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'subform_form',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_team',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'team_members',
                      blockType: 'subform',
                      title: 'Team members',
                      props: { rows: [{ name: 'Ada', role: 'Owner' }] },
                      blocks: [
                        {
                          id: 'member_details',
                          blockType: 'form-section',
                          title: 'Member details',
                          blocks: [
                            {
                              id: 'field_member_name',
                              blockType: 'field',
                              field: 'name',
                              props: { label: 'Name' },
                            },
                            {
                              id: 'field_member_role',
                              blockType: 'field',
                              field: 'role',
                              props: { label: 'Role' },
                            },
                          ],
                        },
                      ],
                    },
                  ],
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit',
                        command: 'team.submit',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-subform-team_members')).toHaveTextContent('Team members');
    expect(
      screen.getByTestId('runtime-subform-section-team_members-0-member_details'),
    ).toHaveTextContent('Member details');
    fireEvent.change(screen.getByTestId('runtime-subform-input-team_members-0-field_member_name'), {
      target: { value: 'Grace' },
    });
    fireEvent.click(screen.getByTestId('runtime-subform-add-team_members'));
    fireEvent.change(screen.getByTestId('runtime-subform-input-team_members-1-field_member_name'), {
      target: { value: 'Linus' },
    });
    fireEvent.change(screen.getByTestId('runtime-subform-input-team_members-1-field_member_role'), {
      target: { value: 'Reviewer' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_submit' }),
        expect.objectContaining({
          formValues: expect.objectContaining({
            team_members: [
              { name: 'Grace', role: 'Owner' },
              { name: 'Linus', role: 'Reviewer' },
            ],
          }),
        }),
      );
    });
  });

  it('applies V3 layout values with stable inline grid styles', () => {
    render(<RecursiveBlockRenderer schema={samplePageSchemaV3} />);

    expect(screen.getByTestId('runtime-block-form_customer')).toHaveStyle({
      gridColumn: 'span 6 / span 6',
    });
    expect(screen.getByTestId('runtime-field-field_customer_name')).toHaveStyle({
      gridColumn: 'span 6 / span 6',
    });
    expect(screen.getByTestId('runtime-widget-widget_revenue')).toHaveStyle({
      gridColumn: '1 / span 3',
      gridRow: '1 / span 2',
    });
  });

  it('renders dashboard widget display configuration from V3 props and data source', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'dashboard',
          id: 'ops_dashboard',
          blocks: [
            {
              id: 'dashboard_ops',
              blockType: 'dashboard',
              title: 'Operations',
              layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
              blocks: [
                {
                  id: 'widget_revenue',
                  blockType: 'widget',
                  widgetType: 'number-card',
                  layout: { x: 0, y: 0, w: 4, h: 2 },
                  dataSource: { model: 'mission', metric: 'sum_amount' },
                  props: {
                    title: 'Revenue',
                    subtitle: 'Month to date',
                    value: '$42K',
                    format: 'currency',
                    drillDownTo: '/p/mission',
                    thresholds: [{ color: 'green', min: 40000 }],
                  },
                },
                {
                  id: 'widget_empty',
                  blockType: 'widget',
                  widgetType: 'bar-chart',
                  layout: { x: 4, y: 0, w: 4, h: 2 },
                  props: {
                    title: 'Pipeline',
                    emptyText: 'No pipeline data',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-widget-widget_revenue')).toHaveTextContent('Revenue');
    expect(screen.getByTestId('runtime-widget-subtitle-widget_revenue')).toHaveTextContent(
      'Month to date',
    );
    expect(screen.getByTestId('runtime-widget-value-widget_revenue')).toHaveTextContent('$42K');
    expect(screen.getByTestId('runtime-widget-meta-widget_revenue')).toHaveTextContent(
      'mission / sum_amount',
    );
    expect(screen.getByTestId('runtime-widget-drilldown-widget_revenue')).toHaveTextContent(
      '/p/mission',
    );
    expect(screen.getByTestId('runtime-widget-empty-widget_empty')).toHaveTextContent(
      'No pipeline data',
    );
  });

  it('renders dashboard chart, table, markdown, and error widget states from V3 props', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'dashboard',
          id: 'analytics_dashboard',
          blocks: [
            {
              id: 'dashboard_analytics',
              blockType: 'dashboard',
              title: 'Analytics',
              layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
              blocks: [
                {
                  id: 'widget_bar',
                  blockType: 'widget',
                  widgetType: 'bar-chart',
                  layout: { x: 0, y: 0, w: 3, h: 2 },
                  props: {
                    title: 'Stage mix',
                    series: [
                      { label: 'Open', value: 3 },
                      { label: 'Won', value: 7 },
                    ],
                  },
                },
                {
                  id: 'widget_line',
                  blockType: 'widget',
                  widgetType: 'line-chart',
                  layout: { x: 3, y: 0, w: 3, h: 2 },
                  props: {
                    title: 'Trend',
                    series: [
                      { label: 'Mon', value: 2 },
                      { label: 'Tue', value: 5 },
                      { label: 'Wed', value: 4 },
                    ],
                  },
                },
                {
                  id: 'widget_table',
                  blockType: 'widget',
                  widgetType: 'table',
                  layout: { x: 6, y: 0, w: 3, h: 2 },
                  props: {
                    title: 'Top owners',
                    columns: ['Owner', 'Open'],
                    rows: [
                      ['Ada', 4],
                      ['Lin', 3],
                    ],
                  },
                },
                {
                  id: 'widget_markdown',
                  blockType: 'widget',
                  widgetType: 'markdown',
                  layout: { x: 9, y: 0, w: 3, h: 2 },
                  props: {
                    title: 'Notes',
                    markdown: 'Pipeline review every Friday',
                  },
                },
                {
                  id: 'widget_error',
                  blockType: 'widget',
                  widgetType: 'number-card',
                  layout: { x: 0, y: 2, w: 3, h: 2 },
                  props: {
                    title: 'Broken metric',
                    errorText: 'Query failed',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('runtime-widget-bar-widget_bar-0')).toHaveTextContent('Open');
    expect(screen.getByTestId('runtime-widget-bar-widget_bar-0')).toHaveAttribute('data-value', '3');
    expect(screen.getByTestId('runtime-widget-bar-widget_bar-1')).toHaveTextContent('Won');
    expect(screen.getByTestId('runtime-widget-line-widget_line')).toHaveAttribute(
      'data-points',
      '2,5,4',
    );
    expect(screen.getByTestId('runtime-widget-table-widget_table')).toHaveTextContent('Owner');
    expect(screen.getByTestId('runtime-widget-table-widget_table')).toHaveTextContent('Ada');
    expect(screen.getByTestId('runtime-widget-markdown-widget_markdown')).toHaveTextContent(
      'Pipeline review every Friday',
    );
    expect(screen.getByTestId('runtime-widget-error-widget_error')).toHaveTextContent(
      'Query failed',
    );
  });

  it('executes action blocks with inline feedback, overlays, and non-native confirmation', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'action_runtime_page',
          blocks: [
            {
              id: 'action_bar',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_command',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Archive',
                    command: 'mission.archive',
                    confirm: true,
                    feedback: 'Archived',
                  },
                },
                {
                  id: 'action_workflow',
                  blockType: 'action',
                  actionType: 'workflow',
                  props: { label: 'Approve', workflowKey: 'mission_approval' },
                },
                {
                  id: 'action_navigate',
                  blockType: 'action',
                  actionType: 'navigate',
                  props: { label: 'Open mission', to: '/p/mission', target: 'self' },
                },
                {
                  id: 'action_modal',
                  blockType: 'action',
                  actionType: 'modal',
                  props: { label: 'Quick edit', pageKey: 'mission_edit', title: 'Edit mission' },
                },
                {
                  id: 'action_drawer',
                  blockType: 'action',
                  actionType: 'drawer',
                  props: { label: 'Details', pageKey: 'mission_detail', title: 'Mission detail' },
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_command'));
    expect(screen.getByTestId('runtime-action-confirm-action_command')).toHaveTextContent(
      'Click again to confirm',
    );
    expect(screen.queryByTestId('runtime-action-status-action_command')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('runtime-action-action_command'));
    expect(screen.getByTestId('runtime-action-status-action_command')).toHaveTextContent(
      'Archived',
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_workflow'));
    expect(screen.getByTestId('runtime-action-status-action_workflow')).toHaveTextContent(
      'Workflow started: mission_approval',
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_navigate'));
    expect(screen.getByTestId('runtime-action-status-action_navigate')).toHaveTextContent(
      'Navigate to /p/mission',
    );
    expect(screen.getByTestId('runtime-action-action_navigate')).toHaveAttribute(
      'data-href',
      '/p/mission',
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_modal'));
    expect(screen.getByTestId('runtime-action-overlay-action_modal')).toHaveTextContent(
      'Edit mission',
    );
    expect(screen.getByTestId('runtime-action-overlay-action_modal')).toHaveAttribute(
      'data-overlay-kind',
      'modal',
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_drawer'));
    expect(screen.getByTestId('runtime-action-overlay-action_drawer')).toHaveTextContent(
      'Mission detail',
    );
    expect(screen.getByTestId('runtime-action-overlay-action_drawer')).toHaveAttribute(
      'data-overlay-kind',
      'drawer',
    );
  });

  it('routes live command actions through injected runtime services', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Command executed on API' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'live_action_page',
          blocks: [
            {
              id: 'action_bar',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_live',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Import',
                    command: 'customer.import',
                    executionMode: 'live',
                    payload: { dryRun: true },
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_live'));

    await screen.findByTestId('runtime-action-status-action_live');
    expect(screen.getByTestId('runtime-action-action_live')).toHaveAttribute(
      'data-live-execution',
      'true',
    );
    expect(screen.getByTestId('runtime-action-status-action_live')).toHaveTextContent(
      'Command executed on API',
    );
    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_live' }),
        expect.objectContaining({
          source: 'unified-designer-runtime-preview',
          pageId: 'live_action_page',
          pageKind: 'list',
          schemaVersion: 3,
          blockId: 'action_live',
          blockType: 'action',
          actionType: 'command',
          blockPath: ['action_bar', 'action_live'],
        }),
      );
    });
  });

  it('passes runtime form values to live actions inside the same form', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Submitted' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'form_runtime_values',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_basic',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_name',
                      blockType: 'field',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                    {
                      id: 'field_status',
                      blockType: 'field',
                      field: 'status',
                      props: { label: 'Status' },
                    },
                  ],
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit',
                        command: 'customer.submit',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.change(screen.getByTestId('runtime-input-field_name'), {
      target: { value: 'Ada Lovelace' },
    });
    fireEvent.change(screen.getByTestId('runtime-input-field_status'), {
      target: { value: 'draft' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_submit' }),
        expect.objectContaining({
          formValues: {
            name: 'Ada Lovelace',
            status: 'draft',
          },
        }),
      );
    });
  });

  it('applies form action visibleWhen and disabledWhen rules against form values', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'form_runtime_action_conditions',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'field_status',
                  blockType: 'field',
                  field: 'status',
                  props: { label: 'Status', component: 'input' },
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit',
                        command: 'form.submit',
                        visibleWhen: { field: 'status', operator: 'notEmpty' },
                        disabledWhen: { field: 'status', operator: 'equals', value: 'blocked' },
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(screen.queryByTestId('runtime-action-action_submit')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('runtime-input-field_status'), {
      target: { value: 'ready' },
    });
    expect(screen.getByTestId('runtime-action-action_submit')).toBeEnabled();

    fireEvent.change(screen.getByTestId('runtime-input-field_status'), {
      target: { value: 'blocked' },
    });
    expect(screen.getByTestId('runtime-action-action_submit')).toBeDisabled();
    expect(screen.getByTestId('runtime-action-action_submit')).toHaveAttribute(
      'data-condition-disabled',
      'true',
    );
  });

  it('validates form field rules before executing form actions', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Submitted' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'form_runtime_validation',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'section_basic',
                  blockType: 'form-section',
                  blocks: [
                    {
                      id: 'field_name',
                      blockType: 'field',
                      field: 'name',
                      props: {
                        label: 'Name',
                        required: true,
                        validationRules: [
                          { type: 'minLength', value: 3, message: 'Name is too short' },
                          { type: 'pattern', value: '^A', message: 'Name must start with A' },
                        ],
                      },
                    },
                  ],
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit',
                        command: 'customer.submit',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    expect(runtimeServices.executeAction).not.toHaveBeenCalled();
    expect(screen.getByTestId('runtime-field-error-field_name')).toHaveTextContent('Required');

    fireEvent.change(screen.getByTestId('runtime-input-field_name'), {
      target: { value: 'Bo' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    expect(runtimeServices.executeAction).not.toHaveBeenCalled();
    expect(screen.getByTestId('runtime-field-error-field_name')).toHaveTextContent(
      'Name is too short',
    );

    fireEvent.change(screen.getByTestId('runtime-input-field_name'), {
      target: { value: 'Bob' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    expect(runtimeServices.executeAction).not.toHaveBeenCalled();
    expect(screen.getByTestId('runtime-field-error-field_name')).toHaveTextContent(
      'Name must start with A',
    );

    fireEvent.change(screen.getByTestId('runtime-input-field_name'), {
      target: { value: 'Ada' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_submit' }),
        expect.objectContaining({
          formValues: { name: 'Ada' },
        }),
      );
    });
    expect(screen.queryByTestId('runtime-field-error-field_name')).not.toBeInTheDocument();
  });

  it('validates repeater and subform row fields before executing form actions', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Nested submitted' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'form',
          id: 'nested_form_runtime_validation',
          blocks: [
            {
              id: 'form_root',
              blockType: 'form',
              blocks: [
                {
                  id: 'repeater_contacts',
                  blockType: 'repeater',
                  field: 'contacts',
                  props: {
                    rows: [{ email: '' }],
                  },
                  blocks: [
                    {
                      id: 'field_contact_email',
                      blockType: 'field',
                      field: 'email',
                      props: {
                        label: 'Email',
                        required: true,
                      },
                    },
                  ],
                },
                {
                  id: 'subform_tasks',
                  blockType: 'subform',
                  field: 'tasks',
                  props: {
                    rows: [{ title: '' }],
                  },
                  blocks: [
                    {
                      id: 'task_section',
                      blockType: 'form-section',
                      blocks: [
                        {
                          id: 'field_task_title',
                          blockType: 'field',
                          field: 'title',
                          props: {
                            label: 'Task title',
                            required: true,
                          },
                        },
                      ],
                    },
                  ],
                },
                {
                  id: 'form_actions',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_submit_nested',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Submit nested',
                        command: 'nested.submit',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_submit_nested'));

    expect(runtimeServices.executeAction).not.toHaveBeenCalled();
    expect(
      screen.getByTestId('runtime-repeater-input-error-repeater_contacts-0-field_contact_email'),
    ).toHaveTextContent('Required');
    expect(
      screen.getByTestId('runtime-subform-input-error-subform_tasks-0-field_task_title'),
    ).toHaveTextContent('Required');

    fireEvent.change(
      screen.getByTestId('runtime-repeater-input-repeater_contacts-0-field_contact_email'),
      {
        target: { value: 'ada@example.com' },
      },
    );
    fireEvent.change(screen.getByTestId('runtime-subform-input-subform_tasks-0-field_task_title'), {
      target: { value: 'Prepare launch' },
    });
    fireEvent.click(screen.getByTestId('runtime-action-action_submit_nested'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_submit_nested' }),
        expect.objectContaining({
          formValues: {
            contacts: [{ email: 'ada@example.com' }],
            tasks: [{ title: 'Prepare launch' }],
          },
        }),
      );
    });
    expect(
      screen.queryByTestId('runtime-repeater-input-error-repeater_contacts-0-field_contact_email'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('runtime-subform-input-error-subform_tasks-0-field_task_title'),
    ).not.toBeInTheDocument();
  });

  it('passes selected table rows to live toolbar actions inside the same list', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Bulk action completed' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'list_runtime_selection',
          blocks: [
            {
              id: 'list_root',
              blockType: 'list',
              blocks: [
                {
                  id: 'list_toolbar',
                  blockType: 'action-bar',
                  blocks: [
                    {
                      id: 'action_bulk',
                      blockType: 'action',
                      actionType: 'command',
                      props: {
                        label: 'Bulk archive',
                        command: 'customer.bulkArchive',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
                {
                  id: 'table_customers',
                  blockType: 'table',
                  props: {
                    rows: [
                      { pid: 'row_001', name: 'Ada' },
                      { pid: 'row_002', name: 'Grace' },
                    ],
                  },
                  blocks: [
                    {
                      id: 'column_name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-row-select-table_customers-0'));
    fireEvent.click(screen.getByTestId('runtime-action-action_bulk'));

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_bulk' }),
        expect.objectContaining({
          selectedRows: [{ pid: 'row_001', name: 'Ada' }],
          selectedRowIds: ['row_001'],
        }),
      );
    });
  });

  it('passes the clicked table row to live row actions', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Row action completed' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'list_runtime_current_row',
          blocks: [
            {
              id: 'list_root',
              blockType: 'list',
              blocks: [
                {
                  id: 'table_customers',
                  blockType: 'table',
                  props: {
                    rows: [
                      { pid: 'row_001', name: 'Ada' },
                      { pid: 'row_002', name: 'Grace' },
                    ],
                  },
                  blocks: [
                    {
                      id: 'column_name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                    {
                      id: 'action_open_row',
                      blockType: 'action',
                      region: 'row-actions',
                      actionType: 'command',
                      props: {
                        label: 'Open row',
                        command: 'customer.open',
                        executionMode: 'live',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(
      screen.getByTestId('runtime-row-action-table_customers-action_open_row-1'),
    );

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_open_row' }),
        expect.objectContaining({
          currentRow: { pid: 'row_002', name: 'Grace' },
          currentRowId: 'row_002',
          blockPath: ['list_root', 'table_customers', 'action_open_row'],
        }),
      );
    });
    expect(
      await screen.findByTestId('runtime-row-action-status-table_customers-action_open_row-1'),
    ).toHaveTextContent('Row action completed');
  });

  it('applies row action visibleWhen and disabledWhen rules against the current row', () => {
    render(
      <RecursiveBlockRenderer
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'list_runtime_row_conditions',
          blocks: [
            {
              id: 'list_root',
              blockType: 'list',
              blocks: [
                {
                  id: 'table_customers',
                  blockType: 'table',
                  props: {
                    rows: [
                      { pid: 'row_001', name: 'Ada', status: 'locked' },
                      { pid: 'row_002', name: 'Grace', status: 'hidden' },
                      { pid: 'row_003', name: 'Lin', status: 'active' },
                    ],
                  },
                  blocks: [
                    {
                      id: 'column_name',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Name' },
                    },
                    {
                      id: 'action_open_row',
                      blockType: 'action',
                      region: 'row-actions',
                      actionType: 'command',
                      props: {
                        label: 'Open row',
                        command: 'customer.open',
                        visibleWhen: { field: 'status', operator: 'notEquals', value: 'hidden' },
                        disabledWhen: { field: 'current.rowId', operator: 'equals', value: 'row_001' },
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }}
      />,
    );

    const lockedAction = screen.getByTestId(
      'runtime-row-action-table_customers-action_open_row-0',
    );
    expect(lockedAction).toBeDisabled();
    expect(lockedAction).toHaveAttribute('data-condition-disabled', 'true');
    expect(
      screen.queryByTestId('runtime-row-action-table_customers-action_open_row-1'),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('runtime-row-action-table_customers-action_open_row-2')).toBeEnabled();
  });

  it('shows live runtime action failures inline', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi
        .fn()
        .mockRejectedValue(Object.assign(new Error('Access forbidden'), { code: '403' })),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'live_action_error_page',
          blocks: [
            {
              id: 'action_bar',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_error',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Run command',
                    command: 'missing.command',
                    executionMode: 'live',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('runtime-action-action_error'));

    const error = await screen.findByTestId('runtime-action-error-action_error');
    expect(error).toHaveTextContent('Access forbidden');
    expect(error).toHaveAttribute('data-error-kind', 'permission');
    expect(error).toHaveAttribute('data-error-code', '403');
    expect(screen.getByTestId('runtime-action-error-hint-action_error')).toHaveTextContent(
      'Check the permission required by this block.',
    );
  });

  it('blocks action execution when the required permission is missing', () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Should not execute' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'permission_action_page',
          blocks: [
            {
              id: 'action_bar',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_secure_export',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Secure export',
                    command: 'page_schema.export',
                    executionMode: 'live',
                    permissionCode: 'meta.page-schema.export',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    const action = screen.getByTestId('runtime-action-action_secure_export');
    expect(action).toBeDisabled();
    expect(action).toHaveAttribute('data-permission-code', 'meta.page-schema.export');
    expect(action).toHaveAttribute('data-permission-allowed', 'false');
    expect(screen.getByTestId('runtime-action-permission-action_secure_export')).toHaveTextContent(
      'Requires permission: meta.page-schema.export',
    );

    fireEvent.click(action);

    expect(runtimeServices.executeAction).not.toHaveBeenCalled();
  });

  it('executes permission-protected action blocks when the host permission evaluator allows it', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      executeAction: vi.fn().mockResolvedValue({ status: 'Secure export completed' }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        permissionEvaluator={(permissionCode) => permissionCode === 'meta.page-schema.export'}
        schema={{
          schemaVersion: 3,
          kind: 'list',
          id: 'permission_action_allowed_page',
          blocks: [
            {
              id: 'action_bar',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_secure_export',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Secure export',
                    command: 'page_schema.export',
                    executionMode: 'live',
                    permissionCode: 'meta.page-schema.export',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    const action = screen.getByTestId('runtime-action-action_secure_export');
    expect(action).not.toBeDisabled();
    expect(action).toHaveAttribute('data-permission-code', 'meta.page-schema.export');
    expect(action).toHaveAttribute('data-permission-allowed', 'true');
    expect(screen.getByTestId('runtime-action-permission-action_secure_export')).toHaveTextContent(
      'Permission: meta.page-schema.export',
    );

    fireEvent.click(action);

    await waitFor(() => {
      expect(runtimeServices.executeAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'action_secure_export' }),
        expect.objectContaining({
          pageId: 'permission_action_allowed_page',
          blockId: 'action_secure_export',
          actionType: 'command',
          permissionCode: 'meta.page-schema.export',
        }),
      );
    });
    expect(screen.getByTestId('runtime-action-status-action_secure_export')).toHaveTextContent(
      'Secure export completed',
    );
  });

  it('loads widget query data through injected runtime services', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadWidgetData: vi.fn().mockResolvedValue({
        source: 'query-builder',
        columns: ['name', 'page_key'],
        rows: [['System overview', 'system_overview']],
      }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'dashboard',
          id: 'live_widget_page',
          blocks: [
            {
              id: 'dashboard_live',
              blockType: 'dashboard',
              title: 'Live dashboard',
              layout: { span: 12, rowHeight: 80, gap: 16 },
              blocks: [
                {
                  id: 'widget_live_table',
                  blockType: 'widget',
                  widgetType: 'table',
                  title: 'Pages',
                  layout: { x: 0, y: 0, w: 6, h: 2 },
                  dataSource: {
                    model: 'page_schema',
                    executionMode: 'live',
                    query: {
                      modelCode: 'page_schema',
                      fields: ['name', 'page_key'],
                      limit: 3,
                    },
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(await screen.findByTestId('runtime-widget-table-widget_live_table')).toHaveTextContent(
      'System overview',
    );
    expect(screen.getByTestId('runtime-widget-meta-widget_live_table')).toHaveTextContent(
      'query-builder / page_schema',
    );
    await waitFor(() => {
      expect(runtimeServices.loadWidgetData).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'widget_live_table' }),
        expect.objectContaining({
          source: 'unified-designer-runtime-preview',
          pageId: 'live_widget_page',
          pageKind: 'dashboard',
          schemaVersion: 3,
          blockId: 'widget_live_table',
          blockType: 'widget',
          widgetType: 'table',
          blockPath: ['dashboard_live', 'widget_live_table'],
        }),
      );
    });
  });

  it('loads widget named query data through injected runtime services', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadWidgetData: vi.fn().mockResolvedValue({
        source: 'named-query',
        columns: ['name', 'page_key'],
        rows: [['Named query page', 'named_query_page']],
      }),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'dashboard',
          id: 'named_query_widget_page',
          blocks: [
            {
              id: 'dashboard_named_query',
              blockType: 'dashboard',
              title: 'Named query dashboard',
              blocks: [
                {
                  id: 'widget_named_query_table',
                  blockType: 'widget',
                  widgetType: 'table',
                  title: 'Named query pages',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'udw_pages',
                    page: 1,
                    size: 20,
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    expect(
      await screen.findByTestId('runtime-widget-table-widget_named_query_table'),
    ).toHaveTextContent('Named query page');
    expect(screen.getByTestId('runtime-widget-meta-widget_named_query_table')).toHaveTextContent(
      'named-query',
    );
    await waitFor(() => {
      expect(runtimeServices.loadWidgetData).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'widget_named_query_table' }),
        expect.objectContaining({
          source: 'unified-designer-runtime-preview',
          pageId: 'named_query_widget_page',
          pageKind: 'dashboard',
          schemaVersion: 3,
          blockId: 'widget_named_query_table',
          blockType: 'widget',
          widgetType: 'table',
          blockPath: ['dashboard_named_query', 'widget_named_query_table'],
        }),
      );
    });
  });

  it('classifies live widget runtime failures for permission states', async () => {
    const runtimeServices: RuntimeExecutionServices = {
      loadWidgetData: vi
        .fn()
        .mockRejectedValue(Object.assign(new Error('Access denied'), { code: '403' })),
    };

    render(
      <RecursiveBlockRenderer
        runtimeServices={runtimeServices}
        schema={{
          schemaVersion: 3,
          kind: 'dashboard',
          id: 'permission_widget_page',
          blocks: [
            {
              id: 'dashboard_permission',
              blockType: 'dashboard',
              title: 'Permission dashboard',
              blocks: [
                {
                  id: 'widget_permission',
                  blockType: 'widget',
                  widgetType: 'number-card',
                  title: 'Restricted metric',
                  dataSource: {
                    type: 'namedQuery',
                    executionMode: 'live',
                    queryCode: 'restricted_metric',
                  },
                },
              ],
            },
          ],
        }}
      />,
    );

    const error = await screen.findByTestId('runtime-widget-error-widget_permission');
    expect(error).toHaveTextContent('Access denied');
    expect(error).toHaveAttribute('data-error-kind', 'permission');
    expect(error).toHaveAttribute('data-error-code', '403');
    expect(screen.getByTestId('runtime-widget-error-hint-widget_permission')).toHaveTextContent(
      'Check the permission required by this block.',
    );
  });
});
