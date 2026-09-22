#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/db/stage-pre1900-core-migrations.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -r "$tmp_dir"' EXIT

bash "$script" --core-root "$repo_root" --out-dir "$tmp_dir/overlay" > "$tmp_dir/stage.log"
test -f "$tmp_dir/overlay/V20260705093000__sla_action_policy.sql"
test -f "$tmp_dir/overlay/V20260921010000__backfill_price_evidence_quote_id.sql"

old_file=platform/src/main/resources/db/migration/core/V20260618000000__baseline_core_schema.sql
git -C "$repo_root" show "114f12a4c9b525f184f7b97d423ada9e3ab7de5f:$old_file" > "$tmp_dir/expected.sql"
cmp "$tmp_dir/expected.sql" "$tmp_dir/overlay/$(basename "$old_file")"
cmp "$repo_root/platform/src/main/resources/db/migration/core/V20260921010000__backfill_price_evidence_quote_id.sql" \
  "$tmp_dir/overlay/V20260921010000__backfill_price_evidence_quote_id.sql"

if bash "$script" --core-root "$repo_root" --out-dir "$tmp_dir/overlay" > "$tmp_dir/duplicate.log" 2>&1; then
  echo "stage must refuse an existing overlay" >&2
  exit 1
fi
grep -q 'Refusing existing overlay path' "$tmp_dir/duplicate.log"
echo "PASS: immutable legacy bytes, newer migration preservation, existing-path refusal"
