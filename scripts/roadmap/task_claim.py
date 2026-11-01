"""Reserve roadmap tasks using atomic, create-only remote Git refs.

No checkout, index change, main push or implementation branch creation is performed.
Only claim/release mutate origin, under refs/heads/codex/claims/.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid


PREFIX = "refs/heads/codex/claims/"
HEADER = "Calorica task claim v1\n\n"
CARDS = "docs/semester-mvp/roadmap/tasks/"


class ClaimError(Exception):
    """The reservation was not confirmed; work must not start."""


def _git(repo, *args, text=None):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), *args], input=text, env=env,
            capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ClaimError("Git недоступен или запрос не завершён. Проверьте status; не начинайте работу.") from error
    if result.returncode:
        # Remote output may contain credentials in URLs; do not forward raw stderr.
        raise ClaimError("Git отклонил операцию. Проверьте доступ к origin и status; бронь не подтверждена.")
    return result.stdout.strip()


def _task(task):
    if not re.fullmatch(r"T\d{2}", task):
        raise ClaimError("ID задачи должен иметь вид T00.")
    return task


def _refs(repo, pattern):
    lines = _git(repo, "ls-remote", "--heads", "origin", pattern).splitlines()
    return dict(line.split()[::-1] for line in lines)


def _fetch(repo, sha):
    _git(repo, "fetch", "--no-tags", "--no-write-fetch-head", "origin", sha)


def _read_claim(repo, task, sha):
    _fetch(repo, sha)
    message = _git(repo, "show", "-s", "--format=%B", sha)
    try:
        if not message.startswith(HEADER):
            raise ValueError("Unknown marker")
        data = json.loads(message[len(HEADER):])
        if (data["task"] != task or not isinstance(data["agent"], int)
                or not all(isinstance(data[field], str) and data[field]
                           for field in ("owner", "branch", "claimed_at", "nonce"))):
            raise ValueError("Invalid marker")
    except (ValueError, KeyError, TypeError) as error:
        raise ClaimError(f"{task}: ветка брони существует, но маркер некорректен. Задача не свободна.") from error
    data["claim"] = sha
    return data


def status(repo, task=None):
    """Read the shared remote, never infer freedom from local tracking refs."""
    pattern = PREFIX + (_task(task) if task else "*")
    claims = _refs(repo, pattern)
    return [_read_claim(repo, ref[len(PREFIX):], sha)
            for ref, sha in sorted(claims.items())]


def claim(repo, task, owner, branch):
    """First successful remote ref creation wins, even when checks race."""
    _task(task)
    if not owner.strip() or len(owner) > 160 or any(ord(c) < 32 for c in owner):
        raise ClaimError("Укажите имя разработчика и идентификатор чата без переносов строк (до 160 символов).")
    if branch == "main" or branch.startswith("codex/claims/") or branch.startswith("-"):
        raise ClaimError("Укажите отдельную рабочую ветку, не main и не ветку бронирования.")
    _git(repo, "check-ref-format", "--branch", branch)
    occupied = status(repo, task)
    if occupied:
        raise ClaimError(f"{task} уже занята: {occupied[0]['owner']}; ветка {occupied[0]['branch']}.")

    main = _refs(repo, "refs/heads/main").get("refs/heads/main")
    if not main:
        raise ClaimError("В origin не найдена main.")
    _fetch(repo, main)
    card = _git(repo, "show", f"{main}:{CARDS}{task}.md")
    agent = re.search(r"Агент-владелец: \*\*(\d+)\*\*", card)
    if not agent:
        raise ClaimError(f"{task}: в main нет карточки с агентом-владельцем.")
    data = {
        "task": task, "agent": int(agent[1]), "owner": owner.strip(),
        "branch": branch,
        "claimed_at": datetime.now(timezone.utc).isoformat(),
        "nonce": uuid.uuid4().hex,
    }
    tree = _git(repo, "rev-parse", main + "^{tree}")
    sha = _git(repo, "commit-tree", tree, "-p", main,
               text=HEADER + json.dumps(data, ensure_ascii=False) + "\n")
    ref = PREFIX + task
    try:
        # Empty expected value means the remote ref MUST NOT exist. No overwrite.
        _git(repo, "push", "--porcelain", f"--force-with-lease={ref}:",
             "origin", f"{sha}:{ref}")
    except ClaimError as error:
        # A lost response may follow a successful push. Confirm only our exact SHA.
        current = status(repo, task)
        if current and current[0]["claim"] == sha:
            return current[0]
        if current:
            raise ClaimError(f"{task} занята: {current[0]['owner']}. Работу не начинать.") from error
        raise
    data["claim"] = sha
    return data


def release(repo, task, token):
    """Delete only the exact reservation explicitly supplied by the caller."""
    _task(task)
    if not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", token):
        raise ClaimError("Нужен полный SHA брони из результата claim/status.")
    current = status(repo, task)
    if not current or current[0]["claim"] != token:
        raise ClaimError("Бронь отсутствует или уже изменилась. Чужая/новая бронь не снята.")
    ref = PREFIX + task
    _git(repo, "push", "--porcelain", f"--force-with-lease={ref}:{token}", "origin", f":{ref}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("status", help="Проверить общие брони на origin")
    check.add_argument("task", nargs="?")
    take = commands.add_parser("claim", help="Атомарно забронировать задачу на origin")
    take.add_argument("task")
    take.add_argument("--owner", required=True, help="Имя разработчика / идентификатор чата")
    take.add_argument("--branch", required=True, help="Имя будущей рабочей ветки")
    drop = commands.add_parser("release", help="Снять конкретную бронь после сдачи или передачи")
    drop.add_argument("task")
    drop.add_argument("--claim", required=True, help="Полный SHA брони; не SHA рабочей ветки")
    args = parser.parse_args()
    try:
        if args.command == "claim":
            result = claim(args.repo, args.task, args.owner, args.branch)
        elif args.command == "status":
            result = status(args.repo, args.task)
        else:
            release(args.repo, args.task, args.claim)
            result = {"task": args.task, "released": args.claim}
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ClaimError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
