#!/usr/bin/env python3
"""Stop only Gradle processes belonging to one finished CI command."""
import argparse
import json
import os
from pathlib import Path
import re
import select
import signal
import time

GRADLE_MAINS = {
    b"worker.org.gradle.process.internal.worker.GradleWorkerMain",
    b"org.gradle.launcher.daemon.bootstrap.GradleDaemon",
}


def proc_entries():
    return [path for path in Path("/proc").iterdir() if path.name.isdigit()]


def owned_handles(job, source_root):
    handles = []
    for path in proc_entries():
        fd = None
        try:
            if path.stat().st_uid != os.geteuid():
                continue
            if (path / "exe").resolve(strict=True).name != "java":
                continue
            arguments = set((path / "cmdline").read_bytes().split(b"\0"))
            if not arguments.intersection(GRADLE_MAINS):
                continue
            environment = dict(row.split(b"=", 1) for row in
                               (path / "environ").read_bytes().split(b"\0") if b"=" in row)
            if environment.get(b"AURA_CI_JOB_ID") != job.encode():
                continue
            cwd = (path / "cwd").resolve(strict=True)
            if not cwd.is_relative_to(source_root):
                continue
            start = (path / "stat").read_text().rsplit(")", 1)[1].split()[19]
            fd = os.pidfd_open(int(path.name))
            if (path / "stat").read_text().rsplit(")", 1)[1].split()[19] != start:
                os.close(fd)
                fd = None
                continue
            handles.append((fd, {"pid": int(path.name), "startTicks": start,
                                 "cwd": str(cwd), "job": job,
                                 "kind": "daemon" if b"org.gradle.launcher.daemon.bootstrap.GradleDaemon" in arguments else "worker"}))
        except (OSError, ValueError, IndexError):
            if fd is not None:
                os.close(fd)
    return sorted(handles, key=lambda item: item[1]["kind"] != "daemon")


def exited(fd):
    poll = select.poll()
    poll.register(fd, select.POLLIN)
    return bool(poll.poll(0))


def stop_handles(handles, grace_seconds):
    for fd, row in handles:
        row["signal"] = "TERM"
        try:
            signal.pidfd_send_signal(fd, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline and any(not exited(fd) for fd, _ in handles):
        time.sleep(.05)
    for fd, row in handles:
        if not exited(fd):
            row["signal"] = "TERM,KILL"
            try:
                signal.pidfd_send_signal(fd, signal.SIGKILL)
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline and any(not exited(fd) for fd, _ in handles):
        time.sleep(.05)
    for fd, row in handles:
        row["exited"] = exited(fd)


def cleanup(job, source_root, grace_seconds=5):
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", job):
        raise ValueError("Invalid CI job identity")
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise RuntimeError("Linux pidfd support is required for safe CI cleanup")
    root = Path(source_root).resolve(strict=True)
    handles = []
    seen = set()
    try:
        for round_number in range(2):
            fresh = []
            for fd, row in owned_handles(job, root):
                identity = (row["pid"], row["startTicks"])
                if identity in seen:
                    os.close(fd)
                else:
                    seen.add(identity)
                    fresh.append((fd, row))
            if not fresh:
                break
            handles.extend(fresh)
            # Cancel the daemon first; collect a worker forked during cancellation once more.
            stop_handles(fresh, grace_seconds if round_number == 0 else min(grace_seconds, 2))
        remaining = owned_handles(job, root)
        try:
            live = [row for fd, row in remaining if not exited(fd)]
        finally:
            for fd, _ in remaining:
                os.close(fd)
        rows = [row for _, row in handles]
        return {"job": job, "sourceRoot": str(root), "processes": rows, "remaining": live,
                "complete": all(row["exited"] for row in rows) and not live}
    finally:
        for fd, _ in handles:
            os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    result = cleanup(args.job_id, args.source_root)
    Path(args.report).write_text(json.dumps(result, indent=2) + "\n")
    return 0 if result["complete"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"environment-invalid: safe Gradle cleanup failed: {error}", file=__import__("sys").stderr)
        raise SystemExit(2)
