#!/usr/bin/env bash

# The product release-image gate already reserves 10.247.0.0/16 for isolated
# /24 bridges. Reuse that pool instead of consuming Docker's default pools.
# Docker arbitrates overlapping allocations across concurrent jobs.
create_isolated_release_network() {
  local network="$1" receipt="$2" subnet_index subnet response status
  [[ "$network" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo 'invalid release network name' >&2; return 2; }
  for subnet_index in $(seq 0 255); do
    subnet="10.247.${subnet_index}.0/24"
    if response="$(docker network create --subnet "$subnet" "$network" 2>&1)"; then
      [[ "$response" =~ ^[a-f0-9]{64}$ ]] || { echo 'release network ID is invalid; created network preserved' >&2; return 2; }
      printf 'network\tsubnet\tid\n%s\t%s\t%s\n' "$network" "$subnet" "$response" > "$receipt" || return 2
      return 0
    else
      status=$?
      case "$response" in
        *'Pool overlaps with other one'*|*'pool overlaps with other one'*) continue;;
        *) printf 'release network allocation failed (exit %s): %s\n' "$status" "$response" >&2; return 2;;
      esac
    fi
  done
  echo 'no free isolated release-image network in 10.247.0.0/16' >&2
  return 2
}
