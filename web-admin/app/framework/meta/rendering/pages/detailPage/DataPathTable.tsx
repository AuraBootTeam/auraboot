import { useMediaQuery } from '~/framework/meta/rendering/components/ResponsiveBlockLayout';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { getByDataPath } from './detailRecordRequests';

/**
 * DataPathTable - read-only table rendering a nested array (block.dataPath) of a detail
 * page's raw payload. Honors localized column labels, dict-tag rendering (renderType:"tag"
 * + dictCode) and datetime formatting — mirroring the list table's column conventions — so
 * detail sub-tables never leak raw field codes.
 */
export function DataPathTable({
  title,
  columns,
  rows,
  locale,
  t,
  getDictItems,
  mobileCard,
}: {
  title?: any;
  columns: Array<{
    field: string;
    label?: any;
    width?: number;
    renderType?: string;
    dictCode?: string;
  }>;
  rows: any[];
  mobileCard?: { enabled?: boolean; titleField?: string };
  locale: string;
  t: (key: string) => string;
  getDictItems?: (
    code: string,
  ) => Array<{ value: string; label: string; extension?: Record<string, any> }>;
}) {
  const narrow = useMediaQuery('(max-width: 640px)');
  const renderCell = (col: { field: string; renderType?: string; dictCode?: string }, row: any) => {
    const raw =
      row && Object.prototype.hasOwnProperty.call(row, col.field)
        ? row[col.field]
        : getByDataPath(row, col.field);
    if (raw == null || raw === '') return <span className="text-text-3">—</span>;
    if (col.renderType === 'tag' && col.dictCode && getDictItems) {
      const items = getDictItems(col.dictCode) || [];
      const match = items.find((i) => String(i.value) === String(raw));
      // §3 / §1.3: dict-coded status renders as 色点 + 文字, not a filled pill.
      return (
        <StatusDot
          tone={resolveStatusTone(match?.extension?.color)}
          label={match?.label ?? String(raw)}
        />
      );
    }
    if (col.renderType === 'datetime') {
      const d = new Date(String(raw));
      return <span>{isNaN(d.getTime()) ? String(raw) : d.toLocaleString(locale)}</span>;
    }
    return <span>{String(raw)}</span>;
  };

  if (narrow && mobileCard?.enabled === true) {
    const titleColumn =
      columns.find((column) => column.field === mobileCard.titleField) || columns[0];
    if (mobileCard.titleField && titleColumn?.field !== mobileCard.titleField) {
      throw new Error('DataPathTable mobileCard.titleField must identify a declared column');
    }
    return (
      <section className="sub-table-section min-w-0" data-testid="detail-datapath-cards">
        {title && (
          <h3 className="text-text-2 mb-5 text-sm font-semibold">
            {getLocalizedText(title, locale, t)}
          </h3>
        )}
        {rows.length === 0 ? (
          <p
            className="border-border text-text-3 rounded border px-4 py-5 text-sm"
            data-testid="detail-datapath-empty"
          >
            {t('common.noData') || '—'}
          </p>
        ) : (
          <div className="grid min-w-0 gap-3">
            {rows.map((row, index) => (
              <article
                key={row?.pid ?? row?.sourcePid ?? row?.id ?? index}
                data-testid="detail-datapath-card"
                className="border-border bg-panel min-w-0 rounded-lg border p-4 shadow-sm"
              >
                {titleColumn && (
                  <div className="min-w-0 break-words">
                    <p className="text-text-3 text-xs">
                      {getLocalizedText(titleColumn.label, locale, t) || titleColumn.field}
                    </p>
                    <h4 className="text-text mt-1 text-base font-semibold">
                      {renderCell(titleColumn, row)}
                    </h4>
                  </div>
                )}
                <dl className="border-border mt-3 grid min-w-0 grid-cols-1 gap-3 border-t pt-3">
                  {columns
                    .filter((column) => column !== titleColumn)
                    .map((column) => (
                      <div key={column.field} className="min-w-0">
                        <dt className="text-text-3 text-xs">
                          {getLocalizedText(column.label, locale, t) || column.field}
                        </dt>
                        <dd className="text-text mt-1 min-w-0 text-sm break-words whitespace-pre-wrap">
                          {renderCell(column, row)}
                        </dd>
                      </div>
                    ))}
                </dl>
              </article>
            ))}
          </div>
        )}
      </section>
    );
  }

  return (
    <div className="sub-table-section">
      {title && (
        <h3 className="text-text-2 mb-5 text-sm font-semibold tracking-wider uppercase">
          {getLocalizedText(title, locale, t)}
        </h3>
      )}
      <div className="border-border overflow-x-auto rounded border">
        <table
          className="divide-border min-w-full divide-y text-sm"
          data-testid="detail-datapath-table"
        >
          <thead className="bg-subtle">
            <tr>
              {columns.map((col) => (
                <th
                  key={col.field}
                  className="text-text-2 px-3 py-2 text-left font-medium"
                  style={col.width ? { minWidth: col.width } : undefined}
                >
                  {getLocalizedText(col.label, locale, t) || col.field}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-panel divide-border divide-y">
            {rows.length === 0 ? (
              <tr>
                <td className="text-text-3 px-3 py-4 text-center" colSpan={columns.length || 1}>
                  {t('common.noData') || '暂无数据'}
                </td>
              </tr>
            ) : (
              rows.map((row, idx) => (
                <tr key={row?.id ?? idx} data-testid="detail-datapath-row">
                  {columns.map((col) => (
                    <td key={col.field} className="text-text-2 px-3 py-2">
                      {renderCell(col, row)}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
