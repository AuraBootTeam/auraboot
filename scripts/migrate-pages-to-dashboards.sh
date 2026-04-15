#!/usr/bin/env bash
# migrate-pages-to-dashboards.sh
#
# Converts plugin pages with kind=dashboard from the legacy config/pages/*.json format
# to the first-class config/dashboards/*.json contract (Plan #8).
#
# Usage:
#   ./scripts/migrate-pages-to-dashboards.sh [PLUGIN_ROOT_DIR]
#
# If PLUGIN_ROOT_DIR is omitted, it defaults to the current directory.
# The script processes every subdirectory that contains config/pages/*.json files
# where kind=dashboard.
#
# What it does:
#   1. Finds config/pages/*_dashboard.json (or any pages/ file with "kind": "dashboard")
#   2. Converts V2 blocks DSL → Dashboard DSL using the same mapping as BlockToDashboardConverter:
#        blockType=chart + chartType=X  → smart-X-chart
#        blockType=stat-card            → smart-number-card
#        blockType=table                → smart-table-chart
#        blockType=rich-text            → smart-rich-text
#   3. Writes config/dashboards/<same-filename> next to the plugin root
#   4. Removes the old config/pages/*_dashboard.json file (use git rm manually if tracked)
#   5. Adds "dashboards": "config/dashboards" to plugin.json resourceDirs
#
# Requires: python3 (stdlib only)
#
# NOTE: Run this against enterprise plugins as a separate PR.
# The OSS backend already handles both paths (kind=dashboard pages → converter fallback
# AND config/dashboards/*.json → direct import). You can migrate incrementally.

set -euo pipefail

PLUGIN_ROOT="${1:-.}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONVERTER=$(cat <<'PYEOF'
import sys, json, os, pathlib

def resolve_title(raw):
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    if isinstance(raw, dict):
        for key in ("zh-CN", "en", "en-US"):
            if key in raw and raw[key]:
                return str(raw[key])
        for v in raw.values():
            if v:
                return str(v)
    return str(raw)

def map_widget_type(block):
    bt = block.get("blockType", "")
    if bt == "chart":
        ct = block.get("chartType", "bar")
        return f"smart-{ct}-chart"
    elif bt == "stat-card":
        return "smart-number-card"
    elif bt == "table":
        return "smart-table-chart"
    elif bt == "rich-text":
        return "smart-rich-text"
    else:
        print(f"  [WARN] unknown blockType '{bt}' → smart-unknown", file=sys.stderr)
        return "smart-unknown"

def extract_layout(block):
    layout = block.get("layout", {})
    if isinstance(layout, dict):
        return layout
    return {}

def convert_blocks_to_widgets(blocks):
    widgets = []
    y_offset = 0
    for i, block in enumerate(blocks or []):
        layout = extract_layout(block)
        col_span = layout.get("colSpan", block.get("colSpan", 12))
        row_span = layout.get("rowSpan", block.get("rowSpan", 1))
        block_id = block.get("id", f"widget_{i}")
        widget_type = map_widget_type(block)
        title = resolve_title(block.get("title")) or block_id

        config = {}
        if "chartConfig" in block:
            config = block["chartConfig"] if isinstance(block["chartConfig"], dict) else {}
        else:
            skip = {"blockType", "id", "colSpan", "rowSpan", "chartType", "layout"}
            config = {k: v for k, v in block.items() if k not in skip}
        if not config.get("title"):
            config["title"] = title

        widget = {
            "id": block_id,
            "type": widget_type,
            "x": 0,
            "y": y_offset,
            "w": int(col_span),
            "h": int(row_span),
            "title": title,
            "config": config,
        }
        widgets.append(widget)
        y_offset += int(row_span)
    return widgets

def build_layout_config(layout_map):
    lm = layout_map or {}
    return {
        "columns":     int(lm.get("columns", 12)),
        "rowHeight":   int(lm.get("rowHeight", 100)),
        "gap":         int(lm.get("gap", 16)),
        "compactType": lm.get("compactType", "vertical"),
    }

def convert_page_to_dashboard(page_json):
    code  = page_json.get("pageKey") or page_json.get("code", "")
    title = resolve_title(page_json.get("title") or page_json.get("name") or code)
    return {
        "code":         code,
        "title":        title,
        "description":  page_json.get("description"),
        "scope":        page_json.get("scope", "global"),
        "status":       page_json.get("status", "published"),
        "sortOrder":    page_json.get("sortOrder", 0),
        "layoutConfig": build_layout_config(page_json.get("layout")),
        "widgets":      convert_blocks_to_widgets(page_json.get("blocks", [])),
    }

def update_plugin_manifest(manifest_path):
    with open(manifest_path) as f:
        data = json.load(f)
    rd = data.setdefault("resourceDirs", {})
    if "dashboards" not in rd:
        rd["dashboards"] = "config/dashboards"
        with open(manifest_path, "w") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"  [OK] Added 'dashboards' to resourceDirs in {manifest_path}")

def process_plugin_dir(plugin_dir):
    plugin_dir = pathlib.Path(plugin_dir)
    pages_dir  = plugin_dir / "config" / "pages"
    if not pages_dir.is_dir():
        return

    dashboard_files = [
        p for p in pages_dir.glob("*.json")
        if is_dashboard_kind(p)
    ]
    if not dashboard_files:
        return

    out_dir = plugin_dir / "config" / "dashboards"
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"\nProcessing plugin: {plugin_dir}")
    print(f"  Found {len(dashboard_files)} dashboard page(s) to migrate")

    migrated = 0
    for src in dashboard_files:
        with open(src) as f:
            page = json.load(f)
        dashboard = convert_page_to_dashboard(page)
        dst = out_dir / src.name
        with open(dst, "w") as f:
            json.dump(dashboard, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"  [OK] {src.relative_to(plugin_dir)} → {dst.relative_to(plugin_dir)}")
        print(f"       code={dashboard['code']}, widgets={len(dashboard['widgets'])}")
        migrated += 1

    if migrated:
        manifest = plugin_dir / "plugin.json"
        if manifest.exists():
            update_plugin_manifest(manifest)
        print(f"  [ACTION REQUIRED] Run: git rm {pages_dir}/*_dashboard.json")
        print(f"                    Or manually remove the migrated page files.")

def is_dashboard_kind(json_path):
    try:
        with open(json_path) as f:
            data = json.load(f)
        if isinstance(data, list):
            return any(item.get("kind") == "dashboard" for item in data if isinstance(item, dict))
        return isinstance(data, dict) and data.get("kind") == "dashboard"
    except Exception:
        return False

# Main
root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")
print(f"Scanning for dashboard pages under: {root.resolve()}")

# Each subdirectory is treated as a potential plugin root
candidates = [root] + [d for d in root.iterdir() if d.is_dir()]
for candidate in candidates:
    process_plugin_dir(candidate)

print("\nDone. Review generated files, then use 'git rm' to remove old pages/*.json files.")
print("Remember to re-import affected plugins after the migration.")
PYEOF
)

python3 -c "$CONVERTER" "$PLUGIN_ROOT"
