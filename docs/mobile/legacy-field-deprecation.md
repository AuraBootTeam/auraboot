# Mobile BFF Legacy Field Deprecation Plan

> M-090 OPS-003 — track legacy fields on mobile BFF DTOs that are kept for
> backward compatibility but should be removed once all clients migrate.
> Last updated: 2026-05-08.

---

## 1. Why this doc

Mobile DTOs (Android Moshi-generated, iOS `Codable`) already prefer
canonical field names but the BFF still emits paired legacy aliases for
older clients. The aliases now block:

- The DTO contract from being a single source of truth — readers have to
  scan two fields and pick one.
- Wire-format size — every payload duplicates 3-4 fields per item.
- Dev clarity — new producers don't know which field is canonical.

This doc is the agreed timetable for retiring each alias.

## 2. Tracked fields (`InboxItemResponse`)

| Legacy field | Canonical replacement | Used by today | Removal target |
|--------------|----------------------|---------------|----------------|
| `subtitle` | `summary` | Android `InboxItemDTO.toDomain` falls back to `subtitle` when `summary` is null; iOS uses `summary` directly | After release **6.5** |
| `modelCode` | `sourceModel` | Android maps `modelCode → relatedPageKey`; iOS reads `sourceModel`. Both populated to identical values today | After release **6.5** |
| `recordId` (Long) | `sourceRecordId` (String) | Numeric leftover from BIGINT-only era. Production records are ULIDs, so `sourceRecordId` is the only safely useful field | After release **6.5** |
| `cardPayload` (String) | `cardData` (Map) | BFF parses `cardPayload` JSON into `cardData`; Android falls back to re-parsing `cardPayload` only when `cardData` is missing; iOS does not read `cardPayload` | After release **6.5** |

Each field has a `@Deprecated(since = "6.4")` annotation in
`platform/src/main/java/com/auraboot/framework/inbox/dto/InboxItemResponse.java`
that points at this doc.

## 3. Removal preconditions

A field becomes safe to remove when **all** are true:

1. **No mobile client reads it.** Verified by:
   ```bash
   # Android
   grep -rn '"\(subtitle\|modelCode\|recordId\|cardPayload\)"' apps/android \
     --include='*.kt' --include='*.json'
   # iOS
   grep -rn '"\(subtitle\|modelCode\|recordId\|cardPayload\)"' apps/ios \
     --include='*.swift'
   ```
   Both must return zero hits in production code (test fixtures may keep
   the field for `decode-permissive` regression tests).

2. **No web client reads it.** `web-admin` + `web-admin-ext`:
   ```bash
   grep -rn '\.\(subtitle\|modelCode\|recordId\|cardPayload\)\b' \
     web-admin/app web-admin-ext --include='*.ts' --include='*.tsx'
   ```

3. **All tenants have updated to a build that prefers the canonical
   field.** The BFF can identify outdated builds by inspecting
   `User-Agent: AuraBoot/<version>` on `/api/mobile/config`. Pre-removal
   the team confirms `<version> >= 6.5` for >99% of last-7-days traffic.

4. **At least one full release cycle has elapsed since `@Deprecated` was
   annotated.** Release 6.4 ships the annotations; removal lands no
   earlier than the 6.5 → 6.6 transition.

## 4. Removal procedure

1. Open a tracking PR titled `chore(inbox): remove legacy InboxItemResponse aliases (OPS-003)`.
2. Delete the four `@Deprecated` fields from `InboxItemResponse`.
3. Update `from(InboxItem)` to drop the alias-population calls.
4. Update mobile DTOs to drop fallback branches:
   - Android `InboxItemDTO.toDomain` lines that read `content = summary ?: content` etc.
   - iOS `InboxItem.swift` if it has any analogous fallback.
5. Run dual-platform DTO contract tests + integration tests against a
   server that emits only the canonical fields.
6. Land via standard direct-push-to-main flow. Update this doc to record
   the removal commit SHA and date.

## 5. New deprecations

When a BFF field gains a canonical replacement, add a row to §2 with:
- Legacy name, canonical name, current consumers
- Removal target release (one minor version away minimum)
- `@Deprecated(since = "<version>")` annotation pointing back to this doc

If a wider DTO than `InboxItemResponse` enters the deprecation track,
split §2 by DTO before it grows.

## 6. Out of scope (for now)

- DTOs other than `InboxItemResponse` (e.g., `MobileConfigResponse`,
  `HomeDashboardDTO`) — none currently carry duplicate fields.
- Backend `InboxItem` model fields (DB columns) — DB schema migration is
  a separate concern and runs on its own timeline.
- iOS / Android client-side cleanup of `subtitle ?? summary` fallbacks —
  these can stay until §3 preconditions are met for the corresponding
  field, then get cleaned up in step §4.
