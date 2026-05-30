"""AuraBootSemanticQueryOperator — execute a semantic query in AuraBoot."""

from __future__ import annotations

from typing import Any, Optional, Union

from airflow.models import BaseOperator

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook


class AuraBootSemanticQueryOperator(BaseOperator):
    """
    Execute a semantic query against AuraBoot's metric layer.

    Posts to ``POST /api/semantic/query``.

    Either ``metric`` (single metric shorthand) or ``request`` (full query body)
    must be provided.  When ``to_df=True`` the result rows are returned as a
    ``pandas.DataFrame``; otherwise the raw API response dict is returned.

    :param metric: Single metric name shorthand (wrapped as ``{"metrics": [metric]}``).
    :param request: Full request body dict — takes precedence over ``metric`` when
        both are supplied.
    :param to_df: When ``True``, convert ``result["rows"]`` to a
        ``pandas.DataFrame``.  Requires ``pandas`` to be installed.
    :param auraboot_conn_id: Airflow connection id for AuraBoot.
    """

    template_fields = ("metric", "request", "to_df")

    def __init__(
        self,
        *,
        metric: Optional[str] = None,
        request: Optional[dict] = None,
        to_df: bool = False,
        auraboot_conn_id: str = "auraboot_default",
        **kwargs: Any,
    ) -> None:
        if metric is None and request is None:
            raise ValueError(
                "AuraBootSemanticQueryOperator requires either 'metric' or 'request'"
            )
        super().__init__(**kwargs)
        self.metric = metric
        self.request = request
        self.to_df = to_df
        self.auraboot_conn_id = auraboot_conn_id

    def execute(self, context: Any) -> Union[dict, Any]:
        """
        Run the semantic query.

        :return: Raw API response dict, or a ``pandas.DataFrame`` when
            ``to_df=True``.
        :raises requests.HTTPError: on non-2xx responses from AuraBoot.
        :raises ImportError: when ``to_df=True`` but ``pandas`` is not installed.
        """
        hook = AuraBootHook(self.auraboot_conn_id)
        body = self.request if self.request is not None else {"metrics": [self.metric]}
        self.log.info("Executing AuraBoot semantic query body=%s", body)
        result = hook.run("POST", "/api/semantic/query", body)
        self.log.info(
            "Semantic query returned %d rows", len(result.get("rows", []))
        )
        if self.to_df:
            try:
                import pandas as pd  # noqa: PLC0415 — optional dep, lazy import
            except ImportError as exc:
                raise ImportError(
                    "pandas is required when to_df=True. "
                    "Install it with: pip install pandas"
                ) from exc
            return pd.DataFrame(result.get("rows", []))
        return result
