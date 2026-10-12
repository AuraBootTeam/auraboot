#!/usr/bin/env python3
"""Reconcile a freshly built SDK archive and the installed real-backend driver."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import tarfile


def verify_consumer_checks(source, output, distribution):
    source, output = Path(source), Path(output)
    for name in ["consumer.test.mjs", "consumer-ts-check.ts"]:
        if (output / "consumer" / name).read_bytes() != (source / "scripts/fixtures/open-platform-consumer" / name).read_bytes():
            raise ValueError("SDK contract consumer differs from frozen source")
    for name in ["runtime", "types"]:
        if (output / "consumer" / f"{name}.exit").read_text().strip() != "0":
            raise ValueError(f"SDK contract consumer {name} did not pass")
        (output / "consumer" / f"{name}.log").read_bytes()
    rows = distribution.validate_consumer_output((output / "consumer/runtime.log").read_text())
    return {"provider": "loopback-fixture", "cases": len(rows), "results": rows,
            "types": "pass", "typeFixtureSha256": hashlib.sha256((output / "consumer/consumer-ts-check.ts").read_bytes()).hexdigest()}

def verify(source, output):
    source, output = Path(source), Path(output)
    spec = importlib.util.spec_from_file_location("distribution", source / "scripts/check-package-distribution.py")
    distribution = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(distribution)
    package = source / "packages/open-platform-sdk"
    expected = json.loads((package / "package.json").read_text())
    canonical = (package / "LICENSE").read_text()
    distribution.validate_canonical(canonical)
    packs = json.loads((output / "pack.json").read_text())
    if len(packs) != 1 or Path(packs[0]["filename"]).name != packs[0]["filename"]:
        raise ValueError("SDK archive inventory invalid")
    archive = output / "archive" / packs[0]["filename"]
    receipt = distribution.validate_archive(archive, canonical, expected)
    build = output / "build/packages/open-platform-sdk"
    with tarfile.open(archive, "r:gz") as tar:
        if json.loads(tar.extractfile("package/package.json").read()) != expected:
            raise ValueError("SDK packed manifest changed")
        for member in tar.getmembers():
            if not member.isfile():
                continue
            name = member.name.removeprefix("package/")
            if not member.name.startswith("package/") or ".." in Path(name).parts:
                raise ValueError("SDK archive path invalid")
            data = tar.extractfile(member).read()
            if name != "package.json" and data != (build / name).read_bytes():
                raise ValueError("SDK archive does not match fresh build")
            if name in ["package.json", "README.md", "LICENSE"] and data != (package / name).read_bytes():
                # npm normalizes package.json whitespace; parsed metadata was compared above.
                if name != "package.json":
                    raise ValueError("SDK archive documentation differs from source")
    for name in ["real-backend.mjs", "real-backend.profile.json"]:
        if (output / "consumer" / name).read_bytes() != (source / "scripts/fixtures/open-platform-consumer" / name).read_bytes():
            raise ValueError("SDK installed consumer differs from frozen source")
    for name in ["src/index.ts", "tsconfig.build.json"]:
        if (build / name).read_bytes() != (package / name).read_bytes():
            raise ValueError("SDK build source differs from frozen source")
    installed = output / "consumer/node_modules/@auraboot/open-platform-sdk"
    for name in ["dist/index.js", "dist/index.d.ts", "README.md", "LICENSE"]:
        if (installed / name).read_bytes() != (build / name).read_bytes():
            raise ValueError("SDK installed bytes differ from fresh build")
    if json.loads((installed / "package.json").read_text()) != expected:
        raise ValueError("SDK installed manifest differs from source")
    compiler = (output / "compiler-version.txt").read_text().strip()
    node = (output / "node-version.txt").read_text().strip()
    if compiler != "Version 5.9.3" or not re.fullmatch(r"v(2[0-9]|[3-9][0-9])\.\d+\.\d+", node):
        raise ValueError("SDK build toolchain identity invalid")
    receipt.update({"manifestUnchanged": True, "explicitBuild": True, "prepackExecuted": False,
                    "compilerVersion": compiler, "nodeVersion": node,
                    "sourceSha256": hashlib.sha256((package / "src/index.ts").read_bytes()).hexdigest(),
                    "consumerSourceVerified": True, "installedBytesVerified": True,
                    "contractConsumer": verify_consumer_checks(source, output, distribution)})
    return receipt


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify-open-platform-sdk.py SOURCE OUTPUT RECEIPT")
    Path(sys.argv[3]).write_text(json.dumps(verify(*sys.argv[1:3]), indent=2) + "\n")
