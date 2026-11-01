#!/usr/bin/env python3
"""Validate target OpenAPI, frozen HTTP examples and local documentation links.
Run with openapi-spec-validator==0.7.2 and jsonschema==4.25.1 (or compatible).
No server, application build, database or external requests are used.
"""
import json
import re
from pathlib import Path
from urllib.parse import unquote
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate

root = Path(__file__).resolve().parents[3]
base = Path(__file__).resolve().parent
spec = json.loads((base / 'v1/openapi.json').read_text())
validate(spec)
operations = [op['operationId'] for methods in spec['paths'].values() for op in methods.values()]
assert len(operations) == len(set(operations)), 'Duplicate operationId'

def check(schema, value):
    document = dict(schema, components=spec['components'])
    Draft202012Validator(document, format_checker=FormatChecker()).validate(value)

fixtures = json.loads((base / 'v1/fixtures.json').read_text())
for fixture in fixtures:
    op = spec['paths'][fixture['path']][fixture['method']]
    if 'request' in fixture:
        check(op['requestBody']['content']['application/json']['schema'], fixture['request'])
    key = str(fixture['status'])
    response = op['responses'].get(key, op['responses']['default'])
    media = 'application/problem+json' if fixture['status'] >= 400 else 'application/json'
    check(response['content'][media]['schema'], fixture['response'])
# Sources and contracts use relative local Markdown links. Ignore web and anchors.
files = list(base.rglob('*.md')) + [root / 'docs/semester-mvp/acceptance.md', root / 'docs/semester-mvp/stage-3/technical-specification.md']
for file in files:
    for target in re.findall(r'\]\(([^)]+)\)', file.read_text()):
        target = target.split('#')[0]
        if not target or '://' in target:
            continue
        assert (file.parent / unquote(target)).exists(), (file, target)
registry = (base / 'migrations.md').read_text()
ids = re.findall(r'calorica:([0-9]+-[a-z-]+)', registry)
# Existing 001 is mentioned in prose and table; table reservations must be unique.
reserved = re.findall(r'^\| [0-9]+-[^|]+\| calorica:([^ |]+)', registry, re.M)
assert len(reserved) == len(set(reserved)), 'Duplicate changeset reservation'
print(f'OpenAPI valid: {len(spec["paths"])} paths, {len(operations)} operations; {len(fixtures)} fixtures; links and {len(reserved)} migration reservations valid')
