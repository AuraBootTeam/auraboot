"""AuraBootCommandOperator — trigger a DSL command in AuraBoot."""

from __future__ import annotations

from typing import Any, Optional

from airflow.models import BaseOperator

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook


class AuraBootCommandOperator(BaseOperator):
    """
    Trigger a DSL command in AuraBoot and return the run result.

    Posts to ``POST /api/commands/run`` with ``{"code": command_code, "params": params}``.

    :param command_code: AuraBoot command code (e.g. ``"sales.refresh_pipeline_mart"``).
    :param params: Optional parameter dict forwarded to the command.
    :param auraboot_conn_id: Airflow connection id for AuraBoot.
    """

    template_fields = ("command_code", "params")

    def __init__(
        self,
        *,
        command_code: str,
        params: Optional[dict] = None,
        auraboot_conn_id: str = "auraboot_default",
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.command_code = command_code
        self.params = params or {}
        self.auraboot_conn_id = auraboot_conn_id

    def execute(self, context: Any) -> dict:
        """
        Execute the command and return ``{commandRunPid, status, result}``.

        :raises requests.HTTPError: on non-2xx responses from AuraBoot.
        """
        hook = AuraBootHook(self.auraboot_conn_id)
        self.log.info(
            "Triggering AuraBoot command '%s' with params=%s", self.command_code, self.params
        )
        result = hook.run(
            "POST",
            "/api/commands/run",
            {"code": self.command_code, "params": self.params},
        )
        self.log.info(
            "Command '%s' returned commandRunPid=%s status=%s",
            self.command_code,
            result.get("commandRunPid"),
            result.get("status"),
        )
        return {
            "commandRunPid": result.get("commandRunPid"),
            "status": result.get("status"),
            "result": result.get("result"),
        }
