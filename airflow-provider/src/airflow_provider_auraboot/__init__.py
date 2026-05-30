"""AuraBoot Airflow provider package."""

from __future__ import annotations


def get_provider_info() -> dict:
    """Return provider metadata for Airflow's provider registry."""
    return {
        "package-name": "airflow-provider-auraboot",
        "name": "AuraBoot",
        "description": "Apache Airflow provider for AuraBoot — operators, sensors, hooks",
        "versions": ["0.1.0"],
        "connection-types": [
            {
                "connection-type": "auraboot",
                "hook-class-name": "airflow_provider_auraboot.hooks.auraboot.AuraBootHook",
            }
        ],
    }
