# Workbench Homepage + Top Bar Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the gradient-card / sticker-tile Workbench look with a Stripe-Dashboard-style visual treatment (white cards, sparkline-augmented KPIs, table-driven Tasks, list-style Quick Actions) and polish the global top bar — without touching the sidebar or other pages.

**Architecture:** OSS-only. Workbench `/home` is rendered by `DashboardViewer` consuming a `Dashboard` config from `dashboardService.getWorkbench()`. Restyle the four workbench widgets (`StatsCardWidget`, `StatsRowWidget`, `InboxWidget`, `ShortcutsWidget`), extend the page wrapper with a header band, polish the global `Header.tsx`, and extend the `/api/workbench/stats` DTO with a 7-day series so KPIs can render a sparkline. Component-local Tailwind only — no global token migration this round.

**Tech Stack:** React 19 + TypeScript + Tailwind (web-admin) · Spring Boot + Lombok + Java 21 (platform) · Vitest + React Testing Library · Playwright (OSS E2E) · MyBatis Plus.

**Spec:** [`docs/superpowers/specs/2026-05-28-workbench-redesign-design.md`](../specs/2026-05-28-workbench-redesign-design.md)

**Working directory:** OSS canonical `auraboot/`. Implementer must run via isolated worktree per project rule "不在 canonical 仓主工作树切分支" — see Task 0.

---

## Task 0: Isolated worktree setup

**Files:** none (workspace setup)

- [ ] **Step 1: Create worktree off OSS main**

```bash
cd /Users/ghj/work/auraboot/auraboot
git fetch origin
git worktree add ../auraboot-wt/workbench-redesign -b feat/2026-05-28-workbench-redesign origin/main
cd ../auraboot-wt/workbench-redesign
```

Expected: new directory with a working tree on branch `feat/2026-05-28-workbench-redesign`.

- [ ] **Step 2: Verify clean tree**

```bash
git status
```

Expected: `nothing to commit, working tree clean`.

- [ ] **Step 3: Install deps (frontend only — backend uses Gradle on demand)**

```bash
cd web-admin && pnpm install --frozen-lockfile
```

Expected: `Done in Xs`.

---

## Task 1: Backend — add `Series` to `WorkbenchStatsDTO`

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTO.java`
- Test:   `platform/src/test/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTOTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `platform/src/test/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTOTest.java`:

```java
package com.auraboot.framework.dashboard.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchStatsDTOTest {

    @Test
    void statItem_carriesOptionalSeries() {
        WorkbenchStatsDTO.Series series = WorkbenchStatsDTO.Series.builder()
                .period("day")
                .points(List.of(220, 225, 223, 232, 235, 240, 241))
                .build();

        WorkbenchStatsDTO.StatItem item = WorkbenchStatsDTO.StatItem.builder()
                .value(241)
                .label("workbench.stats.inbox_pending")
                .series(series)
                .build();

        assertThat(item.getSeries()).isNotNull();
        assertThat(item.getSeries().getPeriod()).isEqualTo("day");
        assertThat(item.getSeries().getPoints()).hasSize(7);
        assertThat(item.getSeries().getPoints().get(6)).isEqualTo(241);
    }

    @Test
    void statItem_seriesNullable() {
        WorkbenchStatsDTO.StatItem item = WorkbenchStatsDTO.StatItem.builder()
                .value(0)
                .label("workbench.stats.bpm_running")
                .build();

        assertThat(item.getSeries()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.dto.WorkbenchStatsDTOTest' -i
```

Expected: compile failure — `cannot find symbol: class Series` and `cannot find symbol: method series(...)`.

- [ ] **Step 3: Add `Series` inner class and `series` field**

Edit `platform/src/main/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTO.java`:

```java
package com.auraboot.framework.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkbenchStatsDTO {

    private Map<String, StatItem> stats;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatItem {
        private Object value;
        private String label;
        private String format;
        private Trend trend;
        /** Optional time series (e.g. 7 daily snapshots) for sparkline rendering. Null when no history is available. */
        private Series series;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Trend {
        private String direction;
        private Object value;
        private String period;
        private String unit;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Series {
        /** Granularity: "day" | "week" | "month". This round only emits "day". */
        private String period;
        /** Oldest → newest. For "day"/7 this is 7 entries; numeric only. */
        private List<Number> points;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.dto.WorkbenchStatsDTOTest' -i
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTO.java \
        platform/src/test/java/com/auraboot/framework/dashboard/dto/WorkbenchStatsDTOTest.java
git commit -m "feat(workbench-stats): add optional Series to StatItem for sparkline support"
```

---

## Task 2: Backend — populate 7-day series in `WorkbenchStatsServiceImpl`

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/dashboard/service/impl/WorkbenchStatsServiceImpl.java`
- Test:   `platform/src/test/java/com/auraboot/framework/dashboard/service/impl/WorkbenchStatsServiceImplSeriesTest.java` (create)

> **Implementer note:** Open `WorkbenchStatsServiceImpl.java` before starting. The current impl computes each stat (`inbox_pending`, `crm_opportunity_amount`, `bpm_running`, `crm_account_active`, etc.) by calling a counter method. For each stat, decide whether you have an inexpensive way to compute the daily count for the past 7 days (e.g., `inbox_pending` count grouped by `DATE(created_at)` over `now() - interval '7 day'`). For stats where the historical count is not easily derivable this round (zero-valued or aggregate amounts), set `series` to `null` — the frontend renders the flat-line / no-change state.

- [ ] **Step 1: Write the failing test**

Create `platform/src/test/java/com/auraboot/framework/dashboard/service/impl/WorkbenchStatsServiceImplSeriesTest.java`. Reuse the existing integration-test base (search the dashboard test package for the pattern used by other `WorkbenchStatsServiceImplTest` siblings; if no IT base exists, mirror an existing unit test pattern in the same package):

```java
package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.service.WorkbenchStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WorkbenchStatsServiceImplSeriesTest {

    @Autowired
    private WorkbenchStatsService service;

    @Test
    void getStats_inboxPending_includes7DaySeries() {
        WorkbenchStatsDTO dto = service.getStats(List.of("inbox_pending"));

        WorkbenchStatsDTO.StatItem item = dto.getStats().get("inbox_pending");
        assertThat(item).isNotNull();
        assertThat(item.getSeries()).as("inbox_pending must have a 7-day series").isNotNull();
        assertThat(item.getSeries().getPeriod()).isEqualTo("day");
        assertThat(item.getSeries().getPoints()).hasSize(7);
    }

    @Test
    void getStats_unknownStat_seriesNull() {
        // bpm_running may legitimately have no history; verify the API tolerates null series.
        WorkbenchStatsDTO dto = service.getStats(List.of("bpm_running"));
        WorkbenchStatsDTO.StatItem item = dto.getStats().get("bpm_running");
        assertThat(item).isNotNull();
        // Either real series or null — both acceptable; test ensures no NPE.
        // If series is present, it must have 7 points; if null, that's fine.
        if (item.getSeries() != null) {
            assertThat(item.getSeries().getPoints()).hasSize(7);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.service.impl.WorkbenchStatsServiceImplSeriesTest' -i
```

Expected: `getStats_inboxPending_includes7DaySeries` fails — `item.getSeries()` is null.

- [ ] **Step 3: Implement series population for `inbox_pending` (minimum viable)**

In `WorkbenchStatsServiceImpl.java`, add a helper:

```java
private WorkbenchStatsDTO.Series buildDailySeriesForInbox() {
    // Query inbox_item rows where status='pending' and grouped by DATE(created_at) for the last 7 days.
    // Use the existing InboxMapper or Mapper SQL; if a method does not exist, add one named countByDayLast7Days.
    List<Number> points = inboxMapper.countPendingByDayLast7Days();   // returns oldest → newest
    if (points == null || points.size() != 7) {
        return null;
    }
    return WorkbenchStatsDTO.Series.builder()
            .period("day")
            .points(points)
            .build();
}
```

Then in the method that builds the `inbox_pending` `StatItem`, attach the series via `.series(buildDailySeriesForInbox())`. For other stats this round (`crm_opportunity_amount`, `bpm_running`, `crm_account_active`), do **not** attach a series — leave `series` unset/null. (Add follow-up backlog: extend to remaining stats once each has a cheap historical query.)

If `InboxMapper` lacks `countPendingByDayLast7Days()`, add it. SQL skeleton (Postgres):

```sql
SELECT to_char(d::date, 'YYYY-MM-DD') AS day,
       COALESCE(c.cnt, 0) AS cnt
FROM generate_series(current_date - interval '6 day', current_date, interval '1 day') AS d
LEFT JOIN (
    SELECT DATE(created_at) AS day, COUNT(*) AS cnt
    FROM inbox_item
    WHERE status = 'pending'
      AND created_at >= current_date - interval '6 day'
    GROUP BY DATE(created_at)
) c ON c.day = d::date
ORDER BY d ASC;
```

The mapper method returns the `cnt` column values in order — emit `List<Long>` (Lombok `@Data` lets `List<Number>` accept `Long`).

- [ ] **Step 4: Run test to verify it passes**

```bash
cd platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.service.impl.WorkbenchStatsServiceImplSeriesTest' -i
```

Expected: both tests pass.

- [ ] **Step 5: Run the broader dashboard test class to verify no regression**

```bash
cd platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.*' -i
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/dashboard/service/impl/WorkbenchStatsServiceImpl.java \
        platform/src/main/java/com/auraboot/framework/dashboard/mapper/InboxMapper.java \
        platform/src/main/resources/mapper/dashboard/InboxMapper.xml \
        platform/src/test/java/com/auraboot/framework/dashboard/service/impl/WorkbenchStatsServiceImplSeriesTest.java
git commit -m "feat(workbench-stats): emit 7-day daily series for inbox_pending"
```

(Adjust mapper paths to actual locations — the editor will see them when wiring the SQL.)

---

## Task 3: Frontend — extend `StatItem` type with `series`

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/widgets/workbench/workbench-types.ts`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/workbench-types.test.ts` (create)

- [ ] **Step 1: Write the failing test**

Create the test file:

```ts
import { describe, it, expect } from 'vitest';
import type { StatItem } from '../workbench-types';

describe('StatItem.series', () => {
  it('accepts an optional series with daily points', () => {
    const item: StatItem = {
      value: 241,
      label: 'workbench.stats.inbox_pending',
      series: {
        period: 'day',
        points: [220, 225, 223, 232, 235, 240, 241],
      },
    };
    expect(item.series?.points).toHaveLength(7);
    expect(item.series?.period).toBe('day');
  });

  it('allows series to be omitted', () => {
    const item: StatItem = { value: 0, label: 'workbench.stats.bpm_running' };
    expect(item.series).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/workbench-types.test.ts
```

Expected: TS compile error — `Object literal may only specify known properties, and 'series' does not exist in type 'StatItem'`.

- [ ] **Step 3: Extend the type**

In `workbench-types.ts`, add the `Series` interface and the `series` field on `StatItem`:

```ts
export interface Series {
  period: 'day' | 'week' | 'month';
  points: number[];
}

export interface StatItem {
  value: number | string;
  label: string;
  format?: 'number' | 'currency' | 'percent';
  trend?: {
    direction: 'up' | 'down' | 'flat';
    value: number | string;
    period: 'week' | 'month';
    unit?: 'percent' | 'absolute';
  };
  series?: Series;
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/workbench-types.test.ts
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/workbench-types.ts \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/workbench-types.test.ts
git commit -m "feat(workbench-types): add optional Series for sparkline payload"
```

---

## Task 4: Frontend — create `Sparkline` SVG primitive

**Files:**
- Create: `web-admin/app/plugins/core-dashboard/widgets/workbench/Sparkline.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/Sparkline.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { Sparkline } from '../Sparkline';

describe('Sparkline', () => {
  it('renders a polyline with one point per data entry', () => {
    const { container } = render(
      <Sparkline points={[1, 2, 3, 4, 5, 6, 7]} width={60} height={20} stroke="#635bff" />,
    );
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
    const pts = polyline!.getAttribute('points')!.trim().split(/\s+/);
    expect(pts).toHaveLength(7);
    expect(polyline!.getAttribute('stroke')).toBe('#635bff');
  });

  it('renders a flat baseline line when points is empty or all zero', () => {
    const { container } = render(<Sparkline points={[]} width={60} height={20} />);
    const line = container.querySelector('line');
    expect(line).not.toBeNull();
  });

  it('handles a single-value series by drawing a flat line', () => {
    const { container } = render(<Sparkline points={[5]} width={60} height={20} />);
    // Single point → no slope → render a flat line at mid-height.
    const line = container.querySelector('line');
    expect(line).not.toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/Sparkline.test.tsx
```

Expected: import error — `Cannot find module '../Sparkline'`.

- [ ] **Step 3: Implement `Sparkline`**

Create `web-admin/app/plugins/core-dashboard/widgets/workbench/Sparkline.tsx`:

```tsx
import React from 'react';

export interface SparklineProps {
  points: number[];
  width?: number;
  height?: number;
  stroke?: string;
  className?: string;
}

export function Sparkline({
  points,
  width = 60,
  height = 20,
  stroke = '#635bff',
  className,
}: SparklineProps) {
  if (!points || points.length < 2) {
    return (
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
        <line
          x1={0}
          y1={height - 2}
          x2={width}
          y2={height - 2}
          stroke="#e3e8ee"
          strokeWidth={1.5}
        />
      </svg>
    );
  }

  const min = Math.min(...points);
  const max = Math.max(...points);
  const range = max - min || 1;
  const step = width / (points.length - 1);

  const coords = points
    .map((v, i) => {
      const x = (i * step).toFixed(2);
      const y = (height - 2 - ((v - min) / range) * (height - 4)).toFixed(2);
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
      <polyline points={coords} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/Sparkline.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/Sparkline.tsx \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/Sparkline.test.tsx
git commit -m "feat(workbench): add lightweight Sparkline SVG primitive"
```

---

## Task 5: Frontend — restyle `StatsCardWidget`

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/widgets/workbench/StatsCardWidget.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/StatsCardWidget.test.tsx` (create)

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatsCardWidget } from '../StatsCardWidget';

vi.mock('../useWorkbenchStats', () => ({
  useWorkbenchStats: vi.fn(),
}));
import { useWorkbenchStats } from '../useWorkbenchStats';

const mocked = useWorkbenchStats as unknown as ReturnType<typeof vi.fn>;

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('StatsCardWidget — redesign', () => {
  beforeEach(() => {
    mocked.mockReset();
  });

  it('renders a white card without gradient background classes', () => {
    mocked.mockReturnValue({
      stats: { inbox_pending: { value: 241, label: 'workbench.stats.inbox_pending' } },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="inbox_pending" />);
    const card = container.querySelector('[data-testid="stat-card-inbox_pending"]');
    expect(card).not.toBeNull();
    const cls = card!.className;
    expect(cls).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
    expect(cls).toMatch(/bg-white/);
    expect(cls).toMatch(/border/);
  });

  it('renders a sparkline polyline when series has ≥ 2 points', () => {
    mocked.mockReturnValue({
      stats: {
        inbox_pending: {
          value: 241,
          label: 'workbench.stats.inbox_pending',
          series: { period: 'day', points: [220, 225, 223, 232, 235, 240, 241] },
        },
      },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="inbox_pending" />);
    expect(container.querySelector('polyline')).not.toBeNull();
  });

  it('renders a flat baseline line when series is missing', () => {
    mocked.mockReturnValue({
      stats: { bpm_running: { value: 0, label: 'workbench.stats.bpm_running' } },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="bpm_running" />);
    expect(container.querySelector('polyline')).toBeNull();
    expect(container.querySelector('line')).not.toBeNull();
  });

  it('shows trend text with positive color class when direction is up', () => {
    mocked.mockReturnValue({
      stats: {
        inbox_pending: {
          value: 241,
          label: 'workbench.stats.inbox_pending',
          trend: { direction: 'up', value: 5.2, period: 'week', unit: 'percent' },
        },
      },
      loading: false,
    });
    render(<StatsCardWidget statKey="inbox_pending" />);
    const trend = screen.getByText(/5\.2/);
    expect(trend.className).toMatch(/text-emerald-/);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/StatsCardWidget.test.tsx
```

Expected: all 4 fail (current implementation uses gradient classes, has no `<polyline>`, no `bg-white`).

- [ ] **Step 3: Rewrite `StatsCardWidget.tsx`**

Replace the file body with the new neutral implementation. Drop `GRADIENT_MAP`. Accept the now-unused `gradient` prop with a deprecation comment so existing dashboard JSON still typechecks (no runtime effect). Use `Sparkline` from Task 4.

```tsx
/**
 * StatsCardWidget — Single neutral stat card with optional sparkline.
 *
 * Visual contract (2026-05 redesign):
 *   - White surface with 1px border, no gradient background.
 *   - Label (uppercase, 11px) above large value.
 *   - Sparkline + trend text in the footer row.
 *
 * The `gradient` prop is accepted for backward compatibility with existing
 * Dashboard JSON but no longer has any visual effect.
 *
 * @since 6.5.0  introduced
 * @since 6.6.0  redesigned (gradient removed, sparkline added)
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import { Sparkline } from './Sparkline';
import type { StatItem } from './workbench-types';

const TREND_ARROWS: Record<string, string> = {
  up: '↑',
  down: '↓',
  flat: '—',
};

const TREND_COLOR: Record<string, string> = {
  up: 'text-emerald-700',
  down: 'text-red-700',
  flat: 'text-gray-500',
};

function formatValue(item: StatItem): string {
  const raw = typeof item.value === 'string' ? parseFloat(item.value) : item.value;
  if (item.format === 'currency') {
    if (isNaN(raw as number)) return String(item.value);
    const num = raw as number;
    if (num >= 10000) {
      return `¥${(num / 10000).toFixed(num % 10000 === 0 ? 0 : 1)}万`;
    }
    return `¥${num.toLocaleString()}`;
  }
  if (item.format === 'percent') return `${item.value}%`;
  if (typeof raw === 'number' && !isNaN(raw)) return raw.toLocaleString();
  return String(item.value);
}

function formatTrend(item: StatItem): string {
  if (!item.trend) return '— no change';
  const arrow = TREND_ARROWS[item.trend.direction] ?? '';
  const suffix = item.trend.unit === 'percent' ? '%' : '';
  const periodLabel = item.trend.period === 'week' ? 'vs last week' : 'vs last month';
  return `${arrow} ${item.trend.value}${suffix} ${periodLabel}`;
}

interface StatsCardWidgetProps {
  statKey?: string;
  /** @deprecated kept for dashboard JSON compatibility; has no visual effect since 6.6.0 */
  gradient?: string;
  linkTo?: string;
  className?: string;
}

export function StatsCardWidget({
  statKey = 'inbox_pending',
  linkTo,
  className = '',
}: StatsCardWidgetProps) {
  const { t } = useI18n();
  const { stats, loading } = useWorkbenchStats({ keys: [statKey] });
  const item = stats[statKey];

  const cardBase =
    'flex flex-col gap-3 rounded-[10px] bg-white border border-[#e3e8ee] p-5 min-h-[128px] ' +
    'dark:bg-gray-900 dark:border-gray-700';

  const inner = (
    <>
      <div className="text-[11px] font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
        {item ? t(item.label) : t(`workbench.stats.${statKey}`)}
      </div>
      <div className="text-[28px] leading-none font-semibold text-gray-900 dark:text-gray-100">
        {loading ? '—' : item ? formatValue(item) : '—'}
      </div>
      <div className="mt-auto flex items-center justify-between">
        <span className={`text-[12px] ${TREND_COLOR[item?.trend?.direction ?? 'flat']}`}>
          {item ? formatTrend(item) : ''}
        </span>
        <Sparkline points={item?.series?.points ?? []} />
      </div>
    </>
  );

  const testId = `stat-card-${statKey}`;
  if (linkTo) {
    return (
      <a href={linkTo} data-testid={testId} className={`${cardBase} hover:border-[#cdd5df] transition-colors ${className}`}>
        {inner}
      </a>
    );
  }
  return (
    <div data-testid={testId} className={`${cardBase} ${className}`}>
      {inner}
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/StatsCardWidget.test.tsx
```

Expected: 4 tests pass.

- [ ] **Step 5: Run full Vitest suite for the dashboard plugin to catch unrelated breakage**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard
```

Expected: all green (any pre-existing snapshot mismatches that reference the old gradient classes are acceptable — accept them with `-u` only after confirming the snapshot file only contained gradient-derived class strings).

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/StatsCardWidget.tsx \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/StatsCardWidget.test.tsx
git commit -m "feat(workbench): restyle StatsCardWidget to neutral surface + sparkline"
```

---

## Task 6: Frontend — restyle `StatsRowWidget`

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/widgets/workbench/StatsRowWidget.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/StatsRowWidget.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { StatsRowWidget } from '../StatsRowWidget';

vi.mock('../useWorkbenchStats', () => ({
  useWorkbenchStats: vi.fn(() => ({
    stats: {
      inbox_pending: { value: 241, label: 'workbench.stats.inbox_pending' },
      bpm_running: { value: 0, label: 'workbench.stats.bpm_running' },
      crm_account_active: { value: 107, label: 'workbench.stats.crm_account_active' },
      crm_opportunity_amount: { value: 0, label: 'workbench.stats.crm_opportunity_amount' },
    },
    loading: false,
  })),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('StatsRowWidget — redesign', () => {
  it('renders 4 cards in a CSS grid (not flex) without gradient classes', () => {
    const { container } = render(<StatsRowWidget />);
    const cards = container.querySelectorAll('[data-testid^="stat-card-"]');
    expect(cards).toHaveLength(4);
    cards.forEach((c) => {
      expect(c.className).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
      expect(c.className).toMatch(/bg-white|dark:bg-gray-900/);
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/StatsRowWidget.test.tsx
```

Expected: `expect(c.className).not.toMatch(...)` fails on each card.

- [ ] **Step 3: Rewrite `StatsRowWidget.tsx`**

Replace the render function and drop `GRADIENT_MAP`:

```tsx
/**
 * StatsRowWidget — Renders a row of neutral stat cards for the workbench.
 *
 * Visual contract (2026-05 redesign): white surfaces in a 4-column grid; each
 * card delegates to the same internal renderer as StatsCardWidget for parity.
 */

import React, { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useWorkbenchStats } from './useWorkbenchStats';
import { Sparkline } from './Sparkline';
import type { StatsConfig, StatItem } from './workbench-types';

const TREND_ARROWS: Record<string, string> = { up: '↑', down: '↓', flat: '—' };
const TREND_COLOR: Record<string, string> = {
  up: 'text-emerald-700',
  down: 'text-red-700',
  flat: 'text-gray-500',
};

const DEFAULT_STATS: StatsConfig[] = [
  { key: 'inbox_pending', title: 'workbench.stats.inbox_pending', gradient: 'blue' },
  { key: 'crm_opportunity_amount', title: 'workbench.stats.crm_opportunity_amount', gradient: 'amber' },
  { key: 'bpm_running', title: 'workbench.stats.bpm_running', gradient: 'emerald' },
  { key: 'crm_account_active', title: 'workbench.stats.crm_account_active', gradient: 'violet' },
];

function formatValue(item: StatItem): string {
  const raw = typeof item.value === 'string' ? parseFloat(item.value) : item.value;
  if (item.format === 'currency') {
    if (isNaN(raw as number)) return String(item.value);
    const num = raw as number;
    if (num >= 10000) return `¥${(num / 10000).toFixed(num % 10000 === 0 ? 0 : 1)}万`;
    return `¥${num.toLocaleString()}`;
  }
  if (item.format === 'percent') return `${item.value}%`;
  if (typeof raw === 'number' && !isNaN(raw)) return raw.toLocaleString();
  return String(item.value);
}

function formatTrend(item: StatItem): string {
  if (!item.trend) return '— no change';
  const arrow = TREND_ARROWS[item.trend.direction] ?? '';
  const suffix = item.trend.unit === 'percent' ? '%' : '';
  const periodLabel = item.trend.period === 'week' ? 'vs last week' : 'vs last month';
  return `${arrow} ${item.trend.value}${suffix} ${periodLabel}`;
}

interface StatsRowWidgetProps {
  stats?: StatsConfig[];
  className?: string;
}

export function StatsRowWidget({ stats: statConfigs, className = '' }: StatsRowWidgetProps) {
  const configs = statConfigs ?? DEFAULT_STATS;
  const keys = useMemo(() => configs.map((c) => c.key), [configs]);
  const { stats, loading } = useWorkbenchStats({ keys });
  const { t } = useI18n();

  return (
    <div
      className={`grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 ${className}`}
      data-testid="stats-row"
    >
      {configs.map((cfg) => {
        const item = stats[cfg.key];
        return (
          <div
            key={cfg.key}
            data-testid={`stat-card-${cfg.key}`}
            className="flex flex-col gap-3 rounded-[10px] bg-white border border-[#e3e8ee] p-5 min-h-[128px] dark:bg-gray-900 dark:border-gray-700"
          >
            <div className="text-[11px] font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t(cfg.title)}
            </div>
            <div className="text-[28px] leading-none font-semibold text-gray-900 dark:text-gray-100">
              {loading ? '—' : item ? formatValue(item) : '—'}
            </div>
            <div className="mt-auto flex items-center justify-between">
              <span className={`text-[12px] ${TREND_COLOR[item?.trend?.direction ?? 'flat']}`}>
                {item ? formatTrend(item) : ''}
              </span>
              <Sparkline points={item?.series?.points ?? []} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/StatsRowWidget.test.tsx
```

Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/StatsRowWidget.tsx \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/StatsRowWidget.test.tsx
git commit -m "feat(workbench): restyle StatsRowWidget to neutral grid + sparkline"
```

---

## Task 7: Frontend — restyle `InboxWidget` to table layout

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/widgets/workbench/InboxWidget.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/InboxWidget.test.tsx` (create)

> **Implementer note:** The current component is ~250 LOC of list-rendering, urgent-highlighting, and approve-shortcut logic. Preserve the data loading + approve action wiring; only the rendered markup changes. Remove the avatar column and the priority-driven "urgent left bar"; keep the approve button on approval rows.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { InboxWidget } from '../InboxWidget';

vi.mock('~/shared/services/inboxService', () => ({
  listInboxItems: vi.fn(async () => ({
    total: 2,
    records: [
      { id: 1, title: 'Close Capa', itemType: 'approval', createdAt: new Date().toISOString() },
      { id: 2, title: 'Verify Capa', itemType: 'task', createdAt: new Date().toISOString() },
    ],
  })),
  submitApprovalAction: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('InboxWidget — table redesign', () => {
  it('renders a <table> with the new column headers', async () => {
    const { container, findByRole } = render(<InboxWidget />);
    const table = await findByRole('table');
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim());
    expect(headers).toEqual(expect.arrayContaining(['Task', 'Type', 'Due']));
  });

  it('renders a colored badge for each item type', async () => {
    const { findAllByTestId } = render(<InboxWidget />);
    const badges = await findAllByTestId('inbox-type-badge');
    expect(badges.length).toBeGreaterThan(0);
    expect(badges[0].className).toMatch(/bg-(amber|blue|red)-/);
  });

  it('does not render an avatar column', () => {
    render(<InboxWidget />);
    expect(screen.queryByTestId('inbox-avatar')).toBeNull();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/InboxWidget.test.tsx
```

Expected: no `<table>` rendered → `findByRole('table')` times out and fails.

- [ ] **Step 3: Rewrite the render body**

Open `InboxWidget.tsx` and replace the `return (...)` block. Keep all `useEffect` / state / handlers. The new markup:

```tsx
const TYPE_BADGE: Record<string, string> = {
  approval: 'bg-amber-100 text-amber-800',
  task: 'bg-blue-100 text-blue-800',
  alert: 'bg-red-100 text-red-800',
  mention: 'bg-violet-100 text-violet-800',
  assignment: 'bg-green-100 text-green-800',
  ai_suggestion: 'bg-indigo-100 text-indigo-800',
};

function formatDue(item: InboxItem): string {
  const created = item.createdAt ? new Date(item.createdAt).getTime() : 0;
  if (!created) return '—';
  const diffMs = Date.now() - created;
  const days = Math.floor(diffMs / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return '1d ago';
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  return `${weeks}w ago`;
}

return (
  <div className={`rounded-[10px] bg-white border border-[#e3e8ee] dark:bg-gray-900 dark:border-gray-700 overflow-hidden ${className}`}>
    <div className="flex items-center justify-between px-5 py-4 border-b border-[#f0f3f7] dark:border-gray-700">
      <h2 className="text-[14px] font-semibold text-gray-900 dark:text-gray-100">{resolvedTitle}</h2>
      <a href="/inbox" className="text-[13px] text-[#635bff]">View all →</a>
    </div>

    <div className="flex gap-6 px-5 border-b border-[#f0f3f7] dark:border-gray-700">
      {FILTER_PILLS.map((pill) => (
        <button
          key={pill.key ?? 'all'}
          type="button"
          onClick={() => setActiveFilter(pill.key)}
          className={`py-3 text-[13px] font-medium border-b-2 transition-colors ${
            activeFilter === pill.key
              ? 'border-[#635bff] text-[#635bff]'
              : 'border-transparent text-gray-500 hover:text-gray-900'
          }`}
        >
          {t(pill.labelKey)}
        </button>
      ))}
    </div>

    <table role="table" className="w-full text-[13px]">
      <thead className="bg-[#fafbfc] dark:bg-gray-800">
        <tr>
          <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">Task</th>
          <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">Type</th>
          <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">Due</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr
            key={item.id}
            onClick={() => handleItemClick(item)}
            className="border-t border-[#f0f3f7] dark:border-gray-700 hover:bg-[#fafbfc] dark:hover:bg-gray-800 cursor-pointer"
          >
            <td className="px-5 py-3 font-medium text-gray-900 dark:text-gray-100">{item.title}</td>
            <td className="px-5 py-3">
              <span
                data-testid="inbox-type-badge"
                className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium ${TYPE_BADGE[item.itemType] ?? 'bg-gray-100 text-gray-700'}`}
              >
                {t(`workbench.inbox.${item.itemType}`)}
              </span>
            </td>
            <td className="px-5 py-3 text-gray-500">{formatDue(item)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  </div>
);
```

> If `InboxItem` doesn't yet have `dueLabel`, fall back to relative-formatting `createdAt` with the existing date helper used elsewhere in `web-admin/app/shared/util`. Search for `formatRelative` / `timeAgo` and reuse.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/InboxWidget.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Run InboxWidget existing tests (if any)**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__ -t Inbox
```

Expected: no regression.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/InboxWidget.tsx \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/InboxWidget.test.tsx
git commit -m "feat(workbench): restyle InboxWidget to table layout with type badges"
```

---

## Task 8: Frontend — restyle `ShortcutsWidget` to list layout

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/widgets/workbench/ShortcutsWidget.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/ShortcutsWidget.test.tsx` (create)

> **Implementer note:** Keep the existing favorites loading, drag-reorder edit mode, and add-favorite modal wiring. Only the visual treatment of each shortcut row and the absence of pastel `bg-*-50` tile colors changes. The legacy `color` field on `ShortcutItem` becomes unused but is kept for type compatibility.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ShortcutsWidget } from '../ShortcutsWidget';

vi.mock('~/shared/services/engagementService', () => ({
  listFavorites: vi.fn(async () => []),
  removeFavorite: vi.fn(),
  reorderFavorites: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('ShortcutsWidget — redesign', () => {
  it('renders items in a vertical list (not a grid of tiles)', async () => {
    const { findByTestId } = render(<ShortcutsWidget />);
    const list = await findByTestId('shortcuts-list');
    expect(list.tagName.toLowerCase()).toBe('ul');
  });

  it('does not apply pastel tile backgrounds (bg-*-50) to rows', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const rows = await findAllByTestId('shortcut-row');
    rows.forEach((row) => {
      expect(row.className).not.toMatch(/bg-(blue|green|amber|violet|orange|indigo|rose)-50/);
    });
  });

  it('renders an icon tile and a chevron per row', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const rows = await findAllByTestId('shortcut-row');
    expect(rows.length).toBeGreaterThan(0);
    rows.forEach((row) => {
      expect(row.querySelector('[data-testid="shortcut-icon"]')).not.toBeNull();
      expect(row.textContent).toContain('›');
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/ShortcutsWidget.test.tsx
```

Expected: no `<ul data-testid="shortcuts-list">` rendered.

- [ ] **Step 3: Rewrite the render output**

In `ShortcutsWidget.tsx`, replace the render block (keep all hooks). The new structure:

```tsx
return (
  <div className={`rounded-[10px] bg-white border border-[#e3e8ee] dark:bg-gray-900 dark:border-gray-700 ${className}`}>
    <div className="flex items-center justify-between px-5 py-4 border-b border-[#f0f3f7] dark:border-gray-700">
      <h2 className="text-[14px] font-semibold text-gray-900 dark:text-gray-100">{displayTitle}</h2>
      <button
        type="button"
        onClick={toggleEditing}
        className="text-[12px] text-gray-500 hover:text-gray-900"
      >
        {editing ? t(I18N_KEYS.done) : t(I18N_KEYS.edit)}
      </button>
    </div>

    <ul data-testid="shortcuts-list" className="py-2">
      {items.map((item, idx) => (
        <li key={`${item.label}-${idx}`}>
          <a
            href={item.path}
            data-testid="shortcut-row"
            className="flex items-center gap-3 px-5 py-2.5 hover:bg-[#fafbfc] dark:hover:bg-gray-800 transition-colors"
          >
            <div
              data-testid="shortcut-icon"
              className="w-8 h-8 rounded-lg bg-[#f0f3f7] dark:bg-gray-800 flex items-center justify-center text-[#635bff] text-[14px] font-semibold flex-shrink-0"
            >
              {item.icon}
            </div>
            <span className="flex-1 text-[13px] font-medium text-gray-900 dark:text-gray-100">
              {item.label}
            </span>
            <span className="text-gray-400">›</span>
            {editing && item.engagementId != null && (
              <button
                type="button"
                onClick={(e) => handleRemove(item.engagementId!, e)}
                className="ml-2 text-[11px] text-red-500 hover:text-red-700"
              >
                ×
              </button>
            )}
          </a>
        </li>
      ))}
    </ul>

    {/* Existing AddFavoriteModal wiring stays unchanged */}
    <AddFavoriteModal open={modalOpen} onClose={() => setModalOpen(false)} onAdded={loadFavorites} />
  </div>
);
```

Leave the existing drag-reorder logic in place — when `editing` is true, the drop targets still work; the new row markup retains the `onDragStart` / `onDragOver` / `onDrop` handlers (preserve them while editing the render block).

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/widgets/workbench/__tests__/ShortcutsWidget.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/ShortcutsWidget.tsx \
        web-admin/app/plugins/core-dashboard/widgets/workbench/__tests__/ShortcutsWidget.test.tsx
git commit -m "feat(workbench): restyle ShortcutsWidget to vertical list with neutral icon tiles"
```

---

## Task 9: Frontend — Workbench page header band

**Files:**
- Modify: `web-admin/app/plugins/core-dashboard/pages/home/index.tsx`
- Test:   `web-admin/app/plugins/core-dashboard/pages/home/__tests__/index.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import WorkbenchPage from '../index';

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: {
    getWorkbench: vi.fn(async () => ({ id: 'wb', widgets: [] })),
  },
}));

vi.mock('~/plugins/core-dashboard/components/DashboardViewer', () => ({
  DashboardViewer: () => <div data-testid="dashboard-viewer" />,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('WorkbenchPage header', () => {
  it('renders a page title and dated subline', async () => {
    render(<WorkbenchPage />);
    expect(await screen.findByRole('heading', { name: /workbench/i })).toBeInTheDocument();
    expect(screen.getByTestId('workbench-subline').textContent).toMatch(/\d{4}/);
  });

  it('renders an Export and a + New action in the header', async () => {
    render(<WorkbenchPage />);
    expect(await screen.findByRole('button', { name: /export/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /new/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/pages/home/__tests__/index.test.tsx
```

Expected: no `<h1>` / no Export button.

- [ ] **Step 3: Add header band to `pages/home/index.tsx`**

Wrap the existing `<DashboardViewer />` render in a fragment with the new header:

```tsx
function todayLabel(): string {
  return new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' });
}

// ... inside WorkbenchPage, after loading/error guards, replace the final return:
return (
  <div className="px-8 py-6 bg-[#fafbfc] dark:bg-gray-900 min-h-full">
    <header className="flex items-end justify-between mb-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight text-gray-900 dark:text-gray-100">
          {t('workbench.title')}
        </h1>
        <div data-testid="workbench-subline" className="text-[13px] text-gray-500 mt-1">
          {todayLabel()} · {t('workbench.subline')}
        </div>
      </div>
      <div className="flex gap-2">
        <button type="button" className="px-3.5 py-2 rounded-md border border-[#e3e8ee] bg-white text-[13px] font-medium text-gray-900 hover:border-[#cdd5df]">
          {t('workbench.export')}
        </button>
        <button type="button" className="px-3.5 py-2 rounded-md bg-[#635bff] text-[13px] font-medium text-white hover:bg-[#534eeb]">
          + {t('workbench.new')}
        </button>
      </div>
    </header>

    <DashboardViewer dashboard={dashboard} onReload={loadWorkbench} />
  </div>
);
```

If `DashboardViewer`'s actual prop shape differs from `{ dashboard, onReload }`, mirror what the current `pages/home/index.tsx` is already doing — only wrap, don't change the inner contract.

> **i18n keys to add** (en + zh-CN):
>
> - `workbench.title` → `Workbench` / `工作台`
> - `workbench.subline` → `Overview` / `今日概览`
> - `workbench.export` → `Export` / `导出`
> - `workbench.new` → `New` / `新建`
>
> Add them to the locale files at `web-admin/app/locales/en-US.json` and `web-admin/app/locales/zh-CN.json` (or wherever the existing `workbench.*` keys live — grep first).

- [ ] **Step 4: Run test to verify it passes**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard/pages/home/__tests__/index.test.tsx
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/plugins/core-dashboard/pages/home/index.tsx \
        web-admin/app/plugins/core-dashboard/pages/home/__tests__/index.test.tsx \
        web-admin/app/locales/en-US.json \
        web-admin/app/locales/zh-CN.json
git commit -m "feat(workbench): add page header band with title, subline, and actions"
```

---

## Task 10: Frontend — top bar polish

**Files:**
- Modify: `web-admin/app/routes/Header.tsx`
- Modify: `web-admin/app/routes/AdminLayout.tsx` (change `pt-16` → `pt-14` for the new 56px header)
- Test:   `web-admin/app/routes/__tests__/Header.test.tsx` (create or extend)

> **Implementer note:** Header.tsx is 407 LOC. Don't rewrite — surgically adjust: header height class, search box width + style, icon button size, notification badge color, avatar size + border, add a vertical divider before the avatar. Replace the "AuraBoot · AuraBoot Dev" dotted line with logo + grey "Dev" chip.

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import Header from '../Header';

// Mock the heavy dependencies; we only assert on layout/structure.
vi.mock('~/root', () => ({ useRootLoaderData: () => ({ user: { username: 'cat' } }) }));
vi.mock('~/contexts/ThemeContext', () => ({ useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (k: string) => k, lang: 'en-US', setLang: vi.fn() }) }));
vi.mock('~/hooks/useHydrated', () => ({ useHydrated: () => true }));
vi.mock('~/hooks/useSSE', () => ({ useSSE: () => null }));
vi.mock('~/ui/inbox/InboxDropdown', () => ({ InboxHeaderWidget: () => <button aria-label="notifications">99</button> }));
vi.mock('~/ui/CommandPalette', () => ({ CommandPalette: () => null }));
vi.mock('~/plugins/core-aurabot/components-shell/AuraBotProvider', () => ({ useAuraBot: () => ({ state: { panelState: 'closed' }, toggle: vi.fn() }) }));

describe('Header — polish', () => {
  it('renders with h-14 (56px), not h-16', () => {
    const { container } = render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const header = container.querySelector('header');
    expect(header).not.toBeNull();
    expect(header!.className).toMatch(/\bh-14\b/);
    expect(header!.className).not.toMatch(/\bh-16\b/);
  });

  it('renders the search trigger with a fixed 360px width', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const search = screen.getByTestId('header-search-trigger');
    expect(search.className).toMatch(/w-\[360px\]/);
  });

  it('renders an env chip with "Dev" instead of a dot separator', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('header-env-chip').textContent?.trim()).toBe('Dev');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd web-admin && pnpm vitest run app/routes/__tests__/Header.test.tsx
```

Expected: all 3 fail.

- [ ] **Step 3: Apply the polish edits**

In `Header.tsx`:

1. Change the root `<header className="... h-16 ...">` → `h-14`. Also update any sticky-top spacing.
2. The brand row: replace `<span>·</span><span>AuraBoot Dev</span>` with:
   ```tsx
   <span data-testid="header-env-chip" className="ml-2 px-1.5 py-0.5 text-[11px] font-medium text-gray-500 bg-[#f6f9fc] rounded">Dev</span>
   ```
3. The search trigger button (currently styled to fill the middle): give it `data-testid="header-search-trigger"` and replace its width classes with `w-[360px]`. Keep its existing styling otherwise but change the background to `bg-white border border-[#e3e8ee] rounded-md` and tighten height to `h-[34px]`.
4. The right-side icon buttons: change size class to `w-8 h-8` (32px) from whatever 36px equivalent is currently in use; tighten `gap-` to `gap-1`.
5. The notification count badge inside `InboxHeaderWidget` is provided by that component — we don't restyle it here; instead, raise an item for the `InboxHeaderWidget` repo follow-up in Task 14 below.
6. Insert a `<span className="mx-1.5 w-px h-5 bg-[#e3e8ee]" aria-hidden />` before the avatar group.
7. Avatar: change its outer wrapper to `w-[30px] h-[30px]` with `border border-[#e3e8ee]`.

In `AdminLayout.tsx` line 57: change `pt-16` → `pt-14`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd web-admin && pnpm vitest run app/routes/__tests__/Header.test.tsx
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-admin/app/routes/Header.tsx \
        web-admin/app/routes/AdminLayout.tsx \
        web-admin/app/routes/__tests__/Header.test.tsx
git commit -m "feat(layout): polish top bar — 56px height, fixed search, Dev chip, divider"
```

---

## Task 11: Frontend — drop gradient overrides in seeded workbench dashboard JSON

**Files:**
- Modify: the seed JSON (locate during implementation — search for `"gradient":` inside `web-admin/app/plugins/core-dashboard/` and inside `platform/src/main/resources/` for the seed file)

- [ ] **Step 1: Locate the seed**

```bash
grep -rn '"gradient":' web-admin/app/plugins/core-dashboard/ platform/src/main/resources/ 2>/dev/null
```

Record the file path(s) returned.

- [ ] **Step 2: Remove `gradient` overrides from each seed entry**

Edit each match: delete the `"gradient": "..."` key (and its trailing comma if needed). The widget code already tolerates absence. Do **not** delete the seed entries themselves — just the obsolete styling field.

- [ ] **Step 3: Run web-admin tests to ensure nothing breaks**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard
```

Expected: green.

- [ ] **Step 4: Commit**

```bash
git add <files from step 1>
git commit -m "chore(workbench-seed): drop legacy gradient overrides"
```

---

## Task 12: E2E — Workbench visual smoke

**Files:**
- Create: `e2e/specs/workbench/workbench-redesign.spec.ts`

> **Implementer note:** Confirm the actual e2e folder layout (`auraboot/e2e/specs/...` or similar) before writing — adapt the path. Reuse the existing OSS auth setup project.

- [ ] **Step 1: Write the spec**

```ts
import { test, expect } from '@playwright/test';

test.describe('Workbench redesign smoke', () => {
  test('home page renders with new KPI cards, table tasks, list shortcuts', async ({ page }) => {
    await page.goto('/home');

    // Title band
    await expect(page.getByRole('heading', { name: /workbench/i })).toBeVisible();

    // 4 KPI cards present, none with gradient classes
    const cards = page.locator('[data-testid^="stat-card-"]');
    await expect(cards).toHaveCount(4);
    for (const card of await cards.all()) {
      const cls = (await card.getAttribute('class')) ?? '';
      expect(cls).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
    }

    // Inbox table headers
    await expect(page.getByRole('columnheader', { name: 'Task' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Type' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Due' })).toBeVisible();

    // Shortcuts list
    await expect(page.getByTestId('shortcuts-list')).toBeVisible();

    // Top bar polish
    const header = page.locator('header').first();
    await expect(header).toHaveClass(/h-14/);
    await expect(page.getByTestId('header-env-chip')).toHaveText('Dev');
  });
});
```

- [ ] **Step 2: Run the spec locally (against host backend)**

```bash
cd web-admin && pnpm exec playwright test e2e/specs/workbench/workbench-redesign.spec.ts --reporter=list
```

Expected: 1 pass.

- [ ] **Step 3: Commit**

```bash
git add e2e/specs/workbench/workbench-redesign.spec.ts
git commit -m "test(e2e): smoke for workbench redesign — KPI cards, inbox table, shortcuts list, topbar"
```

---

## Task 13: Manual visual verification (light + dark)

**Files:** none (verification step)

- [ ] **Step 1: Run dev stack**

```bash
cd web-admin && pnpm dev:full
```

Wait for the Vite frontend on the printed port (typically 3000-ish) and the backend on 8080.

- [ ] **Step 2: Open `/home` in a browser**

Expected (light): white KPI cards with brand-purple sparkline; tasks rendered as a table with type badges; shortcuts as a clean list; top bar 56px tall, search box ~360px wide, "Dev" chip next to logo.

- [ ] **Step 3: Toggle theme to dark, reload `/home`**

Expected (dark): same structure, dark surfaces, no leftover light-mode classes visible. If anything looks broken, fix the offending `dark:` Tailwind class in the widget.

- [ ] **Step 4: Save screenshots**

```bash
mkdir -p docs/superpowers/screenshots/2026-05-28-workbench-redesign
# Use the system screenshot tool to save:
#   workbench-light.png, workbench-dark.png, topbar-detail.png
```

- [ ] **Step 5: Commit the screenshots if dark-mode adjustments were needed**

```bash
git add web-admin/app/plugins/core-dashboard/widgets/workbench/*.tsx \
        docs/superpowers/screenshots/2026-05-28-workbench-redesign/
git commit -m "fix(workbench): dark-mode adjustments after visual review"
```

(Skip the commit if no code changes are needed.)

---

## Task 14: Enterprise overlay parity audit (non-blocking)

**Files:** none (audit + backlog entry only)

- [ ] **Step 1: Grep for overrides of the restyled widgets in the enterprise overlay**

```bash
grep -rn "StatsCardWidget\|StatsRowWidget\|InboxWidget\|ShortcutsWidget" \
  /Users/ghj/work/auraboot/auraboot-enterprise/web-admin-ext/ 2>/dev/null
```

- [ ] **Step 2: If matches exist, file a backlog entry**

Create `auraboot-enterprise/docs/backlog/2026-05-28-workbench-redesign-enterprise-parity.md` listing the matched files and noting that they need the same restyle. **Do not** edit enterprise overlay code in this PR — that's a separate effort.

- [ ] **Step 3: Commit only the backlog file if created**

```bash
cd /Users/ghj/work/auraboot/auraboot-enterprise
git add docs/backlog/2026-05-28-workbench-redesign-enterprise-parity.md
git commit -m "docs(backlog): track enterprise overlay parity for workbench redesign"
```

(If no overrides exist, document that in the PR description.)

---

## Task 15: Final verification and PR

**Files:** none (verification + PR)

- [ ] **Step 1: Run all touched test suites**

```bash
cd web-admin && pnpm vitest run app/plugins/core-dashboard app/routes
cd ../platform && ./gradlew test --tests 'com.auraboot.framework.dashboard.*' -i
```

Expected: all green.

- [ ] **Step 2: Type-check the frontend**

```bash
cd web-admin && pnpm tsc --noEmit
```

Expected: no new errors versus main.

- [ ] **Step 3: Push the branch and open a PR**

```bash
cd /Users/ghj/work/auraboot/auraboot-wt/workbench-redesign
git push -u origin feat/2026-05-28-workbench-redesign
gh pr create --base main \
  --title "feat(workbench): redesign Workbench home + top bar polish" \
  --body "$(cat <<'EOF'
## Summary
- Restyles the Workbench `/home` widgets (StatsCard, StatsRow, Inbox, Shortcuts) from gradient/sticker visuals to a Stripe-Dashboard-style neutral treatment.
- Extends `/api/workbench/stats` `StatItem` with an optional 7-day `Series` powering KPI sparklines (populated for `inbox_pending` this round).
- Adds a page header band (title + dated subline + Export / + New).
- Polishes the global top bar: 56px height, 360px fixed search, `Dev` chip, brand-purple notification badge, 30px avatar with border, vertical divider.

## Out of scope
- Global design-token system (a future Option-C spec) — kept component-local Tailwind only.
- Sidebar redesign.
- Enterprise overlay parity — tracked separately in `auraboot-enterprise/docs/backlog/2026-05-28-workbench-redesign-enterprise-parity.md` if applicable.
- Tasks priority column (rejected pending inbox data contract).

## Test plan
- [ ] Vitest: `pnpm vitest run app/plugins/core-dashboard app/routes` green
- [ ] Gradle: `./gradlew test --tests 'com.auraboot.framework.dashboard.*'` green
- [ ] Playwright OSS smoke: `workbench-redesign.spec.ts` passes
- [ ] Manual visual review in light + dark mode (screenshots in `docs/superpowers/screenshots/2026-05-28-workbench-redesign/`)

Spec: `docs/superpowers/specs/2026-05-28-workbench-redesign-design.md`
Plan: `docs/superpowers/plans/2026-05-28-workbench-redesign.md`
EOF
)"
```

- [ ] **Step 4: Update tasks for this plan to completed**

Use TaskUpdate to mark plan-level tracking task as completed once the PR is open.

---

## Notes for the implementer

- **Don't rewrite Header.tsx** — surgical edits only. The file is 407 LOC of working code; the redesign touches ~10-15 distinct lines.
- **Don't break the gradient prop on widget consumers** — accept it as a no-op so existing dashboard JSON keeps parsing.
- **Don't introduce a global token system** — the spec explicitly defers that. Keep Tailwind utility values inline.
- **Verify dark-mode classes** — every new `bg-*` / `border-*` / `text-*` should have a paired `dark:` variant. Search for any class that's light-only after each task and add the variant before committing.
- **i18n** — every user-visible string must go through `useI18n().t(...)`. No raw `Workbench`, no raw `Overview`, no raw `Export` in JSX outside of the locale files.
- **Test running speed** — use `pnpm vitest run <path>` (no watch) for plan steps; CI mode is faster and produces a single deterministic pass/fail.
