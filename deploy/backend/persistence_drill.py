#!/usr/bin/env python3
"""SA-17 on disposable local PostgreSQL and real JVMs; never accepts a DB URL.

The caller supplies immutable JARs (preferably extracted from release digests).
All account data and credentials are generated for this run. Reports contain only
hashes, versions and pass/fail evidence. No provider or shared service is touched.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from decimal import Decimal

POSTGRES = "postgres:17.11-bookworm"


class DrillError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DrillError(message)


def run(args, *, env=None, stdin=None, timeout=120):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True,
                            env=env, timeout=timeout)
    # Do not put subprocess output in failures: logs can include environment/data.
    require(result.returncode == 0, f"{args[0]} {args[1]} failed (exit {result.returncode})")
    return result.stdout.strip()


def jar_migrations(path):
    with zipfile.ZipFile(path) as archive:
        prefix = "BOOT-INF/classes/db/changelog/"
        master = archive.read(prefix + "db.changelog-master.yaml").decode()
        files = re.findall(r"file:\s*(changes/[^\s]+\.sql)", master)
        require(files, "JAR has no production SQL migrations")
        return {name: archive.read(prefix + name) for name in files}


def migration_compatibility(previous, current):
    old = jar_migrations(previous)
    new = jar_migrations(current)
    require(all(name in new and content == new[name] for name, content in old.items()),
            "Previously released SQL was removed or modified")
    return old, new


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Stand:
    def __init__(self, output, java):
        self.output = output
        self.java = java
        self.name = "calorica-t17-" + uuid.uuid4().hex[:12]
        self.owner = str(uuid.uuid4())
        self.password = secrets.token_urlsafe(32)
        self.jwt = base64.b64encode(secrets.token_bytes(32)).decode()
        self.port = free_port()
        self.db_port = str(free_port())
        self.process = None
        self.log = None
        self.created = False
        self.launches = 0
        self.token = None
        self.database = None

    def docker(self, *args, **kwargs):
        return run(["docker", *args], **kwargs)

    def create(self):
        # Select a free loopback port once; Docker random mappings can change on restart.
        env = dict(os.environ, POSTGRES_PASSWORD=self.password)
        self.docker("create", "--name", self.name, "--label", "calorica.t17=" + self.owner,
                    "--publish", f"127.0.0.1:{self.db_port}:5432", "--env", "POSTGRES_PASSWORD",
                    "--env", "POSTGRES_USER=calorica", "--env", "POSTGRES_DB=fresh",
                    POSTGRES, env=env)
        self.created = True
        self.docker("start", self.name)
        self.wait_database()
        self.sql("CREATE DATABASE upgrade", database="fresh")

    def own_container(self):
        actual = self.docker("inspect", "--format", '{{ index .Config.Labels "calorica.t17" }}', self.name)
        require(actual == self.owner, "Refusing to control a container not owned by this drill")

    def wait_database(self):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            result = subprocess.run(["docker", "exec", self.name, "pg_isready", "-U", "calorica", "-d", "fresh"],
                                    capture_output=True, timeout=10)
            if result.returncode == 0:
                return
            time.sleep(0.5)
        raise DrillError("Disposable PostgreSQL did not become ready")

    def sql(self, statement, database=None):
        return self.docker("exec", "-i", self.name, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                           "-U", "calorica", "-d", database or self.database, stdin=statement)

    def changesets(self):
        return json.loads(self.sql("SELECT coalesce(json_agg(r ORDER BY orderexecuted), '[]') FROM "
                                   "(SELECT id, author, filename, md5sum, orderexecuted FROM public.databasechangelog) r"))

    def data_hashes(self):
        tables = self.sql("SELECT tablename FROM pg_tables WHERE schemaname='calorica' ORDER BY tablename").splitlines()
        result = {}
        for table in tables:
            require(re.fullmatch(r"[a-z_]+", table), "Unexpected domain table identifier")
            result[table] = self.sql("SELECT count(*) || ':' || md5(coalesce(string_agg(row_to_json(t)::text, "
                                    f"'' ORDER BY row_to_json(t)::text), '')) FROM calorica.{table} t")
        return result

    def start(self, jar, database, *, failing_migration=False):
        self.stop()
        self.database = database
        self.launches += 1
        log_path = self.output / f"jvm-{self.launches}.log"
        self.log = log_path.open("w")
        # No inherited DB_*, SPRING_*, JAVA_TOOL_OPTIONS or provider credentials.
        env = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "HOME", "TMPDIR") if key in os.environ}
        env.update(DB_URL=f"jdbc:postgresql://127.0.0.1:{self.db_port}/{database}",
                   DB_USERNAME="calorica", DB_PASSWORD=self.password, DB_POOL_SIZE="2",
                   AUTH_JWT_SECRET=self.jwt, SPRING_PROFILES_ACTIVE="prod", SERVER_PORT=str(self.port),
                   SERVER_ADDRESS="127.0.0.1")
        if failing_migration:
            changelog = self.output / "failed-release.yaml"
            changelog.write_text("databaseChangeLog:\n  - include:\n      file: db/changelog/db.changelog-master.yaml\n"
                                 "  - changeSet:\n      id: t17-failed-release\n      author: t17\n      changes:\n"
                                 "        - sql:\n            sql: CREATE TABLE calorica.t17_partial (id int); SELECT 1/0;\n")
            env["SPRING_LIQUIBASE_CHANGE_LOG"] = "file:" + str(changelog)
        self.process = subprocess.Popen([self.java, "-Xms32m", "-Xmx192m", "-XX:+UseSerialGC",
                                         "-XX:ActiveProcessorCount=2", "-Duser.timezone=UTC", "-jar", str(jar)],
                                        env=env, cwd=self.output, stdout=self.log, stderr=subprocess.STDOUT)
        if failing_migration:
            try:
                status = self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                raise DrillError("Bad release did not fail startup within 120 seconds") from None
            require(status != 0, "Bad release unexpectedly started successfully")
            require(self.http("GET", "/actuator/health/readiness", expected=None)[0] != 200,
                    "Failed release must never be reported ready")
        else:
            self.wait_health("readiness", 200, "UP")
        return self.process.pid

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
                raise DrillError("JVM required SIGKILL instead of graceful shutdown") from None
        self.process = None
        if self.log:
            self.log.close()
            self.log = None

    def http(self, method, path, body=None, *, expected=200, token=None):
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if path.startswith("/api/v1/") and (token or self.token):
            headers["Authorization"] = "Bearer " + (token or self.token)
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}" + path,
                                         data=None if body is None else json.dumps(body).encode(),
                                         method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except (urllib.error.URLError, TimeoutError, OSError):
            status, raw = 0, b""
        try:
            data = json.loads(raw) if raw else None
        except ValueError:
            raise DrillError(f"Invalid JSON for {method} {path}") from None
        if expected is not None:
            require(status == expected, f"{method} {path}: expected {expected}, got {status}")
        return status, data

    def api(self, method, path, body=None, expected=200):
        return self.http(method, "/api/v1" + path, body, expected=expected)[1]

    def wait_health(self, group, status, value):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            actual, body = self.http("GET", "/actuator/health/" + group, expected=None)
            if actual == status and body == {"status": value}:
                return
            if self.process and self.process.poll() is not None:
                raise DrillError("JVM exited before health became ready; inspect private local log")
            time.sleep(0.5)
        raise DrillError(f"Health {group} did not become {status}/{value}")

    def cleanup(self):
        try:
            self.stop()
        finally:
            if self.created:
                self.own_container()
                # Only the random container and its anonymous data volume are removed.
                self.docker("rm", "--force", "--volumes", self.name)


def seed_account_and_leaves(stand):
    credentials = {"email": "t17-" + uuid.uuid4().hex + "@example.org", "password": secrets.token_urlsafe(24)}
    tokens = stand.api("POST", "/accounts/register", credentials, 201)
    stand.token = tokens["accessToken"]
    stand.api("PATCH", "/profile", {"calorieTarget": "2100.00", "proteinTarget": "95.00"})
    def leaf(name, calories):
        return stand.api("POST", "/products", {"name": name, "manualNutrition": {
            "calories": calories, "protein": "10.00", "fat": "2.00", "carbs": "3.00"}}, 201)
    return {"credentials": credentials, "account": tokens["account"]["id"],
            "refresh": tokens["refreshToken"], "a": leaf("T17 ingredient A", "400.00")["id"],
            "b": leaf("T17 ingredient B", "80.00")["id"]}


def seed_current_domains(stand, fixture):
    composition = {"inputMode": "PERCENT", "components": [
        {"productId": fixture["a"], "percent": "50", "yieldFactor": "0.5"},
        {"productId": fixture["b"], "percent": "50", "yieldFactor": "1.5"}]}
    parent = stand.api("POST", "/products", {"name": "T17 composite", "composition": composition}, 201)
    fixture["parent"] = parent["id"]
    require(Decimal(parent["nutritionPer100g"]["calories"]) == 240, "Control composite must have 240 kcal")
    labels = stand.api("GET", "/meal-labels")
    fixture["labels"] = [label["id"] for label in labels[:2]]
    entry = stand.api("POST", "/diary/entries", {"productId": parent["id"], "quantity": "200", "unit": "g",
                         "occurredAt": "2026-11-04T12:00:00Z", "mealLabelIds": fixture["labels"]}, 201)
    fixture["entry"] = entry["id"]
    require(Decimal(entry["nutrition"]["calories"]) == 480, "Control diary must have 480 kcal")
    # Test-only role bootstrap on our own DB. All domain writes remain real API calls.
    stand.sql(f"UPDATE calorica.accounts SET role='ADMIN' WHERE id='{fixture['account']}'")
    base = stand.api("POST", "/admin/products", {"name": "T17 conversion", "manualNutrition": {
        "calories": "100", "protein": "1", "fat": "2", "carbs": "3"}}, 201)
    fixture["base"] = base["id"]
    stand.api("PUT", f"/admin/products/{base['id']}/units/ml", {"gramsPerUnit": "1.03"})
    converted = stand.api("POST", "/diary/entries", {"productId": base["id"], "quantity": "200", "unit": "ml",
                            "occurredAt": "2026-11-04T13:00:00Z", "mealLabelIds": []}, 201)
    fixture["converted"] = converted["id"]
    require(Decimal(converted["consumedGrams"]) == 206, "Conversion must persist 206 consumed grams")


def seed_final_domains(stand, fixture):
    """Required for final T17: fail if any T11/T13/T14/T15 API is absent."""
    product_labels = [stand.api("POST", "/admin/product-labels", {"name": "T17 " + name}, 201)["id"]
                      for name in ("leaf A", "leaf B", "ignored parent")]
    for key, label in zip(("a", "b", "parent"), product_labels):
        stand.api("PUT", "/products/" + fixture[key] + "/labels", {"labelIds": [label]})
    fixture["productLabels"] = product_labels
    grams = stand.api("POST", "/products", {"name": "T17 grams and output", "composition": {
        "inputMode": "GRAMS", "components": [{"productId": fixture["a"], "grams": "60"},
                                                  {"productId": fixture["b"], "grams": "40"}],
        "outputGrams": "80"}}, 201)
    fixture["grams"] = grams["id"]
    require(Decimal(grams["effectiveYieldFactor"]) == Decimal("0.8"), "GRAMS output factor must be 0.8")
    leaf = stand.api("POST", "/products", {"name": "T17 deleted ingredient", "manualNutrition": {
        "calories": "100", "protein": "1", "fat": "2", "carbs": "3"}}, 201)
    parent = stand.api("POST", "/products", {"name": "T17 deleted parent", "composition": {
        "inputMode": "PERCENT", "components": [{"productId": leaf["id"], "percent": "100"}]}}, 201)
    deleted_entry = stand.api("POST", "/diary/entries", {"productId": parent["id"], "quantity": "25", "unit": "g",
        "occurredAt": "2026-11-04T14:00:00Z", "mealLabelIds": fixture["labels"]}, 201)
    fixture["deletedEntry"] = deleted_entry["id"]
    stand.api("DELETE", "/products/" + leaf["id"] + "?mode=CASCADE", expected=204)
    deleted = stand.api("GET", "/diary/entries/" + deleted_entry["id"])
    require(deleted["productDeleted"] and Decimal(deleted["nutrition"]["calories"]) == 25,
            "Cascade must preserve history and calculations")
    analytics = stand.api("GET", "/analytics?date=2026-11-04&zone=Europe%2FMoscow&period=WEEK")
    require(analytics["dayCount"] == 7, "Analytics must include the full calendar week")
    groups = {row["labelId"]: Decimal(row["grams"]) for row in analytics["productLabels"]}
    require(groups.get(product_labels[0]) == 50 and groups.get(product_labels[1]) == 150,
            "Allocated label grams must retain 50/150 control values")
    require(product_labels[2] not in groups, "Composite parent label must be ignored")


def snapshot(stand, fixture):
    responses = {"account": stand.api("GET", "/accounts/me")}
    for key in ("a", "b", "parent", "base", "grams"):
        if key in fixture:
            responses[key] = stand.api("GET", "/products/" + fixture[key])
    if "entry" in fixture:
        responses["diary"] = stand.api("GET", "/diary?date=2026-11-04&zone=Europe%2FMoscow")
        responses["units"] = stand.api("GET", "/products/" + fixture["base"] + "/units")
    if "productLabels" in fixture:
        responses["productLabels"] = stand.api("GET", "/product-labels")
        responses["deletedDiary"] = stand.api("GET", "/diary/entries/" + fixture["deletedEntry"])
        responses["analytics"] = stand.api("GET", "/analytics?date=2026-11-04&zone=Europe%2FMoscow&period=WEEK")
    return responses


def verify_same(stand, fixture, baseline, rows, changes):
    require(snapshot(stand, fixture) == baseline, "API data/calculations changed across lifecycle operation")
    require(stand.data_hashes() == rows, "Stored domain rows/links changed across lifecycle operation")
    require(stand.changesets() == changes, "Migration history changed unexpectedly")


def execute(args, report):
    previous, current = migration_compatibility(args.previous_jar, args.current_jar)
    report["migration_files"] = list(current)
    report["previous_migration_files"] = list(previous)
    report["jars"] = {key: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for key, path in (("previous", args.previous_jar), ("current", args.current_jar))}
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr.splitlines()[0]
    require(re.search(r'version "21\.', version), "JDK 21 is required")
    report["java"] = version
    stand = Stand(args.output, java)
    def passed(name):
        report["checks"].append({"name": name, "status": "PASS"})
        print("PASS " + name, flush=True)
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    try:
        stand.create()
        report["postgres"] = stand.sql("SELECT version()", database="fresh")
        report["postgres_image"] = POSTGRES
        report["postgres_image_id"] = stand.docker("inspect", "--format", "{{.Image}}", stand.name)
        stand.start(args.current_jar, "fresh")
        fresh_changes = stand.changesets()
        require(len(fresh_changes) == len(current), "Fresh schema does not include every current migration")
        passed("fresh_schema")
        stand.start(args.previous_jar, "upgrade")
        require(len(stand.changesets()) == len(previous), "Previous schema mismatch")
        fixture = seed_account_and_leaves(stand)
        old_snapshot = snapshot(stand, fixture)
        old_changes = stand.changesets()
        stand.start(args.current_jar, "upgrade")
        new_snapshot = snapshot(stand, fixture)
        # New fields may be added, but all previously returned fields must retain their values.
        for key, old in old_snapshot.items():
            require(all(new_snapshot[key].get(field) == value for field, value in old.items()),
                    "Upgrade changed pre-existing account/product API values")
        require(stand.changesets()[:len(old_changes)] == old_changes, "Upgrade rewrote applied changesets")
        require([(c['id'], c['md5sum']) for c in stand.changesets()] ==
                [(c['id'], c['md5sum']) for c in fresh_changes], "Fresh and upgraded migration states differ")
        passed("upgrade_preserves_released_data_and_checksums")
        seed_current_domains(stand, fixture)
        if args.phase == "full":
            seed_final_domains(stand, fixture)
            passed("final_domains_grams_output_labels_cascade_analytics")
        baseline, rows, changes = snapshot(stand, fixture), stand.data_hashes(), stand.changesets()
        report["schema"] = changes
        report["domain_tables"] = {name: value.split(":")[0] for name, value in rows.items()}
        pid_before = stand.process.pid
        pid_after = stand.start(args.current_jar, "upgrade")
        require(pid_after != pid_before, "JVM was not replaced")
        verify_same(stand, fixture, baseline, rows, changes)
        passed("actual_jvm_restart_preserves_accounts_products_composition_diary_units")
        pg_before = stand.sql("SELECT pg_postmaster_start_time()")
        stand.own_container()
        stand.docker("stop", "--time", "10", stand.name)
        stand.wait_health("readiness", 503, "DOWN")
        stand.wait_health("liveness", 200, "UP")
        passed("database_failure_readiness_503_liveness_200")
        stand.docker("start", stand.name)
        stand.wait_database()
        stand.wait_health("readiness", 200, "UP")
        require(pg_before != stand.sql("SELECT pg_postmaster_start_time()"), "PostgreSQL was not restarted")
        verify_same(stand, fixture, baseline, rows, changes)
        passed("actual_postgres_restart_preserves_all_rows_links_and_calculations")
        # Existing historical entry must recalculate after a committed ingredient update.
        stand.api("PATCH", "/products/" + fixture["a"], {"manualNutrition": {
            "calories": "500", "protein": "10", "fat": "2", "carbs": "3"}})
        require(Decimal(stand.api("GET", "/diary/entries/" + fixture["entry"])["nutrition"]["calories"]) == 580,
                "Historical diary did not recalculate after ingredient update")
        baseline, rows = snapshot(stand, fixture), stand.data_hashes()
        stand.start(args.current_jar, "upgrade", failing_migration=True)
        require(stand.sql("SELECT to_regclass('calorica.t17_partial') IS NULL") == "t",
                "Failed migration left a partial table")
        require(stand.data_hashes() == rows and stand.changesets() == changes,
                "Failed release changed committed rows or schema history")
        passed("failed_release_never_ready_transactional_migration_preserves_data")
        # Roll back the application only; no SQL rollback or schema cleanup is executed.
        compatibility_error = None
        stand.start(args.previous_jar, "upgrade")
        try:
            stand.api("GET", "/accounts/me")
            old_product = stand.api("GET", "/products/" + fixture["a"])
            require(Decimal(old_product["nutritionPer100g"]["calories"]) == 500, "Previous backend lost leaf data")
            composite = stand.api("GET", "/products/" + fixture["parent"])
            require(Decimal(composite["nutritionPer100g"]["calories"]) == 290,
                    "Previous backend calculates composite incorrectly")
            require(stand.api("GET", "/diary/entries/" + fixture["entry"]) == next(e for e in baseline["diary"]["entries"] if e["id"] == fixture["entry"]),
                    "Previous backend cannot serve existing diary")
        except (DrillError, KeyError, TypeError) as error:
            compatibility_error = str(error)
            report["checks"].append({"name": "previous_backend_functional_rollback", "status": "FAIL",
                                     "reason": compatibility_error})
            print("FAIL previous_backend_functional_rollback: " + compatibility_error, flush=True)
        require(stand.data_hashes() == rows and stand.changesets() == changes,
                "Application rollback changed domain data/schema")
        if compatibility_error is None:
            passed("previous_backend_functional_rollback")
        stand.start(args.current_jar, "upgrade")
        verify_same(stand, fixture, baseline, rows, changes)
        passed("return_to_candidate_preserves_data_after_rollback_attempt")
        # Verify a refresh token written by the released application survives every restart.
        refreshed = stand.api("POST", "/accounts/refresh", {"refreshToken": fixture["refresh"]})
        require(refreshed["account"]["id"] == fixture["account"], "Persisted refresh session was lost")
        passed("released_refresh_session_survives_upgrade_and_restarts")
        require(compatibility_error is None, "Previous released backend is incompatible; production rollback is NOT approved")
    finally:
        stand.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-jar", type=Path, required=True)
    parser.add_argument("--current-jar", type=Path, required=True)
    parser.add_argument("--previous-source", required=True, help="Released source SHA/digest, recorded as caller-supplied provenance")
    parser.add_argument("--current-source", required=True, help="Candidate source SHA/digest")
    parser.add_argument("--phase", choices=("preliminary", "full"), default="full",
                        help="full requires T11/T13/T14/T15 APIs; preliminary is not final SA-17")
    parser.add_argument("--output", type=Path, required=True, help="New private directory for report and JVM logs")
    args = parser.parse_args()
    args.previous_jar = args.previous_jar.resolve(strict=True)
    args.current_jar = args.current_jar.resolve(strict=True)
    args.output = args.output.resolve()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)
    report = {"status": "RUNNING", "phase": args.phase, "previous_source": args.previous_source, "current_source": args.current_source,
              "checks": [], "limitations": ["Local JAR lifecycle drill; not a deployment or APK acceptance.",
                                             "External backups and restore drills are not performed."]}
    try:
        execute(args, report)
        report["status"] = "PASS"
    except (DrillError, subprocess.TimeoutExpired, subprocess.CalledProcessError, KeyboardInterrupt) as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        print("FAIL " + str(error), flush=True)
    finally:
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
