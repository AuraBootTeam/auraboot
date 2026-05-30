"""AuraBootCommandStatusSensor — wait for a command run to finish."""

from __future__ import annotations

from typing import Any

from airflow.sensors.base import BaseSensorOperator

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook

_TERMINAL_STATUSES = {"SUCCESS", "FAILED"}


class AuraBootCommandStatusSensor(BaseSensorOperator):
    """
    Poke ``GET /api/command-runs/{command_run_pid}`` until
    ``status`` is ``SUCCESS`` or ``FAILED``.

    :param command_run_pid: PID of the command run to monitor.
    :param auraboot_conn_id: Airflow connection id for AuraBoot.
    """

    template_fields = ("command_run_pid",)

    def __init__(
        self,
        *,
        command_run_pid: str,
        auraboot_conn_id: str = "auraboot_default",
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.command_run_pid = command_run_pid
        self.auraboot_conn_id = auraboot_conn_id

    def poke(self, context: Any) -> bool:
        """
        Return ``True`` when the command run has reached a terminal status.

        :raises requests.HTTPError: on non-2xx responses from AuraBoot.
        """
        hook = AuraBootHook(self.auraboot_conn_id)
        result = hook.run("GET", f"/api/command-runs/{self.command_run_pid}")
        status = result.get("status")
        self.log.info(
            "AuraBootCommandStatusSensor poke commandRunPid=%s status=%s",
            self.command_run_pid,
            status,
        )
        return status in _TERMINAL_STATUSES
