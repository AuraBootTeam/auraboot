---
type: plan
status: implemented
date: 2026-06-25
scope: org-management
---

# Organization Teams DSL CRUD Plan

## Context

`/organization/teams` is currently a hand-written React CRUD page backed by the platform team APIs:

- `GET/POST/PUT/DELETE /api/org/teams`
- `GET/POST/DELETE /api/org/teams/{teamPid}/members`
- platform tables `ab_team` and `ab_team_member`

The team tables are already consumed by platform capabilities such as owner pickers, current-user team resolution, saved views, permissions, and organization workflows. The DSL redesign must not create a parallel dynamic table such as `mt_org_team`.

## Decision

Keep the database schema and storage source of truth unchanged:

- Team source of truth remains `ab_team`.
- Team membership source of truth remains `ab_team_member`.
- Public contracts continue to use `pid`, `teamPid`, `memberPid`, and `userPid` rather than internal numeric ids.

Move the user-facing CRUD surface to a DSL-driven shape:

- `org_team_list`: list page for search, status filters, default ordering, create/edit/delete, and member-management entry.
- `org_team_form`: create/edit form for code, name, description, leader, and status.
- `ab_team_detail`: detail page with team summary and a DSL-mounted `TeamMembersBlock`.
- Team member add/remove is handled by the DSL detail page through a typed custom block that calls `TeamMemberService`, because the relation uses platform-member identity and must avoid JavaScript Long precision loss.

## Implementation Shape

Preferred implementation path:

1. Add a platform-team DSL facade in `org-management` config.
2. Route `/organization/teams` to a DSL-backed page instead of a hand-written React CRUD page.
3. Use existing Team APIs or a minimal platform bridge for persistence.
4. Render member management as a typed DSL custom block inside the DSL detail page.
5. Retain `/organization/teams/:teamPid` as the detail/member-management entry.

The bridge is allowed to call existing platform services. It must not create new team storage tables or duplicate team records into dynamic model tables.

## UX Target

The page should feel like a production organization-admin surface:

- Clean header with concise title and primary create action.
- Dense but readable table with stable default sorting.
- Semantic columns: team name, code, leader, members, status, updated time, actions.
- Status displayed as tags, not raw status codes.
- Icon buttons for row actions with tooltips.
- Clear empty, loading, validation, and permission-denied states.
- No raw field codes, internal ids, `pid`, or `object_record` in visible UI.

## Action Mapping

| User action | Page/block | Backend path | Notes |
| --- | --- | --- | --- |
| View teams | `org_team_list` table | `GET /api/org/teams` | Default sort by name/code; no internal ids shown. |
| Create team | `org_team_form` | `POST /api/org/teams` | Code and name required; code immutable after create. |
| Edit team | `org_team_form` | `PUT /api/org/teams/{pid}` | Name, description, leader, status editable. |
| Delete team | list row action | `DELETE /api/org/teams/{pid}` | Confirm with team name; refresh list. |
| View members | `org_team_detail` | `GET /api/org/teams/{pid}/members` | Member list uses display name/email/role. |
| Add member | detail member block | `POST /api/org/teams/{pid}/members` | Submit `memberPid`; do not submit rounded numeric user ids. |
| Remove member | detail member block | `DELETE /api/org/teams/{pid}/members/{memberPid}` | Confirm with user display name/email. |

## Validation

Before claiming completion:

- Unit/contract tests prove the organization teams route is no longer bound to the old hand-written CRUD page.
- Config/page audit covers the team list/form/detail DSL pages.
- Browser E2E covers menu entry, create, required-field failure, edit/reopen, delete, detail navigation, add member, duplicate filtering, and remove member.
- Backend evidence proves the DSL/bridge writes `ab_team` and `ab_team_member`.
- Permission checks cover write-denied paths for team create/update/delete and member add/remove.

Live browser verification requires an authenticated session. The route is reachable and redirects anonymous access to `/login?redirectTo=%2Forganization%2Fteams`.
