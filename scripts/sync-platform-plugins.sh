#!/bin/bash
# Sync SSR platform plugin frontend/ dirs into web-admin/app/plugins/
# Only manages directories with a .synced-plugin marker (safe for coexisting source dirs)
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGINS_DIR="$REPO_ROOT/plugins/platform"
TARGET_DIR="$REPO_ROOT/web-admin/app/plugins"

mkdir -p "$TARGET_DIR"

# Clean old synced plugins that no longer exist
# Only remove directories with a .synced-plugin marker
for synced_dir in "$TARGET_DIR"/*/; do
  [ -d "$synced_dir" ] || continue
  [ -f "$synced_dir/.synced-plugin" ] || continue
  plugin_name=$(basename "$synced_dir")
  if [ ! -d "$PLUGINS_DIR/$plugin_name" ]; then
    echo "Removing uninstalled plugin: $plugin_name"
    rm -rf "$synced_dir"
  fi
done

# Sync each SSR platform plugin
for plugin_dir in "$PLUGINS_DIR"/*/; do
  [ -d "$plugin_dir" ] || continue
  plugin_json="$plugin_dir/plugin.json"
  [ -f "$plugin_json" ] || continue

  rendering=$(node -e "try{console.log(JSON.parse(require('fs').readFileSync('$plugin_json','utf8')).rendering||'csr')}catch(e){console.log('csr')}")
  plugin_id=$(node -e "try{console.log(JSON.parse(require('fs').readFileSync('$plugin_json','utf8')).id.split('.').pop())}catch(e){console.log('')}")

  [ -z "$plugin_id" ] && continue

  if [ "$rendering" = "ssr" ]; then
    frontend_dir="$plugin_dir/frontend"
    if [ -d "$frontend_dir" ]; then
      echo "Syncing SSR plugin: $plugin_id"
      rsync -a --delete "$frontend_dir/" "$TARGET_DIR/$plugin_id/"
      # Write marker so cleanup knows this is a synced directory
      echo "$plugin_id" > "$TARGET_DIR/$plugin_id/.synced-plugin"
    fi
  fi
done

echo "Platform plugin sync complete."
