"""Reservation protocol exercised against a real isolated Git remote."""

import concurrent.futures
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest

from task_claim import ClaimError, claim, release, status


def git(repo, *args):
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True,
        capture_output=True, text=True, timeout=15,
    ).stdout.strip()


class TaskClaimTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.remote = root / "remote.git"
        self.first = root / "first"
        self.second = root / "second"
        git(root, "init", "--bare", "--initial-branch=main", str(self.remote))
        git(root, "clone", str(self.remote), str(self.first))
        self.identity(self.first)
        cards = self.first / "docs/semester-mvp/roadmap/tasks"
        cards.mkdir(parents=True)
        for task, agent in [("T00", 1), ("T01", 8)]:
            (cards / (task + ".md")).write_text(
                f"# {task}\nАгент-владелец: **{agent}**.\n", encoding="utf-8"
            )
        git(self.first, "add", ".")
        git(self.first, "commit", "-m", "Initial roadmap")
        git(self.first, "push", "origin", "main")
        git(root, "clone", str(self.remote), str(self.second))
        self.identity(self.second)

    def identity(self, repo):
        git(repo, "config", "user.name", "Reservation test")
        git(repo, "config", "user.email", "test@example.invalid")

    def take(self, repo=None, owner="Богдан / чат 1", task="T00"):
        return claim(repo or self.first, task, owner, "codex/" + task.lower())

    def test_claim_is_visible_from_another_clone_without_changing_worktree(self):
        before = git(self.first, "rev-parse", "HEAD")
        (self.first / "local-note.txt").write_text("uncommitted private note")
        dirty = git(self.first, "status", "--porcelain")
        result = self.take()
        other = status(self.second, "T00")
        self.assertEqual(other, [result])
        self.assertEqual(result["owner"], "Богдан / чат 1")
        self.assertEqual(result["agent"], 1)
        self.assertEqual(result["branch"], "codex/t00")
        self.assertEqual(git(self.first, "rev-parse", "HEAD"), before)
        self.assertEqual(git(self.first, "status", "--porcelain"), dirty)
        self.assertEqual(git(self.first, "diff", before, result["claim"]), "")
        self.assertIn("T", result["claimed_at"])

    def test_simultaneous_claims_have_exactly_one_winner(self):
        barrier = threading.Barrier(2)

        def attempt(repo, owner):
            barrier.wait(timeout=5)
            try:
                return self.take(repo, owner)
            except ClaimError:
                return None

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            attempts = [pool.submit(attempt, self.first, "Первый"),
                        pool.submit(attempt, self.second, "Второй")]
            results = [job.result(timeout=30) for job in attempts]
        winners = [item for item in results if item]
        self.assertEqual(len(winners), 1)
        self.assertEqual(status(self.first, "T00"), winners)

    def test_existing_claim_is_not_overwritten_even_for_the_same_owner(self):
        initial = self.take()
        with self.assertRaises(ClaimError):
            self.take(self.second)
        self.assertEqual(status(self.second, "T00"), [initial])

    def test_wrong_token_cannot_release_someone_elses_claim(self):
        initial = self.take()
        with self.assertRaises(ClaimError):
            release(self.second, "T00", "0" * 40)
        self.assertEqual(status(self.first, "T00"), [initial])

    def test_stale_release_cannot_delete_a_new_reservation(self):
        initial = self.take()
        release(self.first, "T00", initial["claim"])
        self.assertEqual(status(self.second, "T00"), [])
        newer = self.take(self.second, "Новый владелец")
        self.assertNotEqual(initial["claim"], newer["claim"])
        with self.assertRaises(ClaimError):
            release(self.first, "T00", initial["claim"])
        self.assertEqual(status(self.first, "T00"), [newer])

    def test_different_tasks_can_be_reserved_independently(self):
        first = self.take()
        second = self.take(self.second, "Другой", "T01")
        self.assertEqual(status(self.second), [first, second])

    def test_invalid_inputs_create_no_remote_refs(self):
        for task, owner, branch in [
            ("T99", "Разработчик", "codex/t99"),
            ("../../main", "Разработчик", "codex/task"),
            ("T00", "", "codex/t00"),
            ("T00", "Разработчик\nподмена", "codex/t00"),
            ("T00", "Разработчик", "main"),
            ("T00", "Разработчик", "codex/claims/T00"),
        ]:
            with self.subTest(task=task, owner=owner, branch=branch):
                with self.assertRaises(ClaimError):
                    claim(self.first, task, owner, branch)
        self.assertEqual(status(self.first), [])

    def test_remote_failure_is_not_reported_as_an_unclaimed_task(self):
        git(self.first, "remote", "set-url", "origin", str(self.remote) + "-missing")
        with self.assertRaises(ClaimError):
            status(self.first, "T00")
        with self.assertRaises(ClaimError):
            self.take()

    def test_unknown_marker_is_not_reported_as_free(self):
        git(self.first, "push", "origin", "HEAD:refs/heads/codex/claims/T00")
        with self.assertRaises(ClaimError):
            status(self.second, "T00")
        with self.assertRaises(ClaimError):
            self.take(self.second)


if __name__ == "__main__":
    unittest.main()
