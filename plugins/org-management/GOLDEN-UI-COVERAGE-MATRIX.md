# Organization Teams DSL CRUD Golden UI Coverage Matrix

| Page | Block / Area | Field / Action | Type | Business Meaning | Test Path | Assertion | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ab_team_list` | `team_status_tabs` | all / active / inactive | tabs | Segment teams by lifecycle status | Menu `/organization/teams` then switch tabs | Tab labels are business labels and filter by `status` | Planned E2E |
| `ab_team_list` | `team_toolbar` | create | toolbar action | Create a collaboration team | Click `新建团队` | Navigates to `/organization/teams/new` with create command context | Planned E2E |
| `ab_team_list` | `team_filters` | name / code / status | filters | Find teams by business identity and status | Search/filter in list | Query does not expose raw field labels or internal ids | Planned E2E |
| `ab_team_list` | `team_table` | name / code / description / status / updated_at | table columns | Review team identity, lifecycle and freshness | Open team list with seeded records | Columns show semantic labels; `pid` and numeric ids are not visible | Planned E2E |
| `ab_team_list` | row actions | members | row action | Manage the selected team's members | Click row members action | Navigates to `/organization/teams/{teamPid}` | Planned E2E |
| `ab_team_list` | row actions | edit | row action | Modify team master data | Click row edit action | Navigates to `/organization/teams/{teamPid}/edit` | Planned E2E |
| `ab_team_list` | row actions | delete | command action | Remove a team | Confirm row delete | Command deletes/soft-deletes `ab_team` record and list refreshes | Planned E2E |
| `ab_team_form` | `team_basic` | name | required text | Team display name | Submit empty form | Field-level required error is shown | Planned E2E |
| `ab_team_form` | `team_basic` | code | required text | Stable team code | Create then reopen edit | Code is persisted and not edited after creation | Planned E2E |
| `ab_team_form` | `team_basic` | leader_id | user reference | Team owner/leader | Select a leader | Saved value points to the selected user pid, not rounded numeric id | Planned E2E |
| `ab_team_form` | `team_basic` | status | enum | Team active/inactive state | Edit team status | Status persists and list tag updates | Planned E2E |
| `ab_team_form` | `team_actions` | save / cancel | form buttons | Complete or abandon edit | Save and cancel from create/edit | Save redirects to list; cancel returns to list without write | Planned E2E |
| `ab_team_detail` | `team_detail_toolbar` | back / edit | toolbar action | Return or continue editing | Open team detail | Back returns to list; edit opens team form | Planned E2E |
| `ab_team_detail` | `team_basic` | all detail fields | read-only detail | Inspect team record | Open detail from list | Details show business values only, no raw `pid` | Planned E2E |
| `ab_team_detail` | `team_members` | list / add / remove | custom block | Manage team membership without exposing internal ids | Open detail, add member, remove member | Uses display name/email/role and submits `memberPid` | Planned E2E |

## Action Coverage Notes

- Storage source of truth remains `ab_team`; no `mt_org_team` table should be created.
- Team member add/remove is implemented by DSL-mounted `TeamMembersBlock`; it uses `memberPid` and avoids JavaScript Long precision loss.
- Completion requires browser E2E plus backend evidence that commands write `ab_team` and member actions write `ab_team_member`.
