#!/usr/bin/env python3
"""Disposable plain-direnv fixture/controller for verify-python-support.groovy."""
import argparse
from pathlib import Path
import subprocess
import time
import venv

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("mode", choices=["prepare", "control"])
parser.add_argument("root", type=Path)
parser.add_argument("--report", type=Path, help="IDE report path, required by control")
args = parser.parse_args()
root = args.root.resolve()
marker = root / ".envlet-python-validation"


def direnv(action):
    subprocess.run(["direnv", action, str(root)], check=True, capture_output=True)


def wait_for(ready, label, seconds=180):
    deadline = time.monotonic() + seconds
    while not ready():
        if time.monotonic() >= deadline:
            raise TimeoutError(label)
        time.sleep(0.2)


if args.mode == "prepare":
    if root.exists() and any(root.iterdir()) and not marker.exists():
        raise SystemExit("Refusing to overwrite a non-fixture directory")
    root.mkdir(parents=True, exist_ok=True)
    marker.touch()
    for name in [".venv", ".venv-next"]:
        venv.EnvBuilder(with_pip=False).create(root / name)
    for name in ["extras", "shared", "blocked", ".control"]:
        (root / name).mkdir(exist_ok=True)
    (root / "extras/envlet_fixture_dependency.py").write_text('VALUE = "fixture-dependency"\n')
    (root / ".envrc").write_text(
        'export VIRTUAL_ENV="$PWD/.venv"\nPATH_add "$VIRTUAL_ENV/bin"\n'
        'export PYTHONPATH="$PWD/extras"\nexport ENVLET_PYTHON_TEST=fixture-only\n'
    )
    (root / "blocked/.envrc").write_text("export ENVLET_UNAPPROVED_CHILD=1\n")
    (root / "probe.py").write_text('''import json, os, sys
from pathlib import Path
try:
    import envlet_fixture_dependency
    dependency = envlet_fixture_dependency.VALUE == "fixture-dependency"
except ImportError:
    dependency = False
result = dict(venv=sys.prefix != sys.base_prefix,
              env=os.environ.get("ENVLET_PYTHON_TEST") == "fixture-only",
              dependency=dependency, executable=sys.executable)
Path(sys.argv[1]).write_text(json.dumps(result))
''')
    # Only this script-authored fixture is approved; the child deliberately stays blocked.
    direnv("allow")
    subprocess.run(["direnv", "deny", str(root / "blocked")], check=True, capture_output=True)
    print("Prepared and approved disposable fixture:", root)
else:
    if not marker.exists() or args.report is None:
        raise SystemExit("control requires a prepared fixture and --report")
    controls = root / ".control"
    for path in controls.iterdir():
        if path.name.startswith(("ready-", "done-")):
            path.unlink()
    original = (root / ".envrc").read_text()
    try:
        for step in ["switch", "deny", "allow", "unset", "restore"]:
            wait_for(lambda: (controls / ("ready-" + step)).exists(), step)
            if step == "switch":
                (root / ".envrc").write_text(original.replace('/.venv"', '/.venv-next"'))
            elif step == "unset":
                (root / ".envrc").write_text(original + "\nunset HOME\n")
            elif step == "restore":
                (root / ".envrc").write_text(original)
            direnv("deny" if step == "deny" else "allow")
            (controls / ("done-" + step)).write_text("done")
            print(step, flush=True)
        wait_for(lambda: args.report.exists() and any(
            marker in args.report.read_text() for marker in ["FINISHED", "ERROR="]
        ), "IDE acceptance")
        if "ERROR=" in args.report.read_text():
            raise RuntimeError("IDE acceptance failed; inspect the fixture report")
    finally:
        (root / ".envrc").write_text(original)
        direnv("allow")
