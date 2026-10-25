#!/usr/bin/env python3
"""Create and verify product release-archive receipts (schemaVersion 3).

This is the product-generic counterpart of the schemaVersion-1
``release-image-receipt.json`` emitted by ``run-product-release-image-gate.sh``.
It seals the exact frozen source set, the offline image archive and the deploy
control bundle of a completed Linux CI gate run into a create-once receipt that
a later release publisher can bind to a qualified release record.

The receipt kind is derived from the product id (``<product>-release-image-receipt``);
the first admitted product line is aura-bpm. Quote/BOM keeps its own dedicated
receipt tool and contract; this tool never emits that product's receipt.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

IMAGE_REF = re.compile(r"^sha256:[0-9a-f]{64}$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
# Only products with an explicitly reviewed release contract may be sealed.
# The quote-bom product line owns a separate, dedicated receipt contract.
ADMITTED_PRODUCTS = {"aura-bpm": {"sourceKey": "bpm", "sourceRepository": "aura-bpm",
                                  "imageKeys": ("application",)}}
RESERVED_PRODUCT_KEYS = ("quote-bom",)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(path, *args):
    return subprocess.check_output(
        ["git", "-C", str(path), *args], text=True, stderr=subprocess.DEVNULL
    ).strip()


def parse_assignments(values, expected_keys, *, require_directory=False):
    parsed = {}
    for value in values:
        key, separator, raw_path = value.partition("=")
        if not separator or key not in expected_keys or key in parsed:
            raise ValueError(f"invalid or duplicate assignment: {value}")
        path = Path(raw_path).resolve()
        if require_directory:
            if not path.is_dir():
                raise ValueError(f"assigned path is not a directory: {path}")
        elif not path.is_file():
            raise ValueError(f"assigned path is not a regular file: {path}")
        parsed[key] = path
    missing = sorted(set(expected_keys) - set(parsed))
    if missing:
        raise ValueError("missing assignments: " + ", ".join(missing))
    return parsed


def source_record(path):
    if git(path, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError(f"source checkout is dirty: {path}")
    oid = git(path, "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", oid):
        raise ValueError(f"source checkout has invalid HEAD: {path}")
    return {"oid": oid}


def read_image_refs(path, image_keys):
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key in ("IMAGE_ID", "OCI_LAYOUT_DIGEST"):
            if key in values:
                raise ValueError(f"duplicate image identity: {key}")
            values[key] = value
    missing = sorted({"IMAGE_ID"} - set(values))
    if missing:
        raise ValueError("image ID file is incomplete: " + ", ".join(missing))
    ref = values["IMAGE_ID"]
    if not IMAGE_REF.fullmatch(ref):
        raise ValueError(f"image ID is not immutable: {ref}")
    images = {key: {"ref": ref} for key in image_keys}
    if "OCI_LAYOUT_DIGEST" in values:
        if not IMAGE_REF.fullmatch(values["OCI_LAYOUT_DIGEST"]):
            raise ValueError(f"OCI layout digest is not immutable: {values['OCI_LAYOUT_DIGEST']}")
        for entry in images.values():
            entry["layoutDigest"] = values["OCI_LAYOUT_DIGEST"]
    return images


def atomic_json(path, payload):
    path = Path(path).absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".receipt-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o444)
        # Atomic create-once: never replace a receipt or follow an output symlink.
        os.link(temporary, path)
    finally:
        os.unlink(temporary)


def source_repository_ids(product):
    contract = ADMITTED_PRODUCTS[product]
    return {"core": "auraboot", contract["sourceKey"]: contract["sourceRepository"]}


def validate_frozen_inputs(evidence, sources, product, ci_job_id):
    if not isinstance(evidence, dict) or evidence.get("contractVersion") != 1:
        raise ValueError("frozen inputs contractVersion must be 1")
    if not isinstance(ci_job_id, str) or not SAFE_ID.fullmatch(ci_job_id) or evidence.get("ciJobId") != ci_job_id:
        raise ValueError("frozen inputs CI job mismatch")
    for key in ("sourceSetDigest", "manifestDigest", "suiteDigest"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(evidence.get(key, ""))):
            raise ValueError(f"frozen inputs {key} is invalid")
    expected = source_repository_ids(product)
    repositories = evidence.get("repositories")
    if not isinstance(repositories, dict) or set(repositories) != set(expected.values()):
        raise ValueError("frozen inputs must contain exactly the declared application repositories")
    if not isinstance(sources, dict) or set(sources) != set(expected):
        raise ValueError("receipt must contain exactly the declared application sources")
    for key, repository in expected.items():
        if (not isinstance(sources[key], dict)
                or not re.fullmatch(r"[0-9a-f]{40}", str(repositories[repository]))
                or repositories[repository] != sources[key].get("oid")):
            raise ValueError(f"frozen inputs source OID mismatch: {key}")
    builders = evidence.get("builderRepositories")
    if not isinstance(builders, dict) or set(builders) != {"auraboot-workspace"}:
        raise ValueError("frozen inputs must identify the Workspace builder")
    if not re.fullmatch(r"[0-9a-f]{40}", str(builders["auraboot-workspace"])):
        raise ValueError("frozen inputs builder commit is invalid")
    executor = evidence.get("executor", {})
    if (not isinstance(executor, dict) or executor.get("repository") != "auraboot-workspace"
            or executor.get("commit") != builders["auraboot-workspace"]
            or executor.get("path") != "tools/regression/orchestrator.sh"
            or not re.fullmatch(r"[0-9a-f]{64}", str(executor.get("sha256", "")))):
        raise ValueError("frozen inputs executor identity mismatch")
    # The portable source-set digest has a published canonical JSON contract.
    content = {key: evidence[key] for key in ("repositories", "builderRepositories", "suiteDigest")}
    digest = hashlib.sha256(json.dumps(content, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if digest != evidence["sourceSetDigest"]:
        raise ValueError("frozen inputs source-set digest mismatch")
    return {key: evidence[key] for key in (
        "contractVersion", "ciJobId", "sourceSetDigest", "manifestDigest", "suiteDigest",
        "repositories", "builderRepositories", "executor")}


def load_gate_receipt(path, product):
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != 1:
        raise ValueError("gate receipt must be the schemaVersion-1 product release-image receipt")
    if payload.get("status") != "PASS":
        raise ValueError("gate receipt is not PASS")
    if payload.get("product") != product:
        raise ValueError("gate receipt belongs to another product")
    return payload



def validate_gate_binding(gate, ci_job_id, images, image_keys):
    """Bind original gate identity to the selected job and sealed images."""
    if not isinstance(gate, dict):
        raise ValueError("gate receipt binding is missing")
    job = gate.get("job")
    if not isinstance(job, str) or not SAFE_ID.fullmatch(job) or job != ci_job_id:
        raise ValueError("gate receipt CI job mismatch")
    image_id = gate.get("loadedImageId")
    layout = gate.get("ociLayoutDigest")
    if not isinstance(image_id, str) or not IMAGE_REF.fullmatch(image_id):
        raise ValueError("gate receipt image ID is invalid")
    if not isinstance(layout, str) or not IMAGE_REF.fullmatch(layout):
        raise ValueError("gate receipt OCI layout is invalid")
    if not isinstance(images, dict) or set(images) != set(image_keys):
        raise ValueError("gate receipt image denominator mismatch")
    for image in images.values():
        if not isinstance(image, dict) or image.get("ref") != image_id:
            raise ValueError("gate receipt image ID mismatch")
        if image.get("layoutDigest") != layout:
            raise ValueError("gate receipt OCI layout mismatch")


def create(args):
    contract = ADMITTED_PRODUCTS.get(args.product)
    if contract is None:
        raise ValueError(f"product has no admitted archive receipt contract: {args.product}")
    if args.product in RESERVED_PRODUCT_KEYS:
        raise ValueError("the quote-bom product line owns a dedicated receipt contract")
    for name, value in (("release id", args.release_id), ("CI job id", args.ci_job_id),
                        ("builder id", args.builder_id)):
        if not SAFE_ID.fullmatch(value):
            raise ValueError(f"{name} is not a safe identifier")
    if not args.release_id.startswith(f"{args.product}-"):
        raise ValueError("release id must be prefixed by the product id")
    frozen_document = json.loads(Path(args.frozen_inputs).read_text(encoding="utf-8"))
    source_keys = ("core", contract["sourceKey"])
    sources = parse_assignments(args.source, source_keys, require_directory=True)
    gate = load_gate_receipt(args.gate_receipt, args.product)
    image_archive = Path(args.image_archive).resolve()
    control = Path(args.control_bundle).resolve()
    if not image_archive.is_file():
        raise ValueError(f"image archive is missing: {image_archive}")
    if not control.is_file():
        raise ValueError(f"control bundle is missing: {control}")
    source_records = {key: source_record(path) for key, path in sources.items()}
    core_oid = source_records["core"]["oid"]
    product_oid = source_records[contract["sourceKey"]]["oid"]
    if gate.get("coreCommit") != core_oid or gate.get("productCommit") != product_oid:
        raise ValueError("gate receipt source commits differ from the receipt sources")
    frozen_inputs = validate_frozen_inputs(frozen_document, source_records, args.product, args.ci_job_id)
    images = read_image_refs(args.image_refs, contract["imageKeys"])
    validate_gate_binding(gate, args.ci_job_id, images, contract["imageKeys"])
    payload = {
        "schemaVersion": 3,
        "kind": f"{args.product}-release-image-receipt",
        "phase": "ci-archive-stage",
        "releaseId": args.release_id,
        "ciJobId": args.ci_job_id,
        "createdAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "builder": {
            "id": args.builder_id,
            "platform": args.platform,
            "dockerVersion": args.docker_version,
        },
        "sources": source_records,
        "frozenInputs": frozen_inputs,
        "gate": {
            "runner": "scripts/application/run-product-release-image-gate.sh",
            "receipt": Path(args.gate_receipt).name,
            "sha256": sha256_file(args.gate_receipt),
            "job": gate["job"],
            "loadedImageId": gate["loadedImageId"],
            "ociLayoutDigest": gate["ociLayoutDigest"],
        },
        "artifacts": {
            "images": images,
            "controlBundle": {
                "fileName": control.name,
                "sha256": sha256_file(control),
                "bytes": control.stat().st_size,
            },
            "imageArchive": {
                "fileName": image_archive.name,
                "sha256": sha256_file(image_archive),
                "bytes": image_archive.stat().st_size,
            },
        },
        "smoke": {"verdict": "PASS", "stack": "original-gate",
                  "archiveReload": "not-attested-by-receipt-tool"},
        "transport": {"mode": "ci-archive"},
        "verdict": "PASS",
    }
    atomic_json(args.output, payload)
    print(f"receipt={Path(args.output).resolve()}")
    print(f"sha256={sha256_file(args.output)}")


def load_receipt(path, product):
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    contract = ADMITTED_PRODUCTS.get(product)
    if contract is None:
        raise ValueError(f"product has no admitted archive receipt contract: {product}")
    if payload.get("schemaVersion") != 3:
        raise ValueError("unsupported release archive receipt schema")
    if payload.get("kind") != f"{product}-release-image-receipt":
        raise ValueError("unexpected release archive receipt kind")
    if payload.get("transport") != {"mode": "ci-archive"}:
        raise ValueError("release archive receipt transport must be ci-archive")
    if payload.get("verdict") != "PASS" or payload.get("smoke", {}).get("verdict") != "PASS":
        raise ValueError("release archive receipt is not PASS")
    validate_frozen_inputs(payload.get("frozenInputs"), payload.get("sources", {}), product,
                           payload.get("ciJobId"))
    validate_gate_binding(payload.get("gate"), payload.get("ciJobId"),
                          payload.get("artifacts", {}).get("images"), contract["imageKeys"])
    return payload


def verify(args):
    receipt_path = Path(args.file).resolve()
    if sha256_file(receipt_path) != args.expected_sha256:
        raise ValueError("release archive receipt SHA-256 mismatch")
    payload = load_receipt(receipt_path, args.product)
    if payload.get("ciJobId") != args.ci_job_id:
        raise ValueError("release archive receipt selected CI job mismatch")
    if payload.get("releaseId") != args.release_id:
        raise ValueError("release archive receipt releaseId mismatch")
    contract = ADMITTED_PRODUCTS[args.product]

    sources = parse_assignments(args.source, ("core", contract["sourceKey"]), require_directory=True)
    for key, path in sources.items():
        actual = source_record(path)["oid"]
        if payload.get("sources", {}).get(key, {}).get("oid") != actual:
            raise ValueError(f"release archive receipt source OID mismatch: {key}")

    images = payload.get("artifacts", {}).get("images", {})
    for key in contract["imageKeys"]:
        ref = images.get(key, {}).get("ref", "")
        if not IMAGE_REF.fullmatch(ref):
            raise ValueError(f"release archive receipt has invalid {key} image ID")

    artifact_dir = Path(args.artifact_dir).resolve()
    gate = payload["gate"]
    gate_name = gate.get("receipt", "")
    if not isinstance(gate_name, str) or not SAFE_ID.fullmatch(gate_name):
        raise ValueError("gate receipt filename is invalid")
    gate_path = (artifact_dir / gate_name).resolve()
    if gate_path.parent != artifact_dir or not gate_path.is_file():
        raise ValueError("original gate receipt is missing")
    if sha256_file(gate_path) != gate.get("sha256"):
        raise ValueError("gate receipt SHA-256 mismatch")
    original_gate = load_gate_receipt(gate_path, args.product)
    validate_gate_binding(original_gate, args.ci_job_id, images, contract["imageKeys"])
    for role in ("controlBundle", "imageArchive"):
        entry = payload.get("artifacts", {}).get(role, {})
        file_name = entry.get("fileName", "")
        if not SAFE_ID.fullmatch(file_name) or "/" in file_name:
            raise ValueError(f"release archive receipt {role} filename is invalid")
        artifact = (artifact_dir / file_name).resolve()
        if artifact.parent != artifact_dir or not artifact.is_file():
            raise ValueError(f"release archive receipt {role} is missing")
        if sha256_file(artifact) != entry.get("sha256"):
            raise ValueError(f"release archive receipt {role} SHA-256 mismatch")
    print("receipt=VERIFIED")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    create_parser = commands.add_parser("create", help="create a schemaVersion-3 archive receipt")
    create_parser.add_argument("--product", required=True)
    create_parser.add_argument("--frozen-inputs", required=True)
    create_parser.add_argument("--output", required=True)
    create_parser.add_argument("--release-id", required=True)
    create_parser.add_argument("--ci-job-id", required=True)
    create_parser.add_argument("--builder-id", required=True)
    create_parser.add_argument("--platform", required=True)
    create_parser.add_argument("--docker-version", required=True)
    create_parser.add_argument("--gate-receipt", required=True)
    create_parser.add_argument("--image-refs", required=True)
    create_parser.add_argument("--control-bundle", required=True)
    create_parser.add_argument("--image-archive", required=True)
    create_parser.add_argument("--source", action="append", required=True,
                               help="source key to checkout directory, as key=path")
    create_parser.set_defaults(handler=create)

    verify_parser = commands.add_parser("verify", help="verify an existing archive receipt")
    verify_parser.add_argument("--product", required=True)
    verify_parser.add_argument("--ci-job-id", required=True)
    verify_parser.add_argument("--file", required=True)
    verify_parser.add_argument("--expected-sha256", required=True)
    verify_parser.add_argument("--release-id", required=True)
    verify_parser.add_argument("--artifact-dir", required=True)
    verify_parser.add_argument("--source", action="append", required=True,
                               help="source key to checkout directory, as key=path")
    verify_parser.set_defaults(handler=verify)

    args = parser.parse_args(argv)
    try:
        args.handler(args)
    except (ValueError, OSError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"product_release_archive_receipt: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
