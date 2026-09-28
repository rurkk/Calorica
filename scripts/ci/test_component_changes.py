import unittest
from component_changes import classify


class ComponentChangesTest(unittest.TestCase):
    def test_component_boundaries(self):
        cases = [
            (["backend/src/main/resources/application.yml"], (True, False, False)),
            (["backend/gradle/wrapper/gradle-wrapper.properties"], (True, False, False)),
            (["deploy/backend/deploy.sh"], (True, False, False)),
            (["app/src/main/Main.kt", "gradle/libs.versions.toml"], (False, True, False)),
            (["backend/Dockerfile", "app/build.gradle.kts"], (True, True, False)),
            (["docs/architecture/README.md", "backend/README.md"], (False, False, False)),
            ([".github/workflows/backend-release.yml"], (False, False, True)),
            (["scripts/ci/component_changes.py"], (False, False, True)),
        ]
        for paths, expected in cases:
            with self.subTest(paths=paths):
                actual = classify(paths)
                self.assertEqual(tuple(actual.values()), expected)


if __name__ == "__main__":
    unittest.main()
