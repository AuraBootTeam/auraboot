"""
Example DAG: Salesforce daily sync to AuraBoot.

This DAG demonstrates a typical integration pattern:
1. Trigger an incremental Salesforce connector sync in AuraBoot.
2. Wait for the sync run to complete (SUCCESS or FAILED).
3. Run a DSL command to refresh the downstream pipeline mart.

Prerequisites:
  - An AuraBoot connection named ``auraboot_prod`` configured in Airflow.
  - A Salesforce connector with pid ``01HZQK...`` registered in AuraBoot.
"""

from datetime import datetime

from airflow import DAG

from airflow_provider_auraboot.operators.command import AuraBootCommandOperator
from airflow_provider_auraboot.operators.connector_sync import AuraBootConnectorSyncOperator
from airflow_provider_auraboot.sensors.sync_complete import AuraBootSyncCompleteSensor

with DAG(
    dag_id="salesforce_daily_to_auraboot",
    description="Daily Salesforce incremental sync → pipeline mart refresh",
    start_date=datetime(2026, 5, 1),
    schedule="0 8 * * *",
    catchup=False,
    tags=["auraboot", "salesforce", "data-platform"],
) as dag:

    sync_sf = AuraBootConnectorSyncOperator(
        task_id="sync_salesforce",
        connector_pid="01HZQK...",
        mode="incremental",
        auraboot_conn_id="auraboot_prod",
    )

    wait = AuraBootSyncCompleteSensor(
        task_id="wait_sync_done",
        # XCom pull from the previous task's returned dict.
        sync_run_pid="{{ ti.xcom_pull(task_ids='sync_salesforce')['syncRunPid'] }}",
        auraboot_conn_id="auraboot_prod",
        timeout=3600,
        poke_interval=30,
        mode="reschedule",
    )

    transform = AuraBootCommandOperator(
        task_id="rebuild_pipeline_mart",
        command_code="sales.refresh_pipeline_mart",
        params={"as_of_date": "{{ ds }}"},
        auraboot_conn_id="auraboot_prod",
    )

    sync_sf >> wait >> transform
