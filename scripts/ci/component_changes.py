#!/usr/bin/env python3
"""Classify a full git diff; documentation never triggers a binary release."""
import os
import subprocess
import sys


def classify(paths):
    result = dict(backend=False, android=False, automation=False)
    for path in paths:
        # Markdown is module documentation, including package boundary notes.
        if path.startswith("docs/") or path.endswith(".md"):
            continue
        if path.startswith(("backend/", "deploy/backend/")):
            result["backend"] = True
        if path.startswith(("app/", "gradle/")) or path in {
            "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties"
        }:
            result["android"] = True
        if path.startswith((".github/workflows/", "scripts/ci/")):
            result["automation"] = True
    return result


if __name__ == "__main__":
    changed = subprocess.check_output(["git", "diff", "--name-only", "-z", sys.argv[1], sys.argv[2]])
    flags = classify(changed.decode().strip("\0").split("\0"))
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for key, value in flags.items():
            print(f"{key}={str(value).lower()}", file=output)
