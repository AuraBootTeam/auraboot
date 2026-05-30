"""AuraBootConnectorSyncOperator — trigger a connector sync run in AuraBoot."""

from __future__ import annotations

from typing import Any, Literal

from airflow.models import BaseOperator

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook


class AuraBootConnectorSyncOperator(BaseOperator):
    """
    Trigger a connector sync run in AuraBoot and return the run descriptor.

    Posts to ``POST /api/connector/sync-runs`` and returns
    ``{syncRunPid, status}``.

    :param connector_pid: AuraBoot connector PID (ULID).
    :param mode: Sync mode — ``'full'`` or ``'incremental'``.
    :param auraboot_conn_id: Airflow connection id for AuraBoot.
    """

    template_fields = ("connector_pid", "mode")

    def __init__(
        self,
        *,
        connector_pid: str,
        mode: Literal["full", "incremental"] = "incremental",
        auraboot_conn_id: str = "auraboot_default",
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.connector_pid = connector_pid
        self.mode = mode
        self.auraboot_conn_id = auraboot_conn_id

    def execute(self, context: Any) -> dict:
        """
        Trigger the sync and return ``{syncRunPid, status}``.

        :raises requests.HTTPError: on non-2xx responses from AuraBoot.
        """
        hook = AuraBootHook(self.auraboot_conn_id)
        self.log.info(
            "Triggering AuraBoot connector sync for connector_pid='%s' mode='%s'",
            self.connector_pid,
            self.mode,
        )
        result = hook.run(
            "POST",
            "/api/connector/sync-runs",
            {"connector_pid": self.connector_pid, "mode": self.mode},
        )
        self.log.info(
            "Connector sync started syncRunPid=%s status=%s",
            result.get("syncRunPid"),
            result.get("status"),
        )
        return {
            "syncRunPid": result.get("syncRunPid"),
            "status": result.get("status"),
        }
