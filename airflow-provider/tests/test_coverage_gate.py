"""Falsification tests for the per-file coverage gate."""
import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("coverage_gate", Path(__file__).parents[1] / "scripts/check_coverage.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def fixture(tmp_path, covered_lines=9, covered_branches=9):
    source = tmp_path / "src"
    source.mkdir()
    path = source / "behavior.py"
    path.write_text("def behavior():\n    return True\n")
    record = {
        "summary": {"covered_lines": covered_lines, "num_statements": 10,
                    "covered_branches": covered_branches, "num_branches": 10},
        "executed_lines": list(range(covered_lines)), "missing_lines": list(range(covered_lines, 10)),
        "executed_branches": [[n, n+1] for n in range(covered_branches)],
        "missing_branches": [[n, n+1] for n in range(covered_branches, 10)],
    }
    data = {"files": {str(path): record}}
    report = tmp_path / "report.json"
    report.write_text(json.dumps(data))
    return source, report, data


def test_exact_threshold_passes(tmp_path):
    source, report, _ = fixture(tmp_path)
    assert gate.check_coverage(report, source) == []


@pytest.mark.parametrize("lines,branches,label", [(8, 10, "lines"), (10, 8, "branches"), (0, 0, "lines")])
def test_either_axis_below_target_fails(tmp_path, lines, branches, label):
    source, report, _ = fixture(tmp_path, lines, branches)
    assert any(label in issue for issue in gate.check_coverage(report, source))


def test_uninstrumented_source_cannot_disappear(tmp_path):
    source, report, _ = fixture(tmp_path)
    (source / "forgotten.py").write_text("raise RuntimeError('untested')\n")
    assert any("forgotten.py" in issue for issue in gate.check_coverage(report, source))


def test_empty_source_is_not_green(tmp_path):
    source, report, _ = fixture(tmp_path)
    with pytest.raises(ValueError, match="No provider source"):
        gate.check_coverage(report, source / "does-not-exist")


def test_inflated_summary_without_matching_execution_evidence_is_rejected(tmp_path):
    source, report, data = fixture(tmp_path, 8, 8)
    next(iter(data["files"].values()))["summary"]["covered_lines"] = 10
    report.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="Inconsistent lines evidence"):
        gate.check_coverage(report, source)


def test_impossible_counts_are_rejected(tmp_path):
    source, report, data = fixture(tmp_path)
    next(iter(data["files"].values()))["summary"]["covered_branches"] = 11
    report.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="Invalid branches counts"):
        gate.check_coverage(report, source)


def test_files_with_no_branches_do_not_need_invented_branches(tmp_path):
    source, report, data = fixture(tmp_path)
    record = next(iter(data["files"].values()))
    record["summary"].update(covered_branches=0, num_branches=0)
    record.update(executed_branches=[], missing_branches=[])
    report.write_text(json.dumps(data))
    assert gate.check_coverage(report, source) == []


def test_executable_source_cannot_claim_an_empty_line_denominator(tmp_path):
    source, report, data = fixture(tmp_path)
    record = next(iter(data["files"].values()))
    record["summary"].update(covered_lines=0, num_statements=0)
    record.update(executed_lines=[], missing_lines=[])
    report.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="empty line denominator"):
        gate.check_coverage(report, source)
