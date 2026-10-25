#!/usr/bin/env python3
"""Verify catalog patches using isolated indexes; never modify source trees."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

catalog = Path(__file__).resolve().parent
root = catalog.parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--series', default='series.json',
                    help='Manifest path relative to this catalog, or an absolute path')
args = parser.parse_args()
manifest = catalog / args.series
series = json.loads(manifest.read_text())
for entry in series['repositories']:
    repo = root / entry['repo']
    with tempfile.TemporaryDirectory(prefix='evo-catalog-') as temporary:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / 'index'))
        def git(*args):
            return subprocess.check_output(['git', '-C', str(repo), *args], env=env, text=True).strip()
        git('read-tree', entry['base_commit'])
        for patch in entry['patches']:
            git('apply', '--cached', str(manifest.parent / patch['file']))
        tree = git('write-tree')
        if tree != entry['result_tree']:
            raise SystemExit(f"FAIL {entry['repo']}: {tree}")
    print(f"PASS {entry['repo']}")
print(f"Verified {len(series['repositories'])} repositories")
