#!/bin/bash
#
# Sourceable helper for per-worktree Maven local publishing.
#
# Usage:
#   source scripts/dev/maven-local-export.sh
#   ./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
#
# Downstream builds must use the same repo:
#   GRADLE_OPTS="-Dmaven.repo.local=$AURA_MAVEN_REPO" ./gradlew bootJar

__maven_local_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __maven_local_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __maven_local_sourced=1
fi

if [ "$__maven_local_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: maven-local-export.sh must be sourced, not executed.

  Use:    source scripts/dev/maven-local-export.sh
  Not:    bash scripts/dev/maven-local-export.sh
HINT
    exit 2
fi

unset __maven_local_sourced

__maven_repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
export AURA_MAVEN_REPO="${AURA_MAVEN_REPO:-$__maven_repo_root/.m2/repository}"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dmaven.repo.local=$AURA_MAVEN_REPO"

cat <<SUMMARY
✓ per-worktree Maven local loaded
  AURA_MAVEN_REPO = $AURA_MAVEN_REPO
  GRADLE_OPTS     = $GRADLE_OPTS
SUMMARY

unset __maven_repo_root
