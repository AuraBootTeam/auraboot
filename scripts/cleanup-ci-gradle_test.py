"""Linux process-safety contracts using isolated Java workers, not product test coverage."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("cleanup_ci_gradle",
    Path(__file__).with_name("cleanup-ci-gradle.py"))
cleanup_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup_module)


@unittest.skipUnless(os.name == "posix" and Path("/proc/self").exists(),
                     "Linux /proc and pidfd contracts")
class CleanupContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("java") or not shutil.which("javac"):
            raise RuntimeError("A real JDK is required")
        base = Path(os.environ.get("AURA_CI_CLEANUP_TEST_ROOT",
                    str(Path(__file__).parent.parent / ".workspace/tmp/ci-cleanup-tests")))
        base.mkdir(parents=True, exist_ok=True)
        cls.directory = tempfile.TemporaryDirectory(dir=base)
        cls.root = Path(cls.directory.name)
        cls.source = cls.root / "source"
        cls.other = cls.root / "other"
        cls.source.mkdir(); cls.other.mkdir()
        java = cls.root / "GradleWorkerMain.java"
        java.write_text("""package worker.org.gradle.process.internal.worker;
public class GradleWorkerMain {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("stubborn")) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
      }));
    }
    System.out.println("READY"); System.out.flush();
    for (;;) Thread.sleep(1000);
  }
}
""")
        other_java = cls.root / "OtherMain.java"
        other_java.write_text("package fixture; public class OtherMain { public static void main(String[] args) throws Exception { worker.org.gradle.process.internal.worker.GradleWorkerMain.main(args); } }")
        subprocess.run(["javac", "-d", str(cls.root), str(java), str(other_java)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def setUp(self):
        self.children = []

    def tearDown(self):
        for child in self.children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=10)
            child.stdout.close()
            child.stderr.close()

    def worker(self, job="owned-contract", cwd=None, stubborn=False, gradle=True):
        child = subprocess.Popen(["java", "-Xmx32m", "-cp", str(self.root),
            "worker.org.gradle.process.internal.worker.GradleWorkerMain" if gradle else "fixture.OtherMain",
            "stubborn" if stubborn else "normal"],
            cwd=cwd or self.source, env={**os.environ, "AURA_CI_JOB_ID": job},
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.children.append(child)
        self.assertEqual(child.stdout.readline().strip(), "READY")
        return child

    def run_cleanup(self, job="owned-contract", grace=.2):
        # Limit mutation experiments to processes created by this fixture.
        candidates = [Path("/proc") / str(child.pid) for child in self.children]
        with patch.object(cleanup_module, "proc_entries", return_value=candidates):
            return cleanup_module.cleanup(job, self.source, grace_seconds=grace)

    def test_exits_owned_worker_and_preserves_foreign_job(self):
        owned = self.worker()
        foreign = self.worker(job="another-contract")
        report = self.run_cleanup()
        self.assertTrue(report["complete"])
        self.assertEqual([row["pid"] for row in report["processes"]], [owned.pid])
        self.assertTrue(report["processes"][0]["exited"])
        self.assertEqual(report["processes"][0]["signal"], "TERM")
        owned.wait(timeout=5)
        self.assertIsNone(foreign.poll())

    def test_escalates_stubborn_owned_worker_without_touching_foreign(self):
        owned = self.worker(stubborn=True)
        foreign = self.worker(job="another-contract", stubborn=True)
        report = self.run_cleanup()
        self.assertTrue(report["complete"])
        self.assertEqual(report["processes"][0]["signal"], "TERM,KILL")
        self.assertEqual(owned.wait(timeout=5), -9)
        self.assertIsNone(foreign.poll())

    def test_collects_a_late_worker_and_proves_final_inventory_empty(self):
        first = self.worker()
        calls = 0
        late = None

        def inventory():
            nonlocal calls, late
            calls += 1
            if calls == 1:
                return [Path("/proc") / str(first.pid)]
            if calls == 2:
                late = self.worker()
            return [Path("/proc") / str(child.pid) for child in self.children]

        with patch.object(cleanup_module, "proc_entries", side_effect=inventory):
            report = cleanup_module.cleanup("owned-contract", self.source, grace_seconds=.2)
        self.assertTrue(report["complete"])
        self.assertEqual({row["pid"] for row in report["processes"]}, {first.pid, late.pid})
        self.assertEqual(report["remaining"], [])
        late.wait(timeout=5)

    def test_rejects_same_job_outside_source_root(self):
        outside = self.worker(cwd=self.other)
        report = self.run_cleanup()
        self.assertEqual(report["processes"], [])
        self.assertIsNone(outside.poll())

    def test_finished_pid_is_not_reused_as_a_signal_target(self):
        finished = self.worker()
        finished.kill(); finished.wait(timeout=5)
        report = self.run_cleanup()
        self.assertEqual(report["processes"], [])

    def test_invalid_job_fails_before_inspecting_or_signalling(self):
        child = self.worker()
        with self.assertRaises(ValueError):
            self.run_cleanup(job="")
        self.assertIsNone(child.poll())

    def test_preserves_non_java_process_with_gradle_marker_argument(self):
        child = subprocess.Popen([sys.executable, "-c",
            "import time; print('READY', flush=True); time.sleep(60)",
            "worker.org.gradle.process.internal.worker.GradleWorkerMain"],
            cwd=self.source, env={**os.environ, "AURA_CI_JOB_ID": "owned-contract"},
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.children.append(child)
        self.assertEqual(child.stdout.readline().strip(), "READY")
        report = self.run_cleanup()
        self.assertEqual(report["processes"], [])
        self.assertIsNone(child.poll())

    def test_preserves_non_gradle_java_with_same_job_and_source(self):
        child = self.worker(gradle=False)
        report = self.run_cleanup()
        self.assertEqual(report["processes"], [])
        self.assertIsNone(child.poll())


if __name__ == "__main__":
    unittest.main(verbosity=2)
