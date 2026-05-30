"""Tests for AuraBoot operators."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest
import requests

from airflow_provider_auraboot.operators.command import AuraBootCommandOperator
from airflow_provider_auraboot.operators.connector_sync import AuraBootConnectorSyncOperator
from airflow_provider_auraboot.operators.semantic_query import AuraBootSemanticQueryOperator


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mock_hook(run_return=None, run_side_effect=None):
    """Return a mock AuraBootHook."""
    mock = MagicMock()
    if run_side_effect is not None:
        mock.run.side_effect = run_side_effect
    else:
        mock.run.return_value = run_return or {}
    return mock


_CONTEXT = {}  # Minimal context stub


# ---------------------------------------------------------------------------
# AuraBootCommandOperator
# ---------------------------------------------------------------------------

class TestAuraBootCommandOperator:
    """Tests for AuraBootCommandOperator."""

    def test_execute_happy_path(self):
        op = AuraBootCommandOperator(
            task_id="test_cmd",
            command_code="sales.refresh",
            params={"as_of_date": "2026-05-30"},
        )
        mock_hook = _mock_hook(run_return={"commandRunPid": "pid-1", "status": "RUNNING", "result": None})
        with patch(
            "airflow_provider_auraboot.operators.command.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        assert result["commandRunPid"] == "pid-1"
        assert result["status"] == "RUNNING"
        mock_hook.run.assert_called_once_with(
            "POST",
            "/api/commands/run",
            {"code": "sales.refresh", "params": {"as_of_date": "2026-05-30"}},
        )

    def test_execute_with_empty_params(self):
        op = AuraBootCommandOperator(task_id="test_cmd", command_code="foo.bar")
        mock_hook = _mock_hook(run_return={"commandRunPid": "pid-2", "status": "SUCCESS"})
        with patch(
            "airflow_provider_auraboot.operators.command.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        assert result["commandRunPid"] == "pid-2"
        call_body = mock_hook.run.call_args[0][2]
        assert call_body["params"] == {}

    def test_execute_raises_on_api_error(self):
        op = AuraBootCommandOperator(task_id="test_cmd", command_code="foo.bar")
        http_err = requests.HTTPError(response=MagicMock(status_code=500))
        mock_hook = _mock_hook(run_side_effect=http_err)
        with patch(
            "airflow_provider_auraboot.operators.command.AuraBootHook",
            return_value=mock_hook,
        ):
            with pytest.raises(requests.HTTPError):
                op.execute(_CONTEXT)

    def test_template_fields_declared(self):
        assert "command_code" in AuraBootCommandOperator.template_fields
        assert "params" in AuraBootCommandOperator.template_fields


# ---------------------------------------------------------------------------
# AuraBootConnectorSyncOperator
# ---------------------------------------------------------------------------

class TestAuraBootConnectorSyncOperator:
    """Tests for AuraBootConnectorSyncOperator."""

    def test_execute_happy_path(self):
        op = AuraBootConnectorSyncOperator(
            task_id="test_sync",
            connector_pid="01HZQK...",
            mode="incremental",
        )
        mock_hook = _mock_hook(run_return={"syncRunPid": "run-1", "status": "RUNNING"})
        with patch(
            "airflow_provider_auraboot.operators.connector_sync.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        assert result["syncRunPid"] == "run-1"
        assert result["status"] == "RUNNING"
        mock_hook.run.assert_called_once_with(
            "POST",
            "/api/connector/sync-runs",
            {"connector_pid": "01HZQK...", "mode": "incremental"},
        )

    def test_execute_full_mode(self):
        op = AuraBootConnectorSyncOperator(
            task_id="test_sync",
            connector_pid="abc-pid",
            mode="full",
        )
        mock_hook = _mock_hook(run_return={"syncRunPid": "run-2", "status": "QUEUED"})
        with patch(
            "airflow_provider_auraboot.operators.connector_sync.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        call_body = mock_hook.run.call_args[0][2]
        assert call_body["mode"] == "full"

    def test_execute_raises_on_api_error(self):
        op = AuraBootConnectorSyncOperator(
            task_id="test_sync",
            connector_pid="pid",
            mode="incremental",
        )
        http_err = requests.HTTPError(response=MagicMock(status_code=500))
        mock_hook = _mock_hook(run_side_effect=http_err)
        with patch(
            "airflow_provider_auraboot.operators.connector_sync.AuraBootHook",
            return_value=mock_hook,
        ):
            with pytest.raises(requests.HTTPError):
                op.execute(_CONTEXT)

    def test_template_fields_declared(self):
        assert "connector_pid" in AuraBootConnectorSyncOperator.template_fields
        assert "mode" in AuraBootConnectorSyncOperator.template_fields


# ---------------------------------------------------------------------------
# AuraBootSemanticQueryOperator
# ---------------------------------------------------------------------------

class TestAuraBootSemanticQueryOperator:
    """Tests for AuraBootSemanticQueryOperator."""

    def test_execute_with_metric_shorthand(self):
        op = AuraBootSemanticQueryOperator(
            task_id="test_query",
            metric="revenue.daily",
        )
        mock_hook = _mock_hook(run_return={"rows": [{"date": "2026-05-30", "value": 1234}]})
        with patch(
            "airflow_provider_auraboot.operators.semantic_query.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        assert result["rows"][0]["value"] == 1234
        mock_hook.run.assert_called_once_with(
            "POST",
            "/api/semantic/query",
            {"metrics": ["revenue.daily"]},
        )

    def test_execute_with_full_request_body(self):
        op = AuraBootSemanticQueryOperator(
            task_id="test_query",
            request={"metrics": ["a", "b"], "filters": {"date": "2026-05-30"}},
        )
        mock_hook = _mock_hook(run_return={"rows": []})
        with patch(
            "airflow_provider_auraboot.operators.semantic_query.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        call_body = mock_hook.run.call_args[0][2]
        assert call_body == {"metrics": ["a", "b"], "filters": {"date": "2026-05-30"}}

    def test_execute_raises_when_no_metric_or_request(self):
        with pytest.raises(ValueError, match="metric.*request"):
            AuraBootSemanticQueryOperator(task_id="test_query")

    def test_execute_to_df_converts_rows(self):
        pytest.importorskip("pandas")
        import pandas as pd

        op = AuraBootSemanticQueryOperator(
            task_id="test_query",
            metric="revenue.daily",
            to_df=True,
        )
        mock_hook = _mock_hook(
            run_return={"rows": [{"date": "2026-05-30", "value": 100}, {"date": "2026-05-29", "value": 200}]}
        )
        with patch(
            "airflow_provider_auraboot.operators.semantic_query.AuraBootHook",
            return_value=mock_hook,
        ):
            result = op.execute(_CONTEXT)

        assert isinstance(result, pd.DataFrame)
        assert len(result) == 2
        assert list(result.columns) == ["date", "value"]

    def test_execute_raises_on_api_error(self):
        op = AuraBootSemanticQueryOperator(
            task_id="test_query",
            metric="revenue.daily",
        )
        http_err = requests.HTTPError(response=MagicMock(status_code=500))
        mock_hook = _mock_hook(run_side_effect=http_err)
        with patch(
            "airflow_provider_auraboot.operators.semantic_query.AuraBootHook",
            return_value=mock_hook,
        ):
            with pytest.raises(requests.HTTPError):
                op.execute(_CONTEXT)

    def test_template_fields_declared(self):
        assert "metric" in AuraBootSemanticQueryOperator.template_fields
        assert "request" in AuraBootSemanticQueryOperator.template_fields
        assert "to_df" in AuraBootSemanticQueryOperator.template_fields
