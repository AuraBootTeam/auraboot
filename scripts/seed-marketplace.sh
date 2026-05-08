#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PLUGINS_DIR="$PROJECT_ROOT/plugins"
# Postgres connection — same env-override pattern as oss-reset-and-init.sh.
# Defaults preserve host-mode; override for isolated docker stack via
# PG_HOST / PG_PORT / PG_USER / PG_DB (and PGPASSWORD when not trust-auth).
DB_HOST="${PG_HOST:-localhost}"
DB_PORT="${PG_PORT:-5432}"
DB_NAME="${PG_DB:-aura_boot}"
DB_USER="${PG_USER:-${USER:-ghj}}"

# Category mapping
category_for_namespace() {
  case "$1" in
    crm|pe_crm) echo "crm" ;;
    sl|sales|pr|procurement|inv|inventory|prod) echo "erp" ;;
    fin|finance) echo "finance" ;;
    pm) echo "project-management" ;;
    org) echo "hr" ;;
    acp) echo "ai" ;;
    pcba|pcba_sol|qo|quarry_sol) echo "industry" ;;
    admin) echo "utility" ;;
    *) echo "utility" ;;
  esac
}

count=0
for plugin_json in "$PLUGINS_DIR"/*/plugin.json; do
  dir=$(dirname "$plugin_json")
  dir_name=$(basename "$dir")

  # Skip non-plugin dirs
  if [[ "$dir_name" == "schemas" || "$dir_name" == "cli" || "$dir_name" == "scripts" ]]; then
    continue
  fi

  # Read plugin.json
  plugin_id=$(jq -r '.pluginId // empty' "$plugin_json")
  namespace=$(jq -r '.namespace // empty' "$plugin_json")
  version=$(jq -r '.version // "1.0.0"' "$plugin_json")
  display_name=$(jq -r '.displayName // .["displayName:en"] // empty' "$plugin_json")
  display_name_zh=$(jq -r '.["displayName:zh-CN"] // empty' "$plugin_json")
  display_name_en=$(jq -r '.["displayName:en"] // empty' "$plugin_json")
  description=$(jq -r '.description // empty' "$plugin_json")
  author=$(jq -r '.author // "AuraBoot Team"' "$plugin_json")
  plugin_type=$(jq -r '.pluginType // "config"' "$plugin_json")
  min_platform=$(jq -r '.minPlatformVersion // empty' "$plugin_json")

  if [[ -z "$plugin_id" || -z "$namespace" ]]; then
    echo "⏭ Skipping $dir_name (missing pluginId or namespace)"
    continue
  fi

  # Parse semver
  IFS='.' read -r v_major v_minor v_patch <<< "$version"
  v_major=${v_major:-0}
  v_minor=${v_minor:-0}
  v_patch=${v_patch:-0}

  category=$(category_for_namespace "$namespace")

  # Generate deterministic PIDs based on plugin_id hash
  plugin_pid="01MKP$(printf '%020s' "$dir_name" | md5sum | head -c 20 | tr '[:lower:]' '[:upper:]')"
  version_pid="01MKV$(printf '%020s' "${dir_name}_${version}" | md5sum | head -c 20 | tr '[:lower:]' '[:upper:]')"

  # Read full manifest for snapshot (escape for SQL)
  manifest=$(cat "$plugin_json" | jq -c '.')

  # Escape single quotes in strings for SQL
  display_name_escaped="${display_name//\'/\'\'}"
  display_name_zh_escaped="${display_name_zh//\'/\'\'}"
  display_name_en_escaped="${display_name_en//\'/\'\'}"
  description_escaped="${description//\'/\'\'}"
  author_escaped="${author//\'/\'\'}"

  # Insert marketplace plugin
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -P pager=off -q <<SQL
INSERT INTO ab_marketplace_plugin (pid, plugin_id, namespace, display_name, display_name_zh, display_name_en, summary, description, author, plugin_type, category_code, status, visibility, featured, install_count, latest_version, total_versions, min_platform_version, license_mode, created_at, updated_at, published_at, deleted_flag)
VALUES ('$plugin_pid', '$plugin_id', '$namespace', '$display_name_escaped', '$display_name_zh_escaped', '$display_name_en_escaped', '$description_escaped', '$description_escaped', '$author_escaped', '$plugin_type', '$category', 'published', 'public', false, 0, '$version', 1, $([ -n "$min_platform" ] && echo "'$min_platform'" || echo "NULL"), 'free', NOW(), NOW(), NOW(), false)
ON CONFLICT (plugin_id) DO UPDATE SET latest_version = '$version', updated_at = NOW();
SQL

  # Insert marketplace version
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -P pager=off -q <<SQL
INSERT INTO ab_marketplace_version (pid, marketplace_plugin_pid, version, version_major, version_minor, version_patch, manifest_snapshot, status, install_count, created_at, updated_at, published_at)
VALUES ('$version_pid', '$plugin_pid', '$version', $v_major, $v_minor, $v_patch, '$(echo "$manifest" | sed "s/'/''/g")'::jsonb, 'published', 0, NOW(), NOW(), NOW())
ON CONFLICT (marketplace_plugin_pid, version) DO NOTHING;
SQL

  # Read README.md if exists
  readme_file="$dir/README.md"
  if [[ -f "$readme_file" ]]; then
    readme_content=$(sed "s/'/''/g" "$readme_file")
    screenshots=$(grep -oP '!\[.*?\]\(\K[^)]+' "$readme_file" | grep '^http' | jq -R -s 'split("\n") | map(select(. != ""))' 2>/dev/null || echo '[]')
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -P pager=off -q <<EOSQL
UPDATE ab_marketplace_plugin SET readme_markdown = '${readme_content}', screenshots = '${screenshots}'::jsonb WHERE plugin_id = '${plugin_id}';
EOSQL
    echo "  📖 Loaded README for $plugin_id"
  fi

  count=$((count + 1))
  echo "✅ Seeded: $plugin_id ($namespace) v$version → $category"
done

# Refresh category counts
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -P pager=off -q <<SQL
UPDATE ab_marketplace_category SET plugin_count = (
  SELECT COUNT(*) FROM ab_marketplace_plugin
  WHERE category_code = ab_marketplace_category.code
    AND status = 'published'
    AND (deleted_flag = FALSE OR deleted_flag IS NULL)
);
SQL

echo ""
echo "🎉 Seeded $count plugins to marketplace"
echo ""
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -P pager=off -c "SELECT code, display_name_en, plugin_count FROM ab_marketplace_category WHERE plugin_count > 0 ORDER BY sort_order"
