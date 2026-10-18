#!/usr/bin/env python3
"""Verify catalog patches using isolated indexes; never modify source trees."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

catalog = Path(__file__).resolve().parent
root = catalog.parents[4]
series = json.loads((catalog / 'series.json').read_text())
for entry in series['repositories']:
    repo = root / entry['repo']
    with tempfile.TemporaryDirectory(prefix='evo-catalog-') as temporary:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / 'index'))
        def git(*args):
            return subprocess.check_output(['git', '-C', str(repo), *args], env=env, text=True).strip()
        git('read-tree', entry['base_commit'])
        for patch in entry['patches']:
            git('apply', '--cached', str(catalog / patch['file']))
        tree = git('write-tree')
        if tree != entry['result_tree']:
            raise SystemExit(f"FAIL {entry['repo']}: {tree}")
    print(f"PASS {entry['repo']}")
print(f"Verified {len(series['repositories'])} repositories")
