"""Exact-identity Linux release-container adapter for model-event recovery."""
import ipaddress
import json
import platform
import re
import subprocess
import urllib.parse

from open_platform_http import Client, ProtocolError
from open_platform_model_event_probe import observe, require


class DockerInfrastructure:
    def __init__(self, *, app, postgres, network, job_id, source_ref, image_digest, base_url, receiver_host):
        require(platform.system() == "Linux" and platform.machine() == "x86_64",
                "model release recovery requires admitted Linux x86_64")
        require(all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,100}", name) for name in [app, postgres, network]),
                "model release container name invalid")
        require(bool(job_id) and re.fullmatch(r"[a-f0-9]{40}", source_ref)
                and re.fullmatch(r"sha256:[a-f0-9]{64}", image_digest), "model release source identity invalid")
        self.app, self.postgres, self.network = app, postgres, network
        self.job_id, self.source_ref, self.image_digest = job_id, source_ref, image_digest
        self.base_url, self.receiver_host, self.crashed_id = base_url, receiver_host, None
        self.identity()

    def docker(self, *arguments):
        try:
            result = subprocess.run(["docker", *arguments], capture_output=True, text=True, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ProtocolError("model release container control failed") from None
        require(result.returncode == 0, "model release container command failed")
        return result.stdout

    def container(self, name, *, running=True):
        try:
            rows = json.loads(self.docker("inspect", name))
        except (ValueError, TypeError):
            raise ProtocolError("model release container identity malformed") from None
        require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict),
                "model release container identity ambiguous")
        row = rows[0]
        labels = row.get("Config", {}).get("Labels") or {}
        require(row.get("Name") == "/" + name and re.fullmatch(r"[a-f0-9]{64}", row.get("Id", ""))
                and labels.get("aura.job-id") == self.job_id and labels.get("aura.source-ref") == self.source_ref
                and row.get("State", {}).get("Running") is running
                and set(row.get("NetworkSettings", {}).get("Networks", {})) == {self.network},
                "model release container ownership or state mismatch")
        if name == self.app:
            require(row.get("Image") == self.image_digest, "model release image changed")
        return row

    def identity(self):
        row = self.container(self.app)
        parsed = urllib.parse.urlsplit(self.base_url)
        require(parsed.scheme == "http" and parsed.hostname == "127.0.0.1"
                and parsed.path in {"", "/"} and not parsed.query and not parsed.fragment
                and not parsed.username and not parsed.password and parsed.port is not None,
                "model release API must use owned loopback binding")
        require(row.get("NetworkSettings", {}).get("Ports", {}).get("6443/tcp")
                == [{"HostIp": "127.0.0.1", "HostPort": str(parsed.port)}],
                "model release API binding changed")
        networks = json.loads(self.docker("network", "inspect", self.network))
        require(isinstance(networks, list) and len(networks) == 1 and networks[0].get("Name") == self.network
                and networks[0].get("Driver") == "bridge", "model release bridge identity invalid")
        config = networks[0].get("IPAM", {}).get("Config", [])
        require(len(config) == 1 and config[0].get("Gateway") == self.receiver_host,
                "model receiver is not bound to owned bridge gateway")
        address = ipaddress.ip_address(self.receiver_host)
        require(address.version == 4 and address in ipaddress.ip_network("10.247.0.0/16"),
                "model receiver bridge outside admitted release pool")
        allowlist = [value for value in row.get("Config", {}).get("Env", [])
                     if value.startswith("AURA_SSRF_ALLOWED_PRIVATE_HOSTS=")]
        require(allowlist == ["AURA_SSRF_ALLOWED_PRIVATE_HOSTS=" + self.receiver_host],
                "model release receiver allowlist mismatch")
        generation = row.get("State", {}).get("StartedAt")
        require(isinstance(generation, str) and bool(generation), "model process generation missing")
        return {"runtimeId": row["Id"], "artifactDigest": row["Image"], "generation": generation}

    def crash(self, expected):
        require(self.identity() == expected, "model release process changed before crash")
        self.crashed_id = expected["runtimeId"]
        self.docker("kill", "--signal=KILL", self.crashed_id)
        stopped = self.container(self.app, running=False)
        require(stopped["Id"] == self.crashed_id and stopped.get("State", {}).get("ExitCode") == 137,
                "model release process did not terminate by owned crash")

    def resume(self):
        require(self.crashed_id is not None, "model release process was not crashed")
        stopped = self.container(self.app, running=False)
        require(stopped["Id"] == self.crashed_id, "model release stopped container replaced")
        self.docker("start", self.crashed_id)
        client = Client(self.base_url, timeout=5, max_requests=300)

        def ready():
            try:
                _, body, _ = client.request("/actuator/health")
            except ProtocolError:
                return False
            return isinstance(body, dict) and body.get("status") == "UP"

        observe(ready, seconds=180)
        require(self.identity()["runtimeId"] == self.crashed_id, "model release recovery replaced runtime")

    def sql(self, query):
        require(query.startswith("SELECT ") and ";" not in query, "model observation must be read-only")
        row = self.container(self.postgres)
        try:
            return json.loads(self.docker("exec", row["Id"], "psql", "-U", "auraboot", "-d", "open_platform_ci",
                                          "-At", "-v", "ON_ERROR_STOP=1", "-c", query))
        except (ValueError, TypeError):
            raise ProtocolError("model release database observation invalid") from None

    @staticmethod
    def tenant_id(tenant):
        require(type(tenant) is int and tenant > 0, "model event tenant identity invalid")
        return str(tenant)

    @staticmethod
    def identifier(value):
        require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,180}", value),
                "model event observation identifier invalid")
        return value

    def source(self, tenant, event_type, subject):
        tenant = self.tenant_id(tenant)
        event_type, subject = self.identifier(event_type), self.identifier(subject)
        return self.sql("SELECT json_build_object('eventId',event_id,'status',status,'retryCount',retry_count,"
                        "'subject',subject,'sequence',event_sequence) FROM ab_outbox WHERE tenant_id=" + tenant
                        + " AND event_type='" + event_type + "' AND subject='" + subject + "'")

    def counts(self, tenant, event_id):
        tenant, event_id = self.tenant_id(tenant), self.identifier(event_id)
        predicate = "tenant_id=" + tenant + " AND event_id='" + event_id + "'"
        return self.sql("SELECT json_build_object('events',(SELECT count(*) FROM ab_outbox WHERE " + predicate
                        + "),'receipts',(SELECT count(*) FROM ab_integration_receipt WHERE " + predicate
                        + " AND consumer_code='open-platform-model-webhook-v1' AND status='applied'),"
                        "'deliveries',(SELECT count(*) FROM ab_webhook_delivery_log WHERE " + predicate + "))")

    def poll_marker(self):
        result = self.sql("SELECT json_build_object('lastRun',last_run_at) FROM ab_scheduled_task WHERE pid='sys-outbox-poll'")
        require(isinstance(result, dict) and "lastRun" in result, "model source poll marker missing")
        return result["lastRun"]

    def load_state(self, tenant, event_type, subjects, subscription_pid):
        tenant, event_type = self.tenant_id(tenant), self.identifier(event_type)
        subscription_pid = self.identifier(subscription_pid)
        require(isinstance(subjects, list) and len(subjects) == 32
                and all(isinstance(subject, str) for subject in subjects) and len(set(subjects)) == 32,
                'model load observation denominator invalid')
        subjects = [self.identifier(subject) for subject in subjects]
        selected = ','.join("'" + subject + "'" for subject in subjects)
        predicate = 'd.tenant_id=' + tenant + ' AND d.event_id=o.event_id'
        target = predicate + " AND d.subscription_pid='" + subscription_pid + "'"
        def scalar(column):
            return '(SELECT max(d.' + column + ') FROM ab_webhook_delivery_log d WHERE ' + target + ')'
        return self.sql("SELECT json_build_object('sources',COALESCE((SELECT json_agg(json_build_object("
            "'subject',o.subject,'eventId',o.event_id,'status',o.status,'sequence',o.event_sequence,"
            "'receipts',(SELECT count(*) FROM ab_integration_receipt r WHERE r.tenant_id=" + tenant
            + " AND r.event_id=o.event_id AND r.consumer_code='open-platform-model-webhook-v1' AND r.status='applied'),"
            "'deliveries',(SELECT count(*) FROM ab_webhook_delivery_log d WHERE " + predicate + "),"
            "'deliveryPid'," + scalar('pid') + ",'deliveryStatus'," + scalar('delivery_status')
            + ",'retryCount'," + scalar('retry_count') + ",'responseStatus'," + scalar('response_status')
            + ')) FROM ab_outbox o WHERE o.tenant_id=' + tenant + " AND o.event_type='" + event_type
            + "' AND o.subject IN (" + selected + ")), '[]'::json))")
