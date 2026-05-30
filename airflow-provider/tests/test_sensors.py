"""Tests for AuraBoot sensors."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
import requests

from airflow_provider_auraboot.sensors.command_status import AuraBootCommandStatusSensor
from airflow_provider_auraboot.sensors.sync_complete import AuraBootSyncCompleteSensor


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mock_hook(run_return=None, run_side_effect=None):
    mock = MagicMock()
    if run_side_effect is not None:
        mock.run.side_effect = run_side_effect
    else:
        mock.run.return_value = run_return or {}
    return mock


_CONTEXT = {}


# ---------------------------------------------------------------------------
# AuraBootSyncCompleteSensor
# ---------------------------------------------------------------------------

class TestAuraBootSyncCompleteSensor:
    """Tests for AuraBootSyncCompleteSensor."""

    def test_poke_returns_false_when_running(self):
        sensor = AuraBootSyncCompleteSensor(
            task_id="wait_sync",
            sync_run_pid="run-123",
        )
        mock_hook = _mock_hook(run_return={"syncRunPid": "run-123", "status": "RUNNING"})
        with patch(
            "airflow_provider_auraboot.sensors.sync_complete.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is False
        mock_hook.run.assert_called_once_with("GET", "/api/connector/sync-runs/run-123")

    def test_poke_returns_true_when_success(self):
        sensor = AuraBootSyncCompleteSensor(
            task_id="wait_sync",
            sync_run_pid="run-123",
        )
        mock_hook = _mock_hook(run_return={"syncRunPid": "run-123", "status": "SUCCESS"})
        with patch(
            "airflow_provider_auraboot.sensors.sync_complete.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is True

    def test_poke_returns_true_when_failed(self):
        sensor = AuraBootSyncCompleteSensor(
            task_id="wait_sync",
            sync_run_pid="run-999",
        )
        mock_hook = _mock_hook(run_return={"syncRunPid": "run-999", "status": "FAILED"})
        with patch(
            "airflow_provider_auraboot.sensors.sync_complete.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is True

    def test_poke_raises_on_api_error(self):
        sensor = AuraBootSyncCompleteSensor(
            task_id="wait_sync",
            sync_run_pid="run-err",
        )
        http_err = requests.HTTPError(response=MagicMock(status_code=404))
        mock_hook = _mock_hook(run_side_effect=http_err)
        with patch(
            "airflow_provider_auraboot.sensors.sync_complete.AuraBootHook",
            return_value=mock_hook,
        ):
            with pytest.raises(requests.HTTPError):
                sensor.poke(_CONTEXT)

    def test_template_fields_declared(self):
        assert "sync_run_pid" in AuraBootSyncCompleteSensor.template_fields


# ---------------------------------------------------------------------------
# AuraBootCommandStatusSensor
# ---------------------------------------------------------------------------

class TestAuraBootCommandStatusSensor:
    """Tests for AuraBootCommandStatusSensor."""

    def test_poke_returns_false_when_running(self):
        sensor = AuraBootCommandStatusSensor(
            task_id="wait_cmd",
            command_run_pid="cmd-run-1",
        )
        mock_hook = _mock_hook(run_return={"commandRunPid": "cmd-run-1", "status": "RUNNING"})
        with patch(
            "airflow_provider_auraboot.sensors.command_status.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is False
        mock_hook.run.assert_called_once_with("GET", "/api/command-runs/cmd-run-1")

    def test_poke_returns_true_when_success(self):
        sensor = AuraBootCommandStatusSensor(
            task_id="wait_cmd",
            command_run_pid="cmd-run-2",
        )
        mock_hook = _mock_hook(run_return={"commandRunPid": "cmd-run-2", "status": "SUCCESS"})
        with patch(
            "airflow_provider_auraboot.sensors.command_status.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is True

    def test_poke_returns_true_when_failed(self):
        sensor = AuraBootCommandStatusSensor(
            task_id="wait_cmd",
            command_run_pid="cmd-run-3",
        )
        mock_hook = _mock_hook(run_return={"commandRunPid": "cmd-run-3", "status": "FAILED"})
        with patch(
            "airflow_provider_auraboot.sensors.command_status.AuraBootHook",
            return_value=mock_hook,
        ):
            result = sensor.poke(_CONTEXT)

        assert result is True

    def test_poke_raises_on_api_error(self):
        sensor = AuraBootCommandStatusSensor(
            task_id="wait_cmd",
            command_run_pid="cmd-err",
        )
        http_err = requests.HTTPError(response=MagicMock(status_code=500))
        mock_hook = _mock_hook(run_side_effect=http_err)
        with patch(
            "airflow_provider_auraboot.sensors.command_status.AuraBootHook",
            return_value=mock_hook,
        ):
            with pytest.raises(requests.HTTPError):
                sensor.poke(_CONTEXT)

    def test_template_fields_declared(self):
        assert "command_run_pid" in AuraBootCommandStatusSensor.template_fields
