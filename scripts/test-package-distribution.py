"""Hermetic falsifiability checks for package archive validation."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("check-package-distribution.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
sdk_spec = importlib.util.spec_from_file_location("sdk_verifier", Path(__file__).with_name("ci") / "verify-open-platform-sdk.py")
sdk = importlib.util.module_from_spec(sdk_spec)
sdk_spec.loader.exec_module(sdk)
LICENSE = Path(__file__).resolve().parents[1].joinpath("packages/open-platform-sdk/LICENSE").read_text()


class ArchiveContract(unittest.TestCase):
    def test_canonical_text_is_independent(self):
        gate.validate_canonical(LICENSE)
        with self.assertRaisesRegex(ValueError, "authoritative"):
            gate.validate_canonical(LICENSE.replace("perpetual", "temporary"))

    def check(self, changes=None, omitted=()):
        metadata = {"name": "@auraboot/fixture", "version": "1.0.0", "license": "Apache-2.0",
                    "exports": {".": {"import": "./dist/index.js", "types": "./dist/index.d.ts"}}}
        files = {"package/package.json": json.dumps(metadata), "package/LICENSE": LICENSE,
                 "package/README.md": "Apache-2.0", "package/dist/index.js": "export {};",
                 "package/dist/index.d.ts": "export {};"}
        files.update(changes or {})
        for name in omitted:
            del files[name]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.tgz"
            with tarfile.open(path, "w:gz") as archive:
                for name, content in files.items():
                    raw = content.encode()
                    item = tarfile.TarInfo(name)
                    item.size = len(raw)
                    archive.addfile(item, io.BytesIO(raw))
            return gate.validate_archive(path, LICENSE, metadata)

    def test_valid_archive(self):
        self.assertEqual(self.check()["license"], "Apache-2.0")

    def test_missing_license(self):
        with self.assertRaisesRegex(ValueError, "missing package/LICENSE"):
            self.check(omitted=["package/LICENSE"])

    def test_retired_license(self):
        with self.assertRaisesRegex(ValueError, "canonical"):
            self.check({"package/LICENSE": "AuraBoot License Agreement, Version 1.3"})

    def test_competing_license(self):
        with self.assertRaisesRegex(ValueError, "competing license"):
            self.check({"package/LICENSE.txt": "commercial restrictions"})

    def test_wrong_metadata(self):
        with self.assertRaisesRegex(ValueError, "metadata license mismatch"):
            self.check({"package/package.json": json.dumps({"name": "@auraboot/fixture", "version": "1.0.0", "license": "LicenseRef-AuraBoot-1.3"})})

    def test_missing_dist(self):
        with self.assertRaisesRegex(ValueError, "missing exported entry"):
            self.check(omitted=["package/dist/index.js"])

    def test_readme_mismatch(self):
        with self.assertRaisesRegex(ValueError, "README license missing"):
            self.check({"package/README.md": "Proprietary only"})

    def test_identity_mismatch(self):
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            self.check({"package/package.json": '{"name":"wrong", "version":"1.0.0"}'})

    def test_publication_boundary(self):
        with self.assertRaisesRegex(ValueError, "publication boundary"):
            self.check({"package/package.json": '{"name":"@auraboot/fixture", "version":"1.0.0", "license":"Apache-2.0", "private":true}'})


class ConsumerExecutionContract(unittest.TestCase):
    def output(self, rows):
        return "CONSUMER_RESULTS " + json.dumps(rows)

    def rows(self):
        return [{"name": name, "status": "passed"} for name in gate.consumer_case_names()]

    def test_release_consumer_checks_reconcile_frozen_drivers_and_exits(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); consumer = output / "consumer"; consumer.mkdir()
            for name in ["consumer.test.mjs", "consumer-ts-check.ts"]:
                (consumer / name).write_bytes((source / "scripts/fixtures/open-platform-consumer" / name).read_bytes())
            for name in ["runtime", "types"]:
                (consumer / f"{name}.exit").write_text("0\n")
                (consumer / f"{name}.log").write_text(self.output(self.rows()) if name == "runtime" else "")
            self.assertEqual(sdk.verify_consumer_checks(source, output, gate)["cases"], 14)
            (consumer / "runtime.log").write_text(self.output(self.rows()[:-1]))
            with self.assertRaisesRegex(ValueError, "fourteen distinct"):
                sdk.verify_consumer_checks(source, output, gate)
            (consumer / "runtime.log").write_text(self.output(self.rows()))
            (consumer / "types.exit").write_text("1\n")
            with self.assertRaisesRegex(ValueError, "types did not pass"):
                sdk.verify_consumer_checks(source, output, gate)
            (consumer / "types.exit").write_text("0\n")
            (consumer / "consumer-ts-check.ts").write_text("export {};\n")
            with self.assertRaisesRegex(ValueError, "frozen source"):
                sdk.verify_consumer_checks(source, output, gate)

    def test_actual_execution_ledger(self):
        self.assertEqual(len(gate.validate_consumer_output(self.output(self.rows()))), 14)

    def test_summary_cannot_replace_execution(self):
        with self.assertRaisesRegex(ValueError, "execution ledger"):
            gate.validate_consumer_output("SUMMARY pass=14 fail=0")

    def test_empty_missing_and_duplicate_cases(self):
        for rows in [[], self.rows()[:-1], self.rows()[:-1] + [self.rows()[0]], [None] * 14]:
            with self.subTest(rows=rows):
                with self.assertRaisesRegex(ValueError, "fourteen distinct"):
                    gate.validate_consumer_output(self.output(rows))

        renamed = self.rows(); renamed[-1]["name"] = "unrelated synthetic pass"
        with self.assertRaisesRegex(ValueError, "fixed consumer fixture"):
            gate.validate_consumer_output(self.output(renamed))
        unexpected = self.rows(); unexpected[0]["extra"] = True
        with self.assertRaisesRegex(ValueError, "fourteen distinct"):
            gate.validate_consumer_output(self.output(unexpected))

    def test_failed_and_skipped_cases_are_rejected(self):
        for status in ["failed", "skipped", "did_not_run"]:
            rows = self.rows(); rows[-1]["status"] = status
            with self.assertRaisesRegex(ValueError, "incomplete or failed"):
                gate.validate_consumer_output(self.output(rows))


if __name__ == "__main__":
    unittest.main()
