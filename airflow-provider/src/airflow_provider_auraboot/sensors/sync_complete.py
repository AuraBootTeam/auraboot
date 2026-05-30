"""AuraBootSyncCompleteSensor — wait for a connector sync run to finish."""

from __future__ import annotations

from typing import Any

from airflow.sensors.base import BaseSensorOperator

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook

_TERMINAL_STATUSES = {"SUCCESS", "FAILED"}


class AuraBootSyncCompleteSensor(BaseSensorOperator):
    """
    Poke ``GET /api/connector/sync-runs/{sync_run_pid}`` until
    ``status`` is ``SUCCESS`` or ``FAILED``.

    :param sync_run_pid: PID of the connector sync run to monitor.
    :param auraboot_conn_id: Airflow connection id for AuraBoot.
    """

    template_fields = ("sync_run_pid",)

    def __init__(
        self,
        *,
        sync_run_pid: str,
        auraboot_conn_id: str = "auraboot_default",
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.sync_run_pid = sync_run_pid
        self.auraboot_conn_id = auraboot_conn_id

    def poke(self, context: Any) -> bool:
        """
        Return ``True`` when the sync run has reached a terminal status.

        :raises requests.HTTPError: on non-2xx responses from AuraBoot.
        """
        hook = AuraBootHook(self.auraboot_conn_id)
        result = hook.run("GET", f"/api/connector/sync-runs/{self.sync_run_pid}")
        status = result.get("status")
        self.log.info(
            "AuraBootSyncCompleteSensor poke syncRunPid=%s status=%s",
            self.sync_run_pid,
            status,
        )
        return status in _TERMINAL_STATUSES
