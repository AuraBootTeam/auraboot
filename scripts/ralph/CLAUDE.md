# Ralph × Superpowers — AuraMeta Autonomous Agent Instructions

You are an autonomous coding agent working on the AuraMeta platform.
You follow the **Ralph loop** (one story per iteration) with **Superpowers discipline** (TDD, systematic debugging, verification).

> **IMPORTANT**: You are also bound by the project root `CLAUDE.md` rules.
> All AuraMeta coding conventions, pitfalls, and constraints defined there apply here.
> Do NOT violate DSL-first, schema-first, or NO_PROXY rules.

---

## File Locations (Absolute Paths)

- **PRD**: `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/prd.json`
- **Progress Log**: `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/progress.txt`
- **Project Root**: `/Users/ghj/work/startup/phenix/AuraMeta`
- **Backend**: `/Users/ghj/work/startup/phenix/AuraMeta/platform`
- **Frontend**: `/Users/ghj/work/startup/phenix/AuraMeta/web-admin`
- **Plugins**: `/Users/ghj/work/startup/phenix/AuraMeta/plugins`
- **System Docs**: `/Users/ghj/work/startup/phenix/AuraMeta/docs/system-reference`

---

## Iteration Workflow

```
1. Orient    → Read prd.json + progress.txt + codebase context
2. Plan      → Understand the story, identify files to change
3. RED       → Write failing test(s)
4. GREEN     → Write minimum code to pass
5. REFACTOR  → Clean up while staying green
6. Verify    → Run ALL quality checks, read output
7. Review    → Self-review against acceptance criteria
8. Commit    → Git commit + update prd.json + log progress
9. Complete? → All done? → <promise>COMPLETE</promise>
```

---

## Step 1: Orient

1. Read `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/prd.json`
   — Find the first user story where `passes: false` (lowest `priority` number first)
2. Read `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/progress.txt`
   — Check the **Codebase Patterns** section at the top FIRST
3. Verify you're on the correct git branch matching `branchName` in prd.json.
   If not, check it out or create from `phenix` branch.
4. If ALL stories have `passes: true` → output `<promise>COMPLETE</promise>` and stop immediately.

## Step 2: Plan (Think Before Coding)

Before writing ANY code:

1. **Read relevant source files** — understand existing patterns, don't guess
2. **Check table schemas** before writing SQL (project rule — mandatory):
   ```bash
   psql -h localhost -U ghj -d aura_boot -P pager=off -c "\d table_name"
   ```
3. **Check DTO/Schema** before writing plugin JSON (project rule — mandatory):
   - `plugins/schemas/plugin-manifest.schema.json`
   - `docs/system-reference/core/06-Command系统.md`
4. **Identify which files to create/modify** — list them before starting
5. **Check if DSL config can solve it** before writing tsx code (DSL-first principle)

## Step 3: RED — Write Failing Test First

> ⚠️ **IRON LAW: No production code without a failing test first.**
> If you write code before a test, DELETE IT and start over.

For **backend stories**:
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/platform
./gradlew test --tests "com.auraboot.*.YourTestClass" --info
```

For **frontend stories** (unit tests):
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/web-admin
npx vitest run path/to/test.test.ts
```

For **E2E stories** (requires running services):
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/web-admin
NO_PROXY=localhost npx playwright test path/to/test.spec.ts
```

**What makes a good failing test:**
- Clear name describing expected behavior: `test('should return 404 when model not found')`
- Tests ONE thing
- Tests real behavior, not mock behavior
- Fails with a meaningful error message (not compilation error)

**E2E test rules** (from project CLAUDE.md):
- MUST use UI interactions (page.goto, page.click, page.fill) — NOT API calls
- MUST add `NO_PROXY=localhost` to all Playwright/curl commands
- API calls allowed only for setup/teardown, not core test actions

**When TDD doesn't apply:**
- DSL-only stories (plugin JSON config, page designer config) — no test needed for JSON files
- Documentation-only stories
- For these, skip directly to Step 4

## Step 4: GREEN — Minimum Code to Pass

Write the **smallest amount of code** that makes the test pass:

- No extra features, no over-engineering
- Follow existing code patterns in the codebase
- Run the test again → confirm it PASSES

> All AuraMeta-specific coding rules (MyBatis, soft-delete, tenant isolation, DSL-first, etc.)
> are defined in the project root CLAUDE.md. Follow them.

## Step 5: REFACTOR — Clean Up While Green

- Remove duplication, improve names
- Run ALL tests again → confirm still green
- Keep changes minimal and focused

## Step 6: Verify (Evidence Before Claims)

Run the full quality gate. **Read the actual output** — do not assume success.

**Backend** (if backend files changed):
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/platform
./gradlew compileJava 2>&1 | tail -20
./gradlew test --info 2>&1 | tail -40
```

**Frontend** (if frontend files changed):
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/web-admin
npm run typecheck 2>&1 | tail -20
npx vitest run 2>&1 | tail -20
```

**E2E** (only if story involves UI AND services are running):
```bash
cd /Users/ghj/work/startup/phenix/AuraMeta/web-admin
NO_PROXY=localhost npx playwright test --grep "relevant-test" 2>&1 | tail -40
```

⚠️ **If any check fails → go to Step 6b. Do NOT commit broken code.**

### Step 6b: Systematic Debugging

> ⚠️ **IRON LAW: No fixes without root cause investigation first.**

1. **Read the error message carefully** — what exactly failed and where?
2. **Reproduce consistently** — can you trigger it reliably?
3. **Check recent changes** — `git diff` to see what you changed
4. **Gather evidence** — add logging, check DB state, read stack traces
5. **Only THEN propose a fix** — based on evidence, not guessing

Do NOT randomly try fixes, suppress errors, or mask symptoms.

## Step 7: Self-Review

Before committing, verify against the story's acceptance criteria:

- [ ] Every acceptance criterion is met (check each one explicitly)
- [ ] Nothing extra added beyond what the story requires
- [ ] All tests pass and test real behavior
- [ ] No security vulnerabilities introduced
- [ ] Code follows existing codebase patterns
- [ ] Commits use English (project language policy)

## Step 8: Commit & Update

### 8a. Git Commit
```bash
git add <specific-files>  # NOT git add -A
git commit -m "feat: [US-XXX] - Story Title"
```

### 8b. Update prd.json
Edit `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/prd.json`:
Set `passes: true` for the completed story. Update `notes` if relevant.

### 8c. Append to progress.txt
APPEND (never replace) to `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/progress.txt`:

```
## [Date] - [Story ID]: [Story Title]
- What was implemented
- Files changed
- **Learnings for future iterations:**
  - Patterns discovered
  - Gotchas encountered
  - Useful context for next stories
---
```

### 8d. Consolidate Patterns
If you discovered a **reusable pattern**, add it to the `## Codebase Patterns` section at the TOP of progress.txt.

## Step 9: Completion Check

After committing, re-read `/Users/ghj/work/startup/phenix/AuraMeta/scripts/ralph/prd.json`.

- **ALL stories have `passes: true`** → output `<promise>COMPLETE</promise>`
- **More stories remain** → end your response normally (next iteration picks up)

---

## Important Reminders

- Work on **ONE story** per iteration — do not attempt multiple stories
- **TDD is mandatory** for code stories — RED → GREEN → REFACTOR
- **Verify before claiming success** — read actual command output
- **Debug systematically** — root cause first, then fix
- Keep changes **focused and minimal**
- Read **Codebase Patterns** in progress.txt before starting
- Follow **existing code patterns** in the codebase
- All coding conventions from the project root CLAUDE.md apply
