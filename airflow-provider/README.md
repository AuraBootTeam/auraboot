# airflow-provider-auraboot

Apache Airflow provider for [AuraBoot](https://auraboot.com) — operators, sensors, and hooks for
integrating AuraBoot data platform pipelines with Airflow DAGs.

## Installation

```bash
pip install airflow-provider-auraboot
```

Or for development:

```bash
pip install -e .
```

## Connection Setup

Create a connection in Airflow with:

- **Conn Type**: `auraboot`
- **Host**: `https://auraboot.example.com`
- **Extra** (JSON):

```json
{
  "auth_method": "jwt",
  "jwt_token": "<your-jwt-token>",
  "hmac_secret": "<shared-secret-for-webhook-signing>"
}
```

Supported `auth_method` values: `jwt`, `api_key`, `hmac`.

## Quick Start

```python
from datetime import datetime
from airflow import DAG
from airflow_provider_auraboot.operators.command import AuraBootCommandOperator
from airflow_provider_auraboot.operators.connector_sync import AuraBootConnectorSyncOperator
from airflow_provider_auraboot.sensors.sync_complete import AuraBootSyncCompleteSensor

with DAG(
    dag_id="salesforce_daily_to_auraboot",
    start_date=datetime(2026, 5, 1),
    schedule="0 8 * * *",
    catchup=False,
) as dag:
    sync_sf = AuraBootConnectorSyncOperator(
        task_id="sync_salesforce",
        connector_pid="01HZQK...",
        mode="incremental",
        auraboot_conn_id="auraboot_prod",
    )
    wait = AuraBootSyncCompleteSensor(
        task_id="wait_sync_done",
        sync_run_pid="{{ ti.xcom_pull(task_ids='sync_salesforce')['syncRunPid'] }}",
        timeout=3600,
        mode="reschedule",
    )
    transform = AuraBootCommandOperator(
        task_id="rebuild_pipeline_mart",
        command_code="sales.refresh_pipeline_mart",
        params={"as_of_date": "{{ ds }}"},
    )
    sync_sf >> wait >> transform
```

## Operators

| Class | Description |
|-------|-------------|
| `AuraBootCommandOperator` | Trigger a DSL command (`/api/commands/run`) |
| `AuraBootConnectorSyncOperator` | Trigger a connector sync run (`/api/connector/sync-runs`) |
| `AuraBootSemanticQueryOperator` | Execute a semantic query (`/api/semantic/query`) |

## Sensors

| Class | Description |
|-------|-------------|
| `AuraBootSyncCompleteSensor` | Wait for a connector sync run to reach `SUCCESS` or `FAILED` |
| `AuraBootCommandStatusSensor` | Wait for a command run to reach `SUCCESS` or `FAILED` |

## Webhook Signing

Outbound webhook signing (for triggering AuraBoot from Airflow callbacks):

```python
from airflow_provider_auraboot.webhooks.sign import sign_webhook

signature = sign_webhook(body=b'{"event":"done"}', secret="shared-secret")
# Returns: "t=<unix_ts>,v1=<hmac-sha256-hex>"
```

The signature format is compatible with the Java `AirflowWebhookService.parseSignature` implementation.

## License

Apache 2.0
