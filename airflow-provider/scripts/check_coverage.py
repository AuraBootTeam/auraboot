"""Enforce line and branch coverage for every provider source file."""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


def check_coverage(report: Path, source: Path, threshold: float = 90) -> list[str]:
    data = json.loads(report.read_text())
    files = {Path(name).resolve(): value for name, value in data["files"].items()}
    expected = {path.resolve() for path in source.rglob("*.py")}
    if not expected:
        raise ValueError("No provider source files found")
    issues = [f"Missing source coverage: {path}" for path in sorted(expected - files.keys())]
    for path in sorted(expected & files.keys()):
        value = files[path]
        summary = value["summary"]
        for label, covered_key, total_key, executed_key, missing_key in [
            ("lines", "covered_lines", "num_statements", "executed_lines", "missing_lines"),
            ("branches", "covered_branches", "num_branches", "executed_branches", "missing_branches"),
        ]:
            covered, total = summary[covered_key], summary[total_key]
            if type(covered) is not int or type(total) is not int or not 0 <= covered <= total:
                raise ValueError(f"Invalid {label} counts: {path}")
            if len(value[executed_key]) != covered or len(value[missing_key]) != total - covered:
                raise ValueError(f"Inconsistent {label} evidence: {path}")
            if label == 'lines' and total == 0:
                tree = ast.parse(path.read_text())
                executable = any(isinstance(node, ast.stmt) and not (
                    isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)
                    and isinstance(node.value.value, str)
                ) for node in ast.walk(tree))
                if executable:
                    raise ValueError(f"Executable source has an empty line denominator: {path}")
            rate = 100 * covered / total if total else 100
            if rate < threshold:
                issues.append(f"{path}: {label} {covered}/{total} = {rate:.2f}% < {threshold}%")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--source", type=Path, default=Path("src/airflow_provider_auraboot"))
    args = parser.parse_args()
    try:
        issues = check_coverage(args.report, args.source)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Coverage evidence invalid: {error}")
        return 2
    if issues:
        print("\n".join(issues))
        return 1
    print("Every provider source file meets 90% line and branch coverage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
