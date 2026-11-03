#!/usr/bin/env python3
"""Deterministic lifecycle regressions for the disposable staging runner."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import multiprocessing
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

RUNNER_PATH = pathlib.Path(__file__).with_name("run-disposable.py")
SPEC = importlib.util.spec_from_file_location("agd003_run_disposable", RUNNER_PATH)
assert SPEC and SPEC.loader
RUNNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RUNNER
SPEC.loader.exec_module(RUNNER)


def _try_same_namespace(project: str, marker: str, result_queue) -> None:
    """Child process: any execution past lock acquisition writes a marker."""
    marker_path = pathlib.Path(marker)
    RUNNER.ensure_project_unused = lambda _project: marker_path.write_text("collision check reached")
    RUNNER.cleanup_project = lambda: marker_path.write_text("cleanup reached")
    code = RUNNER.run_managed(project, lambda: marker_path.write_text("operation reached"))
    result_queue.put(code)


class DisposableLifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="agd003-lifecycle-")
        self.addCleanup(self.temp.cleanup)
        self.old_lock_root = RUNNER.LOCK_ROOT
        RUNNER.LOCK_ROOT = pathlib.Path(self.temp.name) / "locks"
        self.addCleanup(setattr, RUNNER, "LOCK_ROOT", self.old_lock_root)
        self.old_cleanup_dir = RUNNER.CLEANUP_DIR
        self.old_cleanup_env = RUNNER.CLEANUP_ENV
        RUNNER.CLEANUP_DIR = None
        RUNNER.CLEANUP_ENV = None
        self.addCleanup(setattr, RUNNER, "CLEANUP_DIR", self.old_cleanup_dir)
        self.addCleanup(setattr, RUNNER, "CLEANUP_ENV", self.old_cleanup_env)

    def _main(self, operation, ensure=None, cleanup=None, stderr=None) -> int:
        project = "agd003-unit-lifecycle"
        patches = [mock.patch.object(sys, "argv", [str(RUNNER_PATH), "--project-name", project]),
                   mock.patch.object(RUNNER, "run_disposable_locked", side_effect=operation)]
        if ensure is not None:
            patches.append(mock.patch.object(RUNNER, "ensure_project_unused", side_effect=ensure))
        if cleanup is not None:
            patches.append(mock.patch.object(RUNNER, "cleanup_project", side_effect=cleanup))
        stack = contextlib.ExitStack()
        with stack:
            for item in patches:
                stack.enter_context(item)
            if stderr is None:
                return RUNNER.main()
            with contextlib.redirect_stderr(stderr):
                return RUNNER.main()

    def test_second_process_cannot_enter_or_clean_winning_namespace(self) -> None:
        project = "agd003-concurrent-project"
        parent_fd = RUNNER.acquire_namespace_lock(project)
        marker = pathlib.Path(self.temp.name) / "loser-side-effect"
        context = multiprocessing.get_context("fork")
        result_queue = context.Queue()
        child = context.Process(target=_try_same_namespace,
                                args=(project, str(marker), result_queue))
        child.start()
        try:
            child_code = result_queue.get(timeout=5)
            child.join(timeout=5)
            self.assertEqual(child.exitcode, 0)
            self.assertEqual(child_code, 1)
            self.assertFalse(marker.exists(), "loser reached project checks, operation, or cleanup")
        finally:
            if child.is_alive():
                child.terminate()
                child.join(timeout=5)
            RUNNER.release_namespace_lock(parent_fd)

        marker.write_text("winner completed cleanup")
        with mock.patch.object(RUNNER, "ensure_project_unused"), mock.patch.object(RUNNER, "cleanup_project"):
            self.assertEqual(RUNNER.run_managed(project, lambda: None), 0)

    def test_cleanup_timeout_returns_failure_and_keeps_private_config(self) -> None:
        diagnostic = io.StringIO()
        run_dir = pathlib.Path(self.temp.name) / "private-run"
        run_dir.mkdir(mode=0o700)
        env_file = run_dir / "staging.env"
        env_file.write_text("synthetic=secret\n")
        env_file.chmod(0o600)
        RUNNER.CLEANUP_DIR, RUNNER.CLEANUP_ENV = run_dir, env_file
        with mock.patch.object(RUNNER, "compose", return_value=["docker", "compose", "down"]), \
                mock.patch.object(RUNNER.subprocess, "run",
                                  side_effect=subprocess.TimeoutExpired(["docker", "compose"], 180)):
            code = self._main(lambda: None, ensure=lambda _project: None, stderr=diagnostic)
        self.assertEqual(code, 1)
        self.assertIn("ERROR: cleanup failed", diagnostic.getvalue())
        self.assertIn("timed out", diagnostic.getvalue())
        self.assertTrue(env_file.exists(), "failed cleanup must retain its restricted retry configuration")
        self.assertNotIn("synthetic=secret", diagnostic.getvalue())

    def test_preexisting_collision_skips_operation_and_cleanup(self) -> None:
        operation = mock.Mock()
        cleanup = mock.Mock()
        diagnostic = io.StringIO()
        code = self._main(operation,
                          ensure=lambda _project: (_ for _ in ()).throw(RuntimeError("pre-existing collision")),
                          cleanup=cleanup, stderr=diagnostic)
        self.assertEqual(code, 1)
        self.assertIn("pre-existing collision", diagnostic.getvalue())
        operation.assert_not_called()
        cleanup.assert_not_called()

    def test_partial_creation_runs_cleanup_and_preserves_primary_failure(self) -> None:
        partial = pathlib.Path(self.temp.name) / "partial-resource"
        cleanup_saw_partial = []

        def operation() -> None:
            partial.write_text("created before failure")
            raise RuntimeError("simulated Compose partial creation failure")

        def cleanup() -> None:
            cleanup_saw_partial.append(partial.exists())
            partial.unlink(missing_ok=True)

        diagnostic = io.StringIO()
        code = self._main(operation, ensure=lambda _project: None, cleanup=cleanup, stderr=diagnostic)
        self.assertEqual(code, 1)
        self.assertEqual(cleanup_saw_partial, [True])
        self.assertFalse(partial.exists())
        self.assertIn("simulated Compose partial creation failure", diagnostic.getvalue())

    def test_cleanup_failure_does_not_hide_primary_failure(self) -> None:
        diagnostic = io.StringIO()
        code = self._main(lambda: (_ for _ in ()).throw(RuntimeError("primary build failure")),
                          ensure=lambda _project: None,
                          cleanup=lambda: (_ for _ in ()).throw(RuntimeError("secondary cleanup failure")),
                          stderr=diagnostic)
        self.assertEqual(code, 1)
        self.assertIn("primary build failure", diagnostic.getvalue())
        self.assertIn("secondary cleanup failure", diagnostic.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
