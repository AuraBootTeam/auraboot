# README Screenshots — Capture Specification

> 4 screenshot slots in README.md are marked `<!-- TODO: Add screenshot ... -->`.
> This doc tells the operator (founder / designer / community PM) exactly what
> each screenshot should show, the recommended frame size, and what to avoid.
>
> Quality of these 4 images materially affects star conversion. They are usually
> the FIRST visual impression a visitor has — before reading a single sentence.

## Master rules

- **Aspect ratio**: 16:9 (1280×720) or 16:10 (1280×800). Letterboxes look sloppy.
- **OS chrome**: hide or crop. Just the AuraBoot UI, no Mac traffic lights / Windows title bars.
- **Cursor**: hidden. Mouse pointer in screenshots looks unprofessional.
- **Browser dev tools**: closed.
- **Mock data**: realistic. Not "test_user_1" / "asdf" / "lorem ipsum". Use English names + plausible data (Acme Corp, Q3 2026 figures, etc.).
- **Time / date**: pick one. Don't mix locales — use one date format consistently across all 4.
- **Theme**: use light theme by default for screenshots; some users hate dark mode in marketing assets. (We can do dark variants later.)
- **File format**: PNG with transparent edges OK; JPEG only if file size > 200KB.
- **Compression**: pass through `imgoptim` / `tinypng` — < 200KB target each.
- **Place at**: `docs/assets/screenshots/<slot>.png` and reference from README.

## Slot 1 — Main dashboard (`docs/assets/screenshots/dashboard.png`)

**Goal**: Convey "this is a full business app, not a toy."

**Frame**:
- Sidebar: visible with a healthy menu structure (CRM, Workflow, Reports, Admin, AI Assistant)
- Top bar: tenant switcher + user avatar + AuraBot icon visible
- Main pane: dashboard with **2-3 widgets**:
  - One KPI card ("Open Leads: 124, +12% MoM")
  - One bar chart ("Top 5 products by revenue")
  - One list/table ("Recent purchase orders awaiting approval")
- Empty state: avoid. Make sure there's enough mock data to look lived-in.

**Don't**:
- Show only 1 widget (looks bare)
- Show calendar of empty days
- Show a single hardcoded "Welcome!" splash

## Slot 2 — Page Designer (`docs/assets/screenshots/page-designer.png`)

**Goal**: "You can drag-and-drop without dropping into code."

**Frame**:
- Left rail: block palette (Form / Table / Chart / Card / Custom)
- Center canvas: a mid-build customer-detail page with **at least 3 visible blocks** (header, form, related-records table)
- Right rail: the property panel for the SELECTED block, showing real properties (data binding, label, validation)
- Top toolbar: Save / Preview / Publish buttons visible

**Don't**:
- Show a blank canvas (defeats the point — show progress)
- Show every property collapsed (right rail should be expanded)

## Slot 3 — Command Pipeline (`docs/assets/screenshots/command-pipeline.png`)

**Goal**: "Look how configurable the data path is. This is the moat."

**Frame**:
- DSL editor showing a real `commands/<name>.json` file with 20-25 lines
- The file should demonstrate: schema validate stage + permission stage + state machine + handler reference + side effect declaration
- Optional: show the running pipeline visualization (the Pipeline Inspector view, if it's polished enough) split-screen with the DSL

**Don't**:
- Show only a 5-line trivial command — that's misleading easy
- Show a syntax error / validation marker
- Show comments-only file

**Alternative framing**: instead of a code editor, show the **Pipeline Inspector** UI with the 20 stages on a horizontal swimlane, current request highlighted, per-stage timing badges. This is more visual and reinforces the differentiation.

## Slot 4 — AI Copilot (`docs/assets/screenshots/aurabot.png`)

**Goal**: "AI is real and useful, not a marketing add-on."

**Frame**:
- Right-side chat panel pinned to the main app
- Conversation showing a 2-turn realistic exchange:
  - User: "Show me Q3 revenue by region as a bar chart"
  - AuraBot: a chart appears in the response (or "Here's the chart you asked for" with the chart inline)
  - Optional second turn: "Drill into APAC" → filtered chart
- Chart should have realistic numbers and matching axis labels
- The user's main app behind the panel should also be visible (so it's clear this is in-app, not standalone)

**Don't**:
- Show "Hello, I'm AuraBot, how can I help you today?" generic intro screen — no information value
- Show ChatGPT-clone bubble UI without the integration angle
- Show Markdown source code instead of rendered chart

## Optional: 5th screenshot (BPMN designer)

If we want a 5th later, BPMN designer is the strongest candidate:
- A real approval flow (e.g., expense report) with 6-10 nodes, 2 lanes, 1 gateway
- Annotation showing "human task" / "service task" / "timer" nodes color-coded

## Order in README

The 4 TODOs in README.md appear in this order: dashboard → page designer → command pipeline → copilot. **Capture them in that order** so the README story flows: "look at this app → look at how you build it → look at the engine under it → look at the AI that makes it different".

## Capture workflow recommendation

1. Start AuraBoot via `docker compose up -d`
2. Login as admin
3. Install crm-quick-start + simple-inventory templates so screens have data
4. Use Cleanshot X (Mac) or ShareX (Windows) for window-only capture
5. For each slot, hide cursor, take 3-5 variants, pick the one with the best layout
6. Drop into `docs/assets/screenshots/`
7. Replace each `<!-- TODO: Add screenshot -->` line with `![<alt>](docs/assets/screenshots/<file>.png)`

## When you're done

- [ ] All 4 TODOs in README.md replaced with actual `![...]` markdown
- [ ] Each image < 200KB
- [ ] Each image renders cleanly on github.com (open the PR preview)
- [ ] Each image renders on docs.auraboot.com (after `sync-docs-to-website.sh --apply`)
- [ ] Mobile preview (narrow viewport) doesn't catastrophically break layout
- [ ] No PII / customer names / real internal data in any screenshot
