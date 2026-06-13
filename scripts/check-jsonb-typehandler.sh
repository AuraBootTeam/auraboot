#!/usr/bin/env bash
# check-jsonb-typehandler.sh — guard against the recurring "varchar→jsonb on insert/update" bug.
#
# Root cause (4 instances in 2026-06: EDI, QueryAuditLog, OtDevice/OtDataLog, KbChunk):
# a String entity field mapped (@TableField) to a JSONB column, persisted via MyBatis-Plus
# BaseMapper auto-insert/updateById, throws
#     ERROR: column "<col>" is of type jsonb but expression is of type character varying
# because the driver binds the String as varchar and PostgreSQL does not implicitly cast
# (the host JDBC URL has no stringtype=unspecified). Such a field MUST declare
#     @TableField(value="<col>", typeHandler = ...JsonbStringTypeHandler.class)
# UNLESS its mapper inserts/updates that column with an explicit "#{...}::jsonb" cast
# (custom XML/annotation mapper) — in which case the typeHandler is not required.
#
# This lint resolves each @TableName entity's own table, asks the DB which columns are jsonb,
# and flags String @TableField fields on a jsonb column that have NEITHER a typeHandler NOR a
# "::jsonb" cast in the entity's mapper.
#
# Precondition: the shared aura_boot DB must be reachable (defaults below; override via env).
# If the DB is unreachable the check SKIPS (exit 0) with a warning — it is a guardrail, not a
# hard dependency for offline builds.
set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-${USER}}"
PG_DB="${PG_DB:-aura_boot}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${REPO_ROOT}/platform/src/main/java"

if ! command -v psql >/dev/null 2>&1; then
  echo "⚠️  [jsonb-typehandler] psql not found — skipping (guardrail needs a DB)."; exit 0
fi
if ! psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAc "select 1" >/dev/null 2>&1; then
  echo "⚠️  [jsonb-typehandler] cannot reach ${PG_USER}@${PG_HOST}:${PG_PORT}/${PG_DB} — skipping."
  echo "    (run after the shared DB is up, or set PG_HOST/PG_PORT/PG_USER/PG_DB)"; exit 0
fi

echo "==> jsonb-typeHandler check (src: ${SRC_DIR}, db: ${PG_DB})"

# table -> set(jsonb columns)
JSONB_COLS_TSV="$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAF $'\t' -c \
  "select table_name, column_name from information_schema.columns where data_type='jsonb'")"

JSONB_TSV="$JSONB_COLS_TSV" SRC_DIR="$SRC_DIR" python3 - <<'PY'
import os, re, glob, sys

jsonb = {}
for line in os.environ["JSONB_TSV"].splitlines():
    if not line.strip():
        continue
    t, c = line.split("\t")
    jsonb.setdefault(t, set()).add(c)

src = os.environ["SRC_DIR"]
errors, oks = [], 0

# index mapper files by simple name for the ::jsonb cross-check
mapper_text = {}
for mf in glob.glob(os.path.join(src, "**", "mapper", "*.java"), recursive=True):
    mapper_text[os.path.basename(mf)[:-5]] = open(mf, encoding="utf-8", errors="ignore").read()
# also XML mappers if any
for mx in glob.glob(os.path.join(src, "**", "*.xml"), recursive=True):
    name = os.path.basename(mx)[:-4]
    mapper_text.setdefault(name, "")
    mapper_text[name] += open(mx, encoding="utf-8", errors="ignore").read()

field_re = re.compile(
    r'@TableField\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*private\s+String\s+(\w+)\s*;')
colname_re = re.compile(r'"([a-z0-9_]+)"')

for ef in glob.glob(os.path.join(src, "**", "*.java"), recursive=True):
    if "/entity/" not in ef and "/dao/entity/" not in ef:
        continue
    txt = open(ef, encoding="utf-8", errors="ignore").read()
    tn = re.search(r'@TableName\("([a-z0-9_]+)"\)', txt)
    if not tn:
        continue
    table = tn.group(1)
    tbl_jsonb = jsonb.get(table)
    if not tbl_jsonb:
        continue
    entity = os.path.basename(ef)[:-5]
    for m in field_re.finditer(txt):
        args, field = m.group(1), m.group(2)
        cm = colname_re.search(args)
        if not cm:
            continue
        col = cm.group(1)
        if col not in tbl_jsonb:
            continue
        if "typeHandler" in args:
            oks += 1
            continue
        # no typeHandler — is there an explicit ::jsonb cast in the entity's mapper?
        mtext = mapper_text.get(entity + "Mapper", "")
        if "::jsonb" in mtext and re.search(re.escape(field) + r'\s*\}\s*::\s*jsonb', mtext, re.I) \
           or re.search(r'#\{' + re.escape(field) + r'\}::jsonb', mtext, re.I):
            oks += 1
            continue
        errors.append((entity, table, col, field))

if errors:
    print("\n❌ String @TableField on a jsonb column WITHOUT typeHandler or a mapper ::jsonb cast:")
    print("   (BaseMapper auto-insert/updateById will throw 'is of type jsonb but expression is character varying')\n")
    for e, t, c, f in sorted(errors):
        print(f"   - {e}.{f}  →  {t}.{c}")
    print(f"\n   Fix: add @TableField(value=\"<col>\", typeHandler = "
          "com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)")
    print(f"   ({len(errors)} issue(s); {oks} field(s) already safe)")
    sys.exit(1)

print(f"✅ jsonb-typeHandler check passed ({oks} String→jsonb field(s) protected).")
PY
