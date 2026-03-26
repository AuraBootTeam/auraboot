#!/bin/bash
#
# DEPRECATED: Use the CLI instead:
#   cd plugins/cli && npx tsx src/index.ts plugin validate <dir>
#   See: plugins/cli/README.md
#
# AuraBoot Plugin Offline Validator
# Validates plugin configuration files without requiring a running server.
#
# Usage: ./validate-plugin.sh <plugin-dir>
# Example: ./validate-plugin.sh plugins/quarry-industry
#
# Exit codes:
#   0 - No errors (warnings are OK)
#   1 - One or more validation errors found
#

set -uo pipefail

# ============================================================
# Color output helpers
# ============================================================
if [[ -t 1 ]] && command -v tput &>/dev/null; then
  RED=$(tput setaf 1)
  GREEN=$(tput setaf 2)
  YELLOW=$(tput setaf 3)
  CYAN=$(tput setaf 6)
  BOLD=$(tput bold)
  RESET=$(tput sgr0)
else
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  CYAN='\033[0;36m'
  BOLD='\033[1m'
  RESET='\033[0m'
fi

TOTAL_ERRORS=0
TOTAL_WARNINGS=0

# Per-phase accumulators
PHASE_ERRORS=0
PHASE_WARNINGS=0
PHASE_MESSAGES=""

log_error() {
  PHASE_ERRORS=$((PHASE_ERRORS + 1))
  TOTAL_ERRORS=$((TOTAL_ERRORS + 1))
  PHASE_MESSAGES="${PHASE_MESSAGES}  ${RED}ERROR${RESET}: $1\n"
}

log_warn() {
  PHASE_WARNINGS=$((PHASE_WARNINGS + 1))
  TOTAL_WARNINGS=$((TOTAL_WARNINGS + 1))
  PHASE_MESSAGES="${PHASE_MESSAGES}  ${YELLOW}WARN${RESET}: $1\n"
}

log_ok() {
  printf "${GREEN}%s${RESET} %s\n" "+" "$1"
}

log_info() {
  printf "${CYAN}%s${RESET} %s\n" ">" "$1"
}

reset_phase() {
  PHASE_ERRORS=0
  PHASE_WARNINGS=0
  PHASE_MESSAGES=""
}

print_phase_result() {
  local phase_num="$1"
  local phase_name="$2"
  local extra_info="${3:-}"

  local name_len=${#phase_name}
  local target=35
  local pad=$((target - name_len))
  if [[ $pad -lt 1 ]]; then pad=1; fi
  local dots
  dots=$(printf '.%.0s' $(seq 1 "$pad"))

  if [[ $PHASE_ERRORS -gt 0 ]]; then
    local s=""; if [[ $PHASE_ERRORS -ne 1 ]]; then s="s"; fi
    printf "${RED}%s${RESET} Phase %s: %s %s %s (%d error%s)\n" \
      "x" "$phase_num" "$phase_name" "$dots" "${RED}FAIL${RESET}" "$PHASE_ERRORS" "$s"
  elif [[ $PHASE_WARNINGS -gt 0 ]]; then
    local s=""; if [[ $PHASE_WARNINGS -ne 1 ]]; then s="s"; fi
    printf "${YELLOW}%s${RESET} Phase %s: %s %s %s (%d warning%s)\n" \
      "!" "$phase_num" "$phase_name" "$dots" "${YELLOW}WARN${RESET}" "$PHASE_WARNINGS" "$s"
  else
    local suffix=""
    if [[ -n "$extra_info" ]]; then suffix=" ($extra_info)"; fi
    printf "${GREEN}%s${RESET} Phase %s: %s %s %s%s\n" \
      "+" "$phase_num" "$phase_name" "$dots" "${GREEN}OK${RESET}" "$suffix"
  fi

  if [[ -n "$PHASE_MESSAGES" ]]; then
    printf "%b" "$PHASE_MESSAGES"
  fi
}

# ============================================================
# Helper: read codes from a config file into a newline-separated list
# ============================================================
collect_codes() {
  local file="$1"
  local field="$2"
  if [[ -f "$file" ]]; then
    jq -r ".[] | ${field} // empty" "$file" 2>/dev/null || true
  fi
}

# Helper: check if a value exists in a newline-separated list
# Returns 0 if found, 1 if not found
value_in_list() {
  local needle="$1"
  local haystack="$2"
  echo "$haystack" | grep -qFx "$needle" 2>/dev/null
  return $?
}

# ============================================================
# Argument parsing and prerequisite checks
# ============================================================
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <plugin-dir>"
  echo "Example: $0 plugins/quarry-industry"
  exit 1
fi

# Check jq availability
if ! command -v jq &>/dev/null; then
  echo "${RED}ERROR${RESET}: jq is required but not found."
  echo "Install it with:  brew install jq  (macOS)  or  apt-get install jq  (Linux)"
  exit 1
fi

PLUGIN_DIR="$1"

# Resolve to absolute path
if [[ "$PLUGIN_DIR" != /* ]]; then
  PLUGIN_DIR="$(cd "$PLUGIN_DIR" 2>/dev/null && pwd)" || {
    echo "${RED}ERROR${RESET}: Cannot resolve plugin directory: $1"
    exit 1
  }
fi

if [[ ! -d "$PLUGIN_DIR" ]]; then
  echo "${RED}ERROR${RESET}: Plugin directory not found: $PLUGIN_DIR"
  exit 1
fi

PLUGIN_JSON="$PLUGIN_DIR/plugin.json"
CONFIG_DIR="$PLUGIN_DIR/config"

if [[ ! -f "$PLUGIN_JSON" ]]; then
  echo "${RED}ERROR${RESET}: plugin.json not found in $PLUGIN_DIR"
  exit 1
fi

# Extract plugin metadata for the report header
PLUGIN_NAME=$(jq -r '.pluginId // "unknown"' "$PLUGIN_JSON" 2>/dev/null || echo "unknown")
PLUGIN_VERSION=$(jq -r '.version // "?.?.?"' "$PLUGIN_JSON" 2>/dev/null || echo "?.?.?")

echo ""
echo "${BOLD}=== Plugin Validation Report ===${RESET}"
echo "Plugin: ${CYAN}${PLUGIN_NAME}${RESET} (v${PLUGIN_VERSION})"
echo ""

# Pre-define file path variables used across phases
BINDINGS_FILE="$CONFIG_DIR/bindings.json"
MODELS_FILE="$CONFIG_DIR/models.json"
FIELDS_FILE="$CONFIG_DIR/fields.json"
COMMANDS_FILE="$CONFIG_DIR/commands.json"
PERMS_FILE="$CONFIG_DIR/permissions.json"
MENUS_FILE="$CONFIG_DIR/menus.json"
PAGES_FILE="$CONFIG_DIR/pages.json"

# ============================================================
# Phase 1: JSON Syntax Check
# ============================================================
reset_phase

JSON_FILE_COUNT=0

# Validate plugin.json
jq_err=$(jq empty "$PLUGIN_JSON" 2>&1) || true
if [[ -n "$jq_err" ]]; then
  log_error "plugin.json: $jq_err"
else
  JSON_FILE_COUNT=$((JSON_FILE_COUNT + 1))
fi

# Validate all .json files under config/
if [[ -d "$CONFIG_DIR" ]]; then
  for json_file in "$CONFIG_DIR"/*.json; do
    [[ -f "$json_file" ]] || continue
    basename_file=$(basename "$json_file")
    jq_err=$(jq empty "$json_file" 2>&1) || true
    if [[ -n "$jq_err" ]]; then
      log_error "${basename_file}: $jq_err"
    else
      JSON_FILE_COUNT=$((JSON_FILE_COUNT + 1))
    fi
  done
fi

print_phase_result "1" "JSON syntax" "${JSON_FILE_COUNT} files"

# Stop early if JSON is broken - further phases depend on parseable JSON
if [[ $PHASE_ERRORS -gt 0 ]]; then
  echo ""
  echo "${RED}Aborting${RESET}: Fix JSON syntax errors before running further validations."
  echo ""
  echo "Errors: ${TOTAL_ERRORS} | Warnings: ${TOTAL_WARNINGS}"
  exit 1
fi

# ============================================================
# Phase 2: Required Fields + Code Uniqueness
# ============================================================
reset_phase

# --- plugin.json required fields ---
for field in pluginId namespace version; do
  val=$(jq -r ".${field} // empty" "$PLUGIN_JSON" 2>/dev/null || true)
  if [[ -z "$val" ]]; then
    log_error "plugin.json: required field \"${field}\" is missing or empty"
  fi
done

# --- Config files: code uniqueness check ---
for cfg_file in dicts.json fields.json models.json commands.json permissions.json roles.json menus.json; do
  filepath="$CONFIG_DIR/$cfg_file"
  [[ -f "$filepath" ]] || continue

  # Check each item has non-empty code
  empty_indices=$(jq -r 'to_entries[] | select((.value.code) == null or (.value.code) == "") | .key' "$filepath" 2>/dev/null || true)
  if [[ -n "$empty_indices" ]]; then
    while IFS= read -r idx; do
      log_error "${cfg_file}[${idx}]: missing or empty \"code\""
    done <<< "$empty_indices"
  fi

  # Check uniqueness
  dup_codes=$(jq -r '[.[] | .code // empty] | group_by(.) | map(select(length > 1) | .[0]) | .[]' "$filepath" 2>/dev/null || true)
  if [[ -n "$dup_codes" ]]; then
    while IFS= read -r dup; do
      log_error "${cfg_file}: duplicate code \"${dup}\""
    done <<< "$dup_codes"
  fi
done

# --- bindings.json: modelCode + fieldCode required ---
if [[ -f "$BINDINGS_FILE" ]]; then
  empty_mc=$(jq -r 'to_entries[] | select(.value.modelCode == null or .value.modelCode == "") | .key' "$BINDINGS_FILE" 2>/dev/null || true)
  if [[ -n "$empty_mc" ]]; then
    while IFS= read -r idx; do
      log_error "bindings.json[${idx}]: missing or empty \"modelCode\""
    done <<< "$empty_mc"
  fi
  empty_fc=$(jq -r 'to_entries[] | select(.value.fieldCode == null or .value.fieldCode == "") | .key' "$BINDINGS_FILE" 2>/dev/null || true)
  if [[ -n "$empty_fc" ]]; then
    while IFS= read -r idx; do
      log_error "bindings.json[${idx}]: missing or empty \"fieldCode\""
    done <<< "$empty_fc"
  fi
fi

# --- pages.json: pageKey + pageType required, pageKey uniqueness ---
if [[ -f "$PAGES_FILE" ]]; then
  empty_pk=$(jq -r 'to_entries[] | select(.value.pageKey == null or .value.pageKey == "") | .key' "$PAGES_FILE" 2>/dev/null || true)
  if [[ -n "$empty_pk" ]]; then
    while IFS= read -r idx; do
      log_error "pages.json[${idx}]: missing or empty \"pageKey\""
    done <<< "$empty_pk"
  fi
  empty_pt=$(jq -r 'to_entries[] | select(.value.pageType == null or .value.pageType == "") | .key' "$PAGES_FILE" 2>/dev/null || true)
  if [[ -n "$empty_pt" ]]; then
    while IFS= read -r idx; do
      log_error "pages.json[${idx}]: missing or empty \"pageType\""
    done <<< "$empty_pt"
  fi
  # pageKey uniqueness
  dup_pk=$(jq -r '[.[] | .pageKey // empty] | group_by(.) | map(select(length > 1) | .[0]) | .[]' "$PAGES_FILE" 2>/dev/null || true)
  if [[ -n "$dup_pk" ]]; then
    while IFS= read -r dup; do
      log_error "pages.json: duplicate pageKey \"${dup}\""
    done <<< "$dup_pk"
  fi
fi

print_phase_result "2" "Required fields & uniqueness"

# ============================================================
# Phase 3: Enum Value Validation
# ============================================================
reset_phase

# --- models.json: modelType enum ---
if [[ -f "$MODELS_FILE" ]]; then
  VALID_MODEL_TYPES="ENTITY VIEW VIRTUAL AGGREGATE VALUE_OBJECT"
  entries=$(jq -r 'to_entries[] | select(.value.modelType != null) | "\(.key)|\(.value.code // "?")|\(.value.modelType)"' "$MODELS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code val; do
      if ! echo "$VALID_MODEL_TYPES" | grep -qw "$val" 2>/dev/null; then
        log_error "models.json[${idx}] code=\"${code}\": invalid modelType=\"${val}\" (expected: ${VALID_MODEL_TYPES// /, })"
      fi
    done <<< "$entries"
  fi
fi

# --- fields.json: dataType enum (required) ---
if [[ -f "$FIELDS_FILE" ]]; then
  VALID_DATA_TYPES="STRING INTEGER DECIMAL BOOLEAN DATE DATETIME TEXT DICT RELATION JSON COMPUTED REFERENCE ENUM"
  entries=$(jq -r 'to_entries[] | "\(.key)|\(.value.code // "?")|\(.value.dataType // "")"' "$FIELDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code val; do
      if [[ -z "$val" ]]; then
        log_error "fields.json[${idx}] code=\"${code}\": missing required \"dataType\""
      elif ! echo "$VALID_DATA_TYPES" | grep -qw "$val" 2>/dev/null; then
        log_error "fields.json[${idx}] code=\"${code}\": invalid dataType=\"${val}\" (expected: ${VALID_DATA_TYPES// /, })"
      fi
    done <<< "$entries"
  fi
fi

# --- commands.json: type enum ---
if [[ -f "$COMMANDS_FILE" ]]; then
  VALID_CMD_TYPES="CREATE UPDATE DELETE QUERY ACTION STATE_TRANSITION BATCH_CREATE BATCH_UPDATE BATCH_DELETE IMPORT"
  entries=$(jq -r 'to_entries[] | select(.value.type != null) | "\(.key)|\(.value.code // "?")|\(.value.type)"' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code val; do
      if ! echo "$VALID_CMD_TYPES" | grep -qw "$val" 2>/dev/null; then
        log_error "commands.json[${idx}] code=\"${code}\": invalid type=\"${val}\" (expected: ${VALID_CMD_TYPES// /, })"
      fi
    done <<< "$entries"
  fi
fi

# --- permissions.json: resourceType enum ---
if [[ -f "$PERMS_FILE" ]]; then
  VALID_RES_TYPES="API MENU DATA BUTTON MODEL COMMAND OPERATION PAGE"
  entries=$(jq -r 'to_entries[] | select(.value.resourceType != null) | "\(.key)|\(.value.code // "?")|\(.value.resourceType)"' "$PERMS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code val; do
      if ! echo "$VALID_RES_TYPES" | grep -qw "$val" 2>/dev/null; then
        log_error "permissions.json[${idx}] code=\"${code}\": invalid resourceType=\"${val}\" (expected: ${VALID_RES_TYPES// /, })"
      fi
    done <<< "$entries"
  fi
fi

# --- menus.json: type enum (0 or 1) ---
if [[ -f "$MENUS_FILE" ]]; then
  VALID_MENU_TYPES="0 1 2"
  entries=$(jq -r 'to_entries[] | select(.value.type != null) | "\(.key)|\(.value.code // "?")|\(.value.type)"' "$MENUS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code val; do
      if ! echo "$VALID_MENU_TYPES" | grep -qw "$val" 2>/dev/null; then
        log_error "menus.json[${idx}] code=\"${code}\": invalid type=\"${val}\" (expected: 0, 1)"
      fi
    done <<< "$entries"
  fi
fi

print_phase_result "3" "Enum values"

# ============================================================
# Phase 4: Cross-Resource Reference Integrity
# ============================================================
reset_phase

# Collect code sets from each file (if it exists)
DICT_CODES=$(collect_codes "$CONFIG_DIR/dicts.json" ".code")
FIELD_CODES=$(collect_codes "$CONFIG_DIR/fields.json" ".code")
MODEL_CODES=$(collect_codes "$CONFIG_DIR/models.json" ".code")
PERM_CODES=$(collect_codes "$CONFIG_DIR/permissions.json" ".code")
MENU_CODES=$(collect_codes "$CONFIG_DIR/menus.json" ".code")

# --- fields.json dictCode -> dicts.json ---
if [[ -f "$FIELDS_FILE" ]] && [[ -f "$CONFIG_DIR/dicts.json" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.dictCode != null and .value.dictCode != "") | "\(.key)|\(.value.code // "?")|\(.value.dictCode)"' "$FIELDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code ref; do
      if ! value_in_list "$ref" "$DICT_CODES"; then
        log_error "fields.json[${idx}] code=\"${code}\": dictCode=\"${ref}\" not found in dicts.json"
      fi
    done <<< "$entries"
  fi
fi

# --- bindings.json modelCode -> models.json ---
if [[ -f "$BINDINGS_FILE" ]] && [[ -f "$MODELS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.modelCode != null and .value.modelCode != "") | "\(.key)|\(.value.modelCode)"' "$BINDINGS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx ref; do
      if ! value_in_list "$ref" "$MODEL_CODES"; then
        log_error "bindings.json[${idx}] modelCode=\"${ref}\" not found in models.json"
      fi
    done <<< "$entries"
  fi
fi

# --- bindings.json fieldCode -> fields.json ---
if [[ -f "$BINDINGS_FILE" ]] && [[ -f "$FIELDS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.fieldCode != null and .value.fieldCode != "") | "\(.key)|\(.value.fieldCode)"' "$BINDINGS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx ref; do
      if ! value_in_list "$ref" "$FIELD_CODES"; then
        log_error "bindings.json[${idx}] fieldCode=\"${ref}\" not found in fields.json"
      fi
    done <<< "$entries"
  fi
fi

# --- commands.json modelCode -> models.json ---
if [[ -f "$COMMANDS_FILE" ]] && [[ -f "$MODELS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.modelCode != null and .value.modelCode != "") | "\(.key)|\(.value.code // "?")|\(.value.modelCode)"' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code ref; do
      if ! value_in_list "$ref" "$MODEL_CODES"; then
        log_error "commands.json[${idx}] code=\"${code}\": modelCode=\"${ref}\" not found in models.json"
      fi
    done <<< "$entries"
  fi
fi

# --- roles.json permissions[] -> permissions.json ---
if [[ -f "$CONFIG_DIR/roles.json" ]] && [[ -f "$PERMS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | .key as $idx | .value.code as $code | (.value.permissions // [])[] | "\($idx)|\($code // "?")|\(.)"' "$CONFIG_DIR/roles.json" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code ref; do
      if ! value_in_list "$ref" "$PERM_CODES"; then
        log_error "roles.json[${idx}] code=\"${code}\": permission \"${ref}\" not found in permissions.json"
      fi
    done <<< "$entries"
  fi
fi

# --- menus.json permissionCode -> permissions.json ---
if [[ -f "$MENUS_FILE" ]] && [[ -f "$PERMS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.permissionCode != null and .value.permissionCode != "") | "\(.key)|\(.value.code // "?")|\(.value.permissionCode)"' "$MENUS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code ref; do
      if ! value_in_list "$ref" "$PERM_CODES"; then
        log_error "menus.json[${idx}] code=\"${code}\": permissionCode=\"${ref}\" not found in permissions.json"
      fi
    done <<< "$entries"
  fi
fi

# --- menus.json parentCode -> menus.json (self-referential tree) ---
if [[ -f "$MENUS_FILE" ]]; then
  entries=$(jq -r 'to_entries[] | select(.value.parentCode != null and .value.parentCode != "") | "\(.key)|\(.value.code // "?")|\(.value.parentCode)"' "$MENUS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code ref; do
      if ! value_in_list "$ref" "$MENU_CODES"; then
        log_error "menus.json[${idx}] code=\"${code}\": parentCode=\"${ref}\" not found in menus.json"
      fi
    done <<< "$entries"
  fi
fi

print_phase_result "4" "Cross references"

# ============================================================
# Phase 5: Known Pitfall Detection (WARNINGS only)
# ============================================================
reset_phase

# --- STATE_TRANSITION commands must have stateTransitionRules OR (fromStates + toState) ---
if [[ -f "$COMMANDS_FILE" ]]; then
  entries=$(jq -r '
    to_entries[]
    | select(.value.type == "STATE_TRANSITION")
    | select(
        ((.value.stateTransitionRules // []) | length == 0)
        and ((.value.fromStates // []) | length == 0 or (.value.toState // "") == "")
      )
    | "\(.key)|\(.value.code // "?")"
  ' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code; do
      log_warn "commands.json[${idx}] code=\"${code}\": type=STATE_TRANSITION but no stateTransitionRules or fromStates+toState found"
    done <<< "$entries"
  fi
fi

# --- ENTITY models should have at least one binding ---
if [[ -f "$MODELS_FILE" ]] && [[ -f "$BINDINGS_FILE" ]]; then
  entity_models=$(jq -r '.[] | select(.modelType == "ENTITY") | .code' "$MODELS_FILE" 2>/dev/null || true)
  if [[ -n "$entity_models" ]]; then
    binding_model_codes=$(jq -r '[.[] | .modelCode] | unique | .[]' "$BINDINGS_FILE" 2>/dev/null || true)
    while IFS= read -r mc; do
      if ! value_in_list "$mc" "$binding_model_codes"; then
        log_warn "models.json: ENTITY model \"${mc}\" has no field bindings in bindings.json"
      fi
    done <<< "$entity_models"
  fi
fi

# --- autoSetFields with FIXED_VALUE strategy must have value ---
if [[ -f "$COMMANDS_FILE" ]]; then
  entries=$(jq -r '
    to_entries[]
    | .key as $idx
    | .value.code as $code
    | (.value.autoSetFields // {}) | to_entries[]
    | select(.value.strategy == "FIXED_VALUE" and (.value.value == null))
    | "\($idx)|\($code // "?")|\(.key)"
  ' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code field; do
      log_warn "commands.json[${idx}] code=\"${code}\": autoSetFields.${field} has strategy=FIXED_VALUE but no \"value\" field"
    done <<< "$entries"
  fi
fi

# --- postActions with action=CREATE_CHILDREN must have childModel ---
if [[ -f "$COMMANDS_FILE" ]]; then
  entries=$(jq -r '
    to_entries[]
    | .key as $idx
    | .value.code as $code
    | (.value.postActions // [])[]
    | select(.action == "CREATE_CHILDREN" and (.childModel == null or .childModel == ""))
    | "\($idx)|\($code // "?")"
  ' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code; do
      log_warn "commands.json[${idx}] code=\"${code}\": postActions action=CREATE_CHILDREN but missing \"childModel\" field"
    done <<< "$entries"
  fi
fi

# --- Top-level "condition" field in commands is deprecated (use "guard") ---
if [[ -f "$COMMANDS_FILE" ]]; then
  entries=$(jq -r '
    to_entries[]
    | select(.value.condition != null)
    | "\(.key)|\(.value.code // "?")"
  ' "$COMMANDS_FILE" 2>/dev/null || true)
  if [[ -n "$entries" ]]; then
    while IFS='|' read -r idx code; do
      log_warn "commands.json[${idx}] code=\"${code}\": top-level \"condition\" field is deprecated, use \"guard\" instead"
    done <<< "$entries"
  fi
fi

print_phase_result "5" "Known pitfalls"

# ============================================================
# Phase 6: Summary Report
# ============================================================
echo ""
if [[ $TOTAL_ERRORS -eq 0 ]] && [[ $TOTAL_WARNINGS -eq 0 ]]; then
  echo "${GREEN}${BOLD}All checks passed.${RESET}"
elif [[ $TOTAL_ERRORS -eq 0 ]]; then
  echo "${YELLOW}${BOLD}Passed with warnings.${RESET}"
else
  echo "${RED}${BOLD}Validation failed.${RESET}"
fi

echo "Errors: ${TOTAL_ERRORS} | Warnings: ${TOTAL_WARNINGS}"
echo ""

if [[ $TOTAL_ERRORS -gt 0 ]]; then
  exit 1
else
  exit 0
fi
