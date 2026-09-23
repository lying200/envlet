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
        venv.EnvBuilder(with_pip=False, symlinks=True).create(root / name)
    for name in ["extras", "file-extras", "fake-home", "shared", "blocked", ".control"]:
        (root / name).mkdir(exist_ok=True)
    (root / "extras/envlet_fixture_dependency.py").write_text('VALUE = "fixture-dependency"\n')
    (root / "shared/envlet_cwd_dependency.py").write_text('VALUE = "cwd-dependency"\n')
    (root / "file-extras/envlet_file_dependency.py").write_text('VALUE = "file-dependency"\n')
    (root / "run.env").write_text(f"ENVLET_ENVFILE_MODE=test\nPYTHONPATH={root}/file-extras\n")
    (root / "unset.env").write_text(f"HOME={root}/fake-home\nENVLET_ENVFILE_MODE=test\n")
    (root / ".envrc").write_text(
        'export VIRTUAL_ENV="$PWD/.venv"\nPATH_add "$VIRTUAL_ENV/bin"\n'
        'export PYTHONPATH="$PWD/extras:"\nexport ENVLET_PYTHON_TEST=fixture-only\n'
        'export ENVLET_ENVFILE_MODE=development\n'
    )
    (root / "blocked/.envrc").write_text("export ENVLET_UNAPPROVED_CHILD=1\n")
    (root / "probe.py").write_text('''import json, os, sys
from pathlib import Path
try:
    import envlet_fixture_dependency
    dependency = envlet_fixture_dependency.VALUE == "fixture-dependency"
except ImportError:
    dependency = False
try:
    import envlet_file_dependency
    file_dependency = envlet_file_dependency.VALUE == "file-dependency"
except ImportError:
    file_dependency = False
try:
    import envlet_cwd_dependency
    cwd_dependency = envlet_cwd_dependency.VALUE == "cwd-dependency"
except ImportError:
    cwd_dependency = False
result = dict(cwd_dependency=cwd_dependency, file_dependency=file_dependency,
              envfile_override=os.environ.get("ENVLET_ENVFILE_MODE") == "test",
              direct_override=os.environ.get("ENVLET_ENVFILE_MODE") == "direct",
              envfile_unset=os.environ.get("HOME") == str(Path(__file__).parent / "fake-home"),
              venv=sys.prefix != sys.base_prefix,
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
