#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# AuraBoot Repo Publisher
# Distributes monorepo files to 4 independent GitHub repositories
#
# Usage:
#   ./scripts/publish-repos.sh              # dry-run (default)
#   ./scripts/publish-repos.sh --execute    # actually copy + commit + push
#   ./scripts/publish-repos.sh --init       # first-time setup (clone repos)
# ─────────────────────────────────────────────────────────────

MONO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE="${MONO_ROOT}/../auraboot-workspace"

# Temp repo URLs
REPO_PUBLIC="https://github.com/AuraBootTeam/auraboot.git"
REPO_ENTERPRISE="https://github.com/AuraBootTeam/auraboot-enterprise.git"
REPO_SOLUTIONS="https://github.com/AuraBootTeam/auraboot-solutions.git"
REPO_META="https://github.com/AuraBootTeam/auraboot-meta.git"

DIR_PUBLIC="${WORKSPACE}/auraboot"
DIR_ENTERPRISE="${WORKSPACE}/auraboot-enterprise"
DIR_SOLUTIONS="${WORKSPACE}/auraboot-solutions"
DIR_META="${WORKSPACE}/auraboot-meta"

MODE="${1:---dry-run}"
COMMIT_MSG="sync: publish from monorepo $(date +%Y-%m-%d)"

# ─────────────────── Colors ───────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[publish]${NC} $1"; }
ok()   { echo -e "${GREEN}[  ok  ]${NC} $1"; }
warn() { echo -e "${YELLOW}[ warn ]${NC} $1"; }
err()  { echo -e "${RED}[error ]${NC} $1"; }

# ─────────────────── Init Mode ───────────────────
init_workspace() {
    log "Creating workspace at ${WORKSPACE}"
    mkdir -p "${WORKSPACE}"

    cd "${WORKSPACE}"
    [ ! -d "auraboot" ] && git clone "${REPO_PUBLIC}" auraboot
    [ ! -d "auraboot-enterprise" ] && git clone "${REPO_ENTERPRISE}" auraboot-enterprise
    [ ! -d "auraboot-solutions" ] && git clone "${REPO_SOLUTIONS}" auraboot-solutions
    [ ! -d "auraboot-meta" ] && git clone "${REPO_META}" auraboot-meta

    ok "Workspace initialized at ${WORKSPACE}"
    ls -la "${WORKSPACE}"
}

# ─────────────────── Sync Functions ───────────────────

sync_file() {
    local src="$1"
    local dst="$2"

    if [ ! -e "${src}" ]; then
        warn "Source not found: ${src}"
        return
    fi

    mkdir -p "$(dirname "${dst}")"

    if [ -d "${src}" ]; then
        rsync -a --delete --exclude='.git' --exclude='node_modules' --exclude='build' --exclude='.gradle' "${src}/" "${dst}/"
    else
        cp -f "${src}" "${dst}"
    fi
}

sanitize_public() {
    local dir="$1"

    # Remove private key
    rm -f "${dir}/platform/src/main/resources/license/private.pem"

    # Remove enterprise Gradle references from settings.gradle
    if [ -f "${dir}/platform/settings.gradle" ]; then
        sed -i '' "/platform-enterprise/d" "${dir}/platform/settings.gradle" 2>/dev/null || true
    fi

    # Remove enterprise dependencies from build.gradle
    if [ -f "${dir}/platform/build.gradle" ]; then
        sed -i '' "/platform-enterprise/d" "${dir}/platform/build.gradle" 2>/dev/null || true
    fi

    # Remove mission-control route if it references enterprise-only code
    # (Keep it — it shows upgrade prompt in community mode)

    ok "Sanitized public repo (removed private.pem, enterprise references)"
}

# ─────────────────── Public Repo ───────────────────

sync_public() {
    log "Syncing auraboot (public)..."
    local dst="${DIR_PUBLIC}"

    # Platform core
    sync_file "${MONO_ROOT}/platform/src" "${dst}/platform/src"
    sync_file "${MONO_ROOT}/platform/build.gradle" "${dst}/platform/build.gradle"
    sync_file "${MONO_ROOT}/platform/settings.gradle" "${dst}/platform/settings.gradle"
    sync_file "${MONO_ROOT}/platform/platform-plugin-api" "${dst}/platform/platform-plugin-api"
    sync_file "${MONO_ROOT}/platform/platform-storage-minio" "${dst}/platform/platform-storage-minio"
    sync_file "${MONO_ROOT}/platform/platform-storage-s3" "${dst}/platform/platform-storage-s3"
    sync_file "${MONO_ROOT}/platform/platform-storage-oss" "${dst}/platform/platform-storage-oss"
    sync_file "${MONO_ROOT}/platform/platform-mq-kafka" "${dst}/platform/platform-mq-kafka"
    sync_file "${MONO_ROOT}/platform/platform-mq-rabbitmq" "${dst}/platform/platform-mq-rabbitmq"

    # Web admin
    sync_file "${MONO_ROOT}/web-admin" "${dst}/web-admin"

    # Public plugins
    sync_file "${MONO_ROOT}/plugins/crm-starter" "${dst}/plugins/crm-starter"
    sync_file "${MONO_ROOT}/plugins/e2e-test-order" "${dst}/plugins/e2e-test-order"
    sync_file "${MONO_ROOT}/plugins/cli" "${dst}/plugins/cli"
    sync_file "${MONO_ROOT}/plugins/schemas" "${dst}/plugins/schemas"
    sync_file "${MONO_ROOT}/plugins/templates" "${dst}/plugins/templates"
    sync_file "${MONO_ROOT}/plugins/scripts" "${dst}/plugins/scripts"

    # Scripts
    sync_file "${MONO_ROOT}/scripts/reset-and-init.sh" "${dst}/scripts/reset-and-init.sh"
    sync_file "${MONO_ROOT}/scripts/reset-db.sh" "${dst}/scripts/reset-db.sh"

    # Public docs
    sync_file "${MONO_ROOT}/docs/getting-started" "${dst}/docs/getting-started"

    # Root files
    sync_file "${MONO_ROOT}/docker-compose.yml" "${dst}/docker-compose.yml"
    sync_file "${MONO_ROOT}/LICENSE.txt" "${dst}/LICENSE.txt"
    sync_file "${MONO_ROOT}/README.md" "${dst}/README.md"
    sync_file "${MONO_ROOT}/CONTRIBUTING.md" "${dst}/CONTRIBUTING.md"
    sync_file "${MONO_ROOT}/SECURITY.md" "${dst}/SECURITY.md"
    sync_file "${MONO_ROOT}/CODE_OF_CONDUCT.md" "${dst}/CODE_OF_CONDUCT.md"
    sync_file "${MONO_ROOT}/.gitignore" "${dst}/.gitignore"

    # Sanitize
    sanitize_public "${dst}"

    ok "auraboot synced"
}

# ─────────────────── Enterprise Repo ───────────────────

sync_enterprise() {
    log "Syncing auraboot-enterprise (private)..."
    local dst="${DIR_ENTERPRISE}"

    sync_file "${MONO_ROOT}/platform/platform-enterprise-comm" "${dst}/platform-enterprise-comm"
    sync_file "${MONO_ROOT}/platform/platform-enterprise-infra" "${dst}/platform-enterprise-infra"

    ok "auraboot-enterprise synced"
}

# ─────────────────── Solutions Repo ───────────────────

sync_solutions() {
    log "Syncing auraboot-solutions (private)..."
    local dst="${DIR_SOLUTIONS}"

    # Commercial plugins (all except public ones)
    local public_plugins="crm-starter e2e-test-order cli schemas templates scripts platform"

    for plugin_dir in "${MONO_ROOT}"/plugins/*/; do
        local plugin_name=$(basename "${plugin_dir}")

        # Skip public plugins
        local is_public=false
        for pp in ${public_plugins}; do
            if [ "${plugin_name}" = "${pp}" ]; then
                is_public=true
                break
            fi
        done

        if [ "${is_public}" = false ]; then
            sync_file "${plugin_dir}" "${dst}/plugins/${plugin_name}"
        fi
    done

    # Mobile apps
    [ -d "${MONO_ROOT}/apps" ] && sync_file "${MONO_ROOT}/apps" "${dst}/apps"

    # Business docs
    [ -d "${MONO_ROOT}/docs/business" ] && sync_file "${MONO_ROOT}/docs/business" "${dst}/docs/business"

    # Vendor (SmartEngine)
    [ -d "${MONO_ROOT}/vendor" ] && sync_file "${MONO_ROOT}/vendor" "${dst}/vendor"

    ok "auraboot-solutions synced"
}

# ─────────────────── Meta Repo ───────────────────

sync_meta() {
    log "Syncing auraboot-meta (private)..."
    local dst="${DIR_META}"

    # AI config
    mkdir -p "${dst}/ai"
    [ -f "${MONO_ROOT}/CLAUDE.md" ] && cp -f "${MONO_ROOT}/CLAUDE.md" "${dst}/ai/CLAUDE.md"
    [ -f "${MONO_ROOT}/AGENTS.md" ] && cp -f "${MONO_ROOT}/AGENTS.md" "${dst}/ai/AGENTS.md"

    # Skills (from .claude directory or superpowers skills)
    if [ -d "${MONO_ROOT}/.claude/skills" ]; then
        sync_file "${MONO_ROOT}/.claude/skills" "${dst}/ai/skills"
    fi

    # Architecture docs (from system-reference)
    if [ -d "${MONO_ROOT}/docs/system-reference" ]; then
        sync_file "${MONO_ROOT}/docs/system-reference/core" "${dst}/architecture/core"
        sync_file "${MONO_ROOT}/docs/system-reference/subsystems" "${dst}/architecture/subsystems"
        sync_file "${MONO_ROOT}/docs/system-reference/plugins" "${dst}/architecture/plugins"
        sync_file "${MONO_ROOT}/docs/system-reference/standards" "${dst}/architecture/standards"
        sync_file "${MONO_ROOT}/docs/system-reference/reference" "${dst}/architecture/reference"
        [ -d "${MONO_ROOT}/docs/system-reference/guides" ] && \
            sync_file "${MONO_ROOT}/docs/system-reference/guides" "${dst}/docs/internal/guides"
        [ -d "${MONO_ROOT}/docs/system-reference/walkthroughs" ] && \
            sync_file "${MONO_ROOT}/docs/system-reference/walkthroughs" "${dst}/docs/internal/walkthroughs"
    fi

    # Product docs (plans + specs)
    [ -d "${MONO_ROOT}/docs/plans" ] && sync_file "${MONO_ROOT}/docs/plans" "${dst}/product/plans"
    [ -d "${MONO_ROOT}/docs/superpowers/specs" ] && sync_file "${MONO_ROOT}/docs/superpowers/specs" "${dst}/product/specs"
    [ -d "${MONO_ROOT}/docs/superpowers/plans" ] && sync_file "${MONO_ROOT}/docs/superpowers/plans" "${dst}/product/plans"

    # Mobile docs
    [ -d "${MONO_ROOT}/docs/mobile" ] && sync_file "${MONO_ROOT}/docs/mobile" "${dst}/docs/internal/mobile"

    # E2E docs
    [ -d "${MONO_ROOT}/docs/e2e" ] && sync_file "${MONO_ROOT}/docs/e2e" "${dst}/docs/internal/e2e"

    ok "auraboot-meta synced"
}

# ─────────────────── Commit & Push ───────────────────

commit_and_push() {
    local dir="$1"
    local name="$2"

    cd "${dir}"

    if [ -z "$(git status --porcelain)" ]; then
        log "${name}: no changes"
        return
    fi

    git add -A
    git commit -m "${COMMIT_MSG}"

    if [ "${MODE}" = "--execute" ]; then
        git push origin main 2>/dev/null || git push origin master 2>/dev/null || warn "${name}: push failed (check remote branch)"
        ok "${name}: pushed"
    else
        ok "${name}: committed (dry-run, not pushed)"
    fi
}

# ─────────────────── Main ───────────────────

main() {
    log "AuraBoot Repo Publisher"
    log "Mode: ${MODE}"
    log "Monorepo: ${MONO_ROOT}"
    log "Workspace: ${WORKSPACE}"
    echo ""

    if [ "${MODE}" = "--init" ]; then
        init_workspace
        exit 0
    fi

    # Verify workspace exists
    if [ ! -d "${DIR_PUBLIC}" ] || [ ! -d "${DIR_ENTERPRISE}" ] || [ ! -d "${DIR_SOLUTIONS}" ] || [ ! -d "${DIR_META}" ]; then
        err "Workspace not initialized. Run: $0 --init"
        exit 1
    fi

    # Sync all repos
    sync_public
    sync_enterprise
    sync_solutions
    sync_meta

    echo ""
    log "─── Summary ───"

    # Show diff stats
    for dir in "${DIR_PUBLIC}" "${DIR_ENTERPRISE}" "${DIR_SOLUTIONS}" "${DIR_META}"; do
        local name=$(basename "${dir}")
        cd "${dir}"
        local changes=$(git status --porcelain | wc -l | tr -d ' ')
        if [ "${changes}" -gt 0 ]; then
            log "${name}: ${changes} files changed"
        else
            log "${name}: no changes"
        fi
    done

    echo ""

    if [ "${MODE}" = "--execute" ]; then
        log "Committing and pushing..."
        commit_and_push "${DIR_PUBLIC}" "auraboot"
        commit_and_push "${DIR_ENTERPRISE}" "auraboot-enterprise"
        commit_and_push "${DIR_SOLUTIONS}" "auraboot-solutions"
        commit_and_push "${DIR_META}" "auraboot-meta"
        echo ""
        ok "All repos published!"
    else
        warn "Dry-run mode. Use --execute to commit and push."
        warn "Review changes in ${WORKSPACE}/ before executing."
    fi
}

main
