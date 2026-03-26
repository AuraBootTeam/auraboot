#!/usr/bin/env bash
# notify.sh — Send performance regression result notifications.
#
# Usage: ./notify.sh <status> <summary>
#   status  : pass | warning | critical
#   summary : plain-text description of the result
#
# If PERF_ALERT_WEBHOOK is set, POSTs a JSON payload to the Slack-compatible
# incoming webhook URL.  Otherwise prints a coloured line to stdout.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <pass|warning|critical> <summary>" >&2
  exit 1
fi

STATUS="$1"
SUMMARY="$2"

if [[ -n "${PERF_ALERT_WEBHOOK:-}" ]]; then
  # Determine Slack message colour attachment
  case "$STATUS" in
    pass)     COLOR="#36a64f" ; ICON=":white_check_mark:" ;;
    warning)  COLOR="#ff9900" ; ICON=":warning:" ;;
    critical) COLOR="#cc0000" ; ICON=":rotating_light:" ;;
    *)        COLOR="#cccccc" ; ICON=":question:" ;;
  esac

  PAYLOAD=$(jq -n \
    --arg text "$ICON [AuraBoot Perf] $STATUS: $SUMMARY" \
    --arg color "$COLOR" \
    --arg fallback "$STATUS: $SUMMARY" \
    '{
      text: $text,
      attachments: [{
        fallback:   $fallback,
        color:      $color,
        text:       $fallback,
        mrkdwn_in: ["text"]
      }]
    }')

  curl -s -X POST "$PERF_ALERT_WEBHOOK" \
    -H 'Content-Type: application/json' \
    -d "$PAYLOAD"
  echo  # trailing newline after curl output
else
  case "$STATUS" in
    pass)     echo -e "\033[32m[PASS]\033[0m $SUMMARY" ;;
    warning)  echo -e "\033[33m[WARNING]\033[0m $SUMMARY" ;;
    critical) echo -e "\033[31m[CRITICAL]\033[0m $SUMMARY" ;;
    *)        echo "[UNKNOWN] $SUMMARY" ;;
  esac
fi
