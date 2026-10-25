import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import type { ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ListPageModel } from './listPageModel';
import { ListTabs } from './ListTabs';

/** list page filters — preserves the original element and event-handler expressions. */
export function renderListPageFilters(
  model: Pick<
    ListPageModel,
    | 'listTabsBlock'
    | 'workspaceTabCounts'
    | 'activeTab'
    | 'handleTabChange'
    | 'locale'
    | 't'
    | 'workspaceTabCountError'
    | 'filterFormVisible'
    | 'filterBlock'
    | 'modelCode'
    | 'handleSearch'
    | 'filtersExpanded'
    | 'renderSmartField'
    | 'setFiltersExpanded'
    | 'currentView'
    | 'handleSaveFilters'
    | 'translateCommon'
    | 'handleAction'
    | 'resolveButtonLabel'
    | 'handleReset'
  >,
) {
  const {
    listTabsBlock,
    workspaceTabCounts,
    activeTab,
    handleTabChange,
    locale,
    t,
    workspaceTabCountError,
    filterFormVisible,
    filterBlock,
    modelCode,
    handleSearch,
    filtersExpanded,
    renderSmartField,
    setFiltersExpanded,
    currentView,
    handleSaveFilters,
    translateCommon,
    handleAction,
    resolveButtonLabel,
    handleReset,
  } = model;
  return (
    <>
      {/* List Tabs */}
      {listTabsBlock?.tabs && (listTabsBlock.tabs as any[]).length > 0 && (
        <div data-aura-block-id={listTabsBlock.id} data-aura-element-id={listTabsBlock.id}>
          <ListTabs
            tabs={(listTabsBlock.tabs as any[]).map((tab) => ({
              ...tab,
              ...(listTabsBlock.showCounts ? { count: workspaceTabCounts[tab.key] } : {}),
            }))}
            activeTab={activeTab}
            onTabChange={handleTabChange}
            locale={locale}
            t={t}
          />
        </div>
      )}

      {workspaceTabCountError && (
        <p role="status" className="text-status-amber px-6 py-2 text-xs">
          {getLocalizedText(
            {
              'zh-CN': '视图数量暂不可用，请刷新重试。',
              en: 'View counts are unavailable. Refresh to try again.',
            },
            locale,
            t,
          )}
        </p>
      )}
      {/* Filter area - Using Smart Components with collapse/expand (hidden in print) */}
      {filterFormVisible && filterBlock && filterBlock.fields && filterBlock.fields.length > 0 && (
        <div
          data-testid="search-area"
          data-aura-block-id={filterBlock.id}
          data-aura-element-id={filterBlock.id}
          data-ab-testid={deriveTestId('list', modelCode, 'filters')}
          className="print-hide border-border bg-subtle border-b px-6 py-4"
          data-print="hide"
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              handleSearch();
            }
          }}
        >
          <div
            className={`grid grid-cols-1 gap-4 md:grid-cols-12 ${
              !filtersExpanded && filterBlock.fields.length > 4
                ? 'max-h-[72px] overflow-hidden'
                : ''
            }`}
          >
            {filterBlock.fields.map((field: FieldConfig) => (
              <div
                key={field.field}
                data-authoring-node-id={(field as any).id || field.field}
                className="min-w-0"
                style={{
                  gridColumn: `span ${Math.min(Math.max(field.layout?.colSpan || 4, 1), 12)}`,
                }}
              >
                {renderSmartField(
                  field.component === 'SmartSelect' && field.props?.inline === undefined
                    ? { ...field, props: { ...(field.props || {}), inline: true } }
                    : field,
                )}
              </div>
            ))}
          </div>
          <div className="mt-4 flex items-center justify-between">
            {/* Expand/collapse toggle */}
            <div>
              {filterBlock.fields.length > 4 && (
                <button
                  type="button"
                  data-testid="filter-toggle"
                  onClick={() => setFiltersExpanded((prev) => !prev)}
                  className="text-accent flex items-center gap-1 text-sm hover:text-blue-800"
                >
                  {filtersExpanded ? (
                    <>
                      <svg
                        className="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 15l7-7 7 7"
                        />
                      </svg>
                      {t('action.collapse') !== 'action.collapse'
                        ? t('action.collapse')
                        : 'Collapse'}
                    </>
                  ) : (
                    <>
                      <svg
                        className="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M19 9l-7 7-7-7"
                        />
                      </svg>
                      {t('action.expand') !== 'action.expand' ? t('action.expand') : 'Expand'}
                    </>
                  )}
                </button>
              )}
            </div>
            <div className="flex items-center space-x-2">
              {/* Save current filters to view */}
              {currentView && (
                <button
                  type="button"
                  data-testid="filter-save"
                  onClick={handleSaveFilters}
                  className="rounded-control text-text-2 hover:bg-hover hover:text-text-2 px-3 py-2 text-sm transition-colors"
                  title={translateCommon('common.saved_view_save_filters', '保存筛选到当前视图')}
                >
                  <svg
                    className="mr-1 inline h-4 w-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"
                    />
                  </svg>
                </button>
              )}
              {/* DSL-driven buttons if defined, otherwise fallback to defaults */}
              {filterBlock.buttons && filterBlock.buttons.length > 0 ? (
                filterBlock.buttons.map((button: ButtonConfig) => (
                  <button
                    type="button"
                    key={button.code}
                    data-testid={`filter-btn-${button.code}`}
                    data-authoring-node-id={(button as any).id || button.code}
                    onClick={() => handleAction(button)}
                    className={`rounded-control px-4 py-2 ${
                      button.primary || button.variant === 'primary'
                        ? 'bg-accent hover:bg-accent-hover text-white'
                        : button.variant === 'danger' || button.danger
                          ? 'bg-red-600 text-white hover:bg-red-700'
                          : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
                    }`}
                  >
                    {resolveButtonLabel(button)}
                  </button>
                ))
              ) : (
                <>
                  {/* Default buttons for backward compatibility */}
                  <button
                    type="button"
                    data-testid="filter-reset"
                    onClick={handleReset}
                    className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2"
                  >
                    {t('action.reset')}
                  </button>
                  <button
                    type="button"
                    data-testid="filter-search"
                    onClick={handleSearch}
                    className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white"
                  >
                    {t('action.search')}
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
