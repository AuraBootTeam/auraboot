# SmartKanban

A schema-driven kanban view for `kind=list` pages, rendered via `SavedView` of `viewType: kanban`.

## Persistence Contract

When a card is dragged to another column, `useKanbanData.moveCard` performs:

- **Method**: `PUT`
- **URL**: `/api/dynamic/{pageKey}/{recordId}`
- **Body**: `{ [groupByField]: targetColumnId }` (partial update — only the group field is sent)
- **Success**: response body `{ code: '0', ... }` (judged via `ResultHelper.isSuccess`)
- **Failure**: any non-success code OR network error → optimistic update is rolled back to `sourceColumnId` and `onMoveError({ code, message })` is called

Persistence is **opt-in**: pass `pageKey` to enable. When omitted (legacy callers), `moveCard` only updates local state.

Permission: backend requires `model.{modelCode}.update`.

## viewConfig fields

| Field | Type | Purpose |
|---|---|---|
| `groupByField` | string (required) | Field on the row used to group columns |
| `groupByDictCode` | string | Dict code for column header color/terminal injection |
| `terminalStages` | `{ won?: string[]; lost?: string[] }` | Override dict `extension.terminal` for which column ids are won/lost |
| `moveCommand` | string | Future: route persistence through a Command instead of direct PUT |
| `cardFields` | `KanbanCardField[]` | Card body field renderers |
| `kanbanAggregations` | `KanbanAggregation[]` | Column-level aggregations (sum / count) |
| `draggable` | boolean | Enable @dnd-kit drag |
| `showCount` | boolean | Show card count next to column title |
| `showAggregations` | boolean | Show aggregation row in header |

## cardFields[].type

| `type` | Renders |
|---|---|
| `text` (default) | Plain text |
| `number` | Number |
| `tag` | Pill |
| `date` | Locale date |
| `currency` | `Intl.NumberFormat` (default `currencyCode='CNY'`) |
| `avatar` | Initial circle + name |
| `progress` | Bar + percentage (default `max=100`) |
| `date-relative` | Relative date with `<7d` red highlight, `past` grey |

`null` / `undefined` / `NaN` / invalid date → renders `—`.

## Dict integration (color & terminal)

- Dict items expose extras via the DTO field **`extension`** (NOT `extra`).
- Plugin authors must put attributes under `extension`, e.g.:

  ```json
  {
    "value": "closed_won",
    "label": "Won",
    "extension": { "color": "#10b981", "terminal": "won" }
  }
  ```

- Top-level `color` in `dicts.json` is silently dropped by `PluginImportService`.
- `useDictWithExtras` flattens `extension.color` + `extension.terminal` to top-level fields, narrows `terminal` to `'won' | 'lost' | undefined`, and merges them into `KanbanColumn`. `viewConfig.terminalStages` takes precedence over dict `extension.terminal`.

## Visual rules

- Terminal columns: `won` → green header + ✓ icon; `lost` → grey header + ✕ icon.
- Terminal cards: `won` → left border `border-l-green-500` + corner ✓; `lost` → left border `border-l-gray-400` + corner ✕; default → `border-l-blue-300`.
- Non-terminal columns with `dict.extension.color` use the color as header background (12% alpha) and text (full color).

## E2E hooks

- `[data-testid="kanban-column-header"][data-column-id]` + `[data-column-terminal]`
- `[data-testid="kanban-card"][data-card-id]` + `[data-card-terminal]`
- `[data-testid="card-terminal-icon-won" | "card-terminal-icon-lost"]`
- Card field roots: `[data-field-type="currency" | "avatar" | "progress" | "date-relative"]`
- ProgressField fill: `[data-field-type-bar="progress"]`

## Known follow-ups

- **Race condition**: rapid double-drag of the same card may have rollback overwrite the second optimistic state. Acceptable for current demo scope; abort/seq-token defense planned in a follow-up.
- **i18n on `DateRelativeField`**: labels `today / in Nd / Nd ago` are English fallbacks. Wrap in `LocalizedText` once the demo locale story lands.
- **Hex color shorthand**: `${column.color}20` produces invalid CSS for 3-digit `#abc`. Today all backend fixtures emit 6-digit hex. Add input validation when dict editor UX lands.
