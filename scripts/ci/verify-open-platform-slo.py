"""Independently reconcile actual k6 samples and thresholds for the public business SLO."""
import argparse
import json
import math
from pathlib import Path

OPERATIONS = ("create", "replay", "get", "list", "query", "update", "delete")


def verify_summary(summary, *, profile, require_business=True):
    if not isinstance(summary, dict) or summary.get("profile") != profile:
        raise ValueError("SLO profile identity invalid")
    if profile not in {"smoke", "production"}:
        raise ValueError("SLO profile unsupported")
    metrics = summary.get("metrics")
    if not isinstance(metrics, dict): raise ValueError("SLO metrics missing")

    def value(metric, key):
        row = metrics.get(metric)
        if not isinstance(row, dict): raise ValueError("SLO required metric missing: " + metric)
        result = row.get("values", {}).get(key)
        if type(result) not in (float, int) or not math.isfinite(result) or result < 0:
            raise ValueError("SLO sample invalid: " + metric)
        thresholds = row.get("thresholds")
        if not isinstance(thresholds, dict) or not thresholds or any(item.get("ok") is not True for item in thresholds.values()):
            raise ValueError("SLO threshold evidence missing or failed: " + metric)
        return result

    def samples(metric):
        yes, no = value(metric, "passes"), value(metric, "fails")
        if yes != int(yes) or no != int(no) or yes + no <= 0:
            raise ValueError("SLO rate has no actual samples")
        if not math.isclose(value(metric, "rate"), yes / (yes + no), rel_tol=1e-9, abs_tol=1e-12):
            raise ValueError("SLO rate/sample values disagree")
        return yes + no

    error_limit = .005 if profile == "production" else .01
    latency_limit = 250 if profile == "production" else 800
    samples("open_api_errors")
    if value("open_api_errors", "rate") >= error_limit or value("open_api_duration", "p(95)") >= latency_limit:
        raise ValueError("SLO baseline not met")
    if profile == "production" and (value("open_api_duration", "p(99)") >= 500 or value("http_reqs", "rate") <= 20):
        raise ValueError("SLO production baseline not met")
    if not require_business:
        if profile != "smoke" or summary.get("scope") != "baseline-only": raise ValueError("SLO baseline-only claim invalid")
        return summary
    if summary.get("scope") != "dynamic-business": raise ValueError("SLO business workload did not execute")
    total = value("business_requests", "count")
    if total <= 0 or total != int(total) or samples("business_errors") != total or value("business_errors", "rate") >= error_limit:
        raise ValueError("SLO business workload failed or empty")
    if profile == "production" and value("business_requests", "rate") <= 20:
        raise ValueError("SLO business throughput not met")
    counts = []
    for operation in OPERATIONS:
        suffix = "{operation:" + operation + "}"
        count = value("business_requests" + suffix, "count")
        if count <= 0 or count != int(count) or samples("business_errors" + suffix) != count: raise ValueError("SLO operation has no actual samples")
        counts.append(count)
        if value("business_errors" + suffix, "rate") >= error_limit or value("business_duration" + suffix, "p(95)") >= latency_limit:
            raise ValueError("SLO business operation not met: " + operation)
        if profile == "production" and value("business_duration" + suffix, "p(99)") >= 500:
            raise ValueError("SLO business operation tail latency not met: " + operation)
    if sum(counts) != total: raise ValueError("SLO business sample denominator invalid")
    if not counts[0] >= counts[1] >= counts[2] >= counts[3] >= counts[4] >= counts[5] >= counts[6]:
        raise ValueError("SLO operation execution order invalid")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("summary")
    parser.add_argument("profile", choices=["smoke", "production"])
    parser.add_argument("--baseline-only", action="store_true")
    args = parser.parse_args()
    try:
        verify_summary(json.loads(Path(args.summary).read_text()), profile=args.profile, require_business=not args.baseline_only)
    except (ValueError, OSError, TypeError, KeyError) as error:
        raise SystemExit(str(error)) from None
    print("Actual SLO sample/threshold reconciliation passed")
