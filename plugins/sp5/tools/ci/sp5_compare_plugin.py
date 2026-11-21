"""pytest plugin for the tests of sp5-export: every run of sp5_export.py there (tests/exportcli.py starts it with
subprocess.run) also runs the Java port of the plugin sp5 with the same arguments in the same directory. Exit code,
stdout, stderr and every file the program writes (--out, --report and their .tmp) must be the same bytes. The test
itself goes on with the Python result, so sp5-export's own assertions are unchanged.

Use (see compare.sh): SP5_JAVA="java -cp ... app.dispofold.plugins.sp5.port.Sp5Main" SP5_COMPARE_OUT=result.json
python -m pytest -p sp5_compare_plugin tests/ (with this folder on PYTHONPATH).
"""

import csv
import io
import json
import os
import shlex
import subprocess
from pathlib import Path

REAL_RUN = subprocess.run
JAVA = shlex.split(os.environ.get("SP5_JAVA", ""))
RESULTS: list[dict] = []
ENABLED = [True]


def _written(args: list[str], cwd) -> list[Path]:
    paths = []
    for flag in ("--out", "--report"):
        if flag in args and args.index(flag) + 1 < len(args):
            p = Path(args[args.index(flag) + 1])
            p = p if p.is_absolute() else Path(cwd or ".") / p
            paths += [p, p.with_name(p.name + ".tmp")]
    return paths


def _state(paths: list[Path]) -> dict:
    return {str(p): (p.read_bytes() if p.is_file() else None) for p in paths}


def _restore(state: dict) -> None:
    for name, data in state.items():
        p = Path(name)
        if data is None:
            p.unlink(missing_ok=True)
        else:
            p.write_bytes(data)


def _first_difference(a: bytes | None, b: bytes | None) -> str:
    if a is None or b is None:
        return f"python {'missing' if a is None else 'written'}, java {'missing' if b is None else 'written'}"
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            line = a[:i].count(b"\n") + 1
            return f"byte {i} (line {line}): python {a[max(0, i - 40):i + 40]!r} java {b[max(0, i - 40):i + 40]!r}"
    return f"length python {len(a)} java {len(b)}"


def run_both(cmd: list, **kw):
    """Python, then Java on the state before it; returns (python process, java process, python files, java files)."""
    args = [str(a) for a in cmd[2:]]
    paths = _written(args, kw.get("cwd"))
    before = _state(paths)
    py = REAL_RUN(cmd, **kw)
    py_files = _state(paths)
    _restore(before)
    java_kw = {k: v for k, v in kw.items() if k in ("cwd", "env")}
    java = REAL_RUN(JAVA + args, capture_output=True, text=True, encoding="utf-8", **java_kw)
    java_files = _state(paths)
    _restore(py_files)
    return py, java, py_files, java_files


def _features(args: list[str], py, py_files: dict) -> list[str]:
    """What a run covers, from its options and Python's output: for the coverage list of the comparison."""
    f = set()
    opt = lambda flag: args[args.index(flag) + 1] if flag in args and args.index(flag) + 1 < len(args) else None
    if opt("--to"):
        f.add("range of months")
    f.add(f"demand {opt('--demand') or 'history'}")
    if opt("--corrections"):
        f.add("corrections file")
    if "--no-shift-mix" in args:
        f.add("no shift mix")
    for flag in ("--areas-from-history", "--shifts-from-history", "--hidden-shifts-months", "--recent-months",
                 "--mix-min-duties", "--history-months"):
        if opt(flag):
            f.add(f"option {flag}")
    if py.returncode != 0:
        f.add(f"exit {py.returncode}")
    if any(line.startswith("Warnung: ") for line in (py.stderr or "").splitlines()):
        f.add("warnings")
    report = next((v for k, v in py_files.items() if opt("--report") and Path(k).name == Path(opt("--report")).name),
                  None)
    if report:
        for row in list(csv.reader(io.StringIO(report.decode("utf-8")), delimiter=";"))[1:]:
            if row:
                f.add(f"report {row[0]}")
            if row and row[0] == "korrektur" and len(row) > 24:
                f.add(f"correction {row[24]}" + (" exclude" if row[23] == "exclude" else ""))
    return sorted(f)


def compare(name: str, args: list[str], py, java, py_files: dict, java_files: dict) -> list[str]:
    diffs = []
    if py.returncode != java.returncode:
        diffs.append(f"exit code python {py.returncode} java {java.returncode}")
    for stream in ("stdout", "stderr"):
        a, b = getattr(py, stream) or "", getattr(java, stream) or ""
        if a != b:
            diffs.append(f"{stream}: {_first_difference(a.encode(), b.encode())}")
    for path in py_files:
        if py_files[path] != java_files.get(path):
            diffs.append(f"{Path(path).name}: {_first_difference(py_files[path], java_files.get(path))}")
    RESULTS.append({"test": name, "args": args, "same": not diffs, "differences": diffs,
                    "features": _features(args, py, py_files) + (["cycle"] if "cycle" in name else [])})
    return diffs


def pytest_configure(config):
    if not JAVA:
        raise RuntimeError("SP5_JAVA is not set")

    def run(cmd, *a, **kw):
        if not (ENABLED[0] and isinstance(cmd, list) and len(cmd) > 1 and str(cmd[1]).endswith("sp5_export.py")):
            return REAL_RUN(cmd, *a, **kw)
        py, java, py_files, java_files = run_both(cmd, **kw)
        compare(os.environ.get("PYTEST_CURRENT_TEST", "?").split(" ")[0], [str(x) for x in cmd[2:]], py, java,
                py_files, java_files)
        return py

    subprocess.run = run


def pytest_sessionfinish(session, exitstatus):
    out = os.environ.get("SP5_COMPARE_OUT")
    if out:
        Path(out).write_text(json.dumps(RESULTS, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    covered = {}
    for r in RESULTS:
        for x in r["features"]:
            covered[x] = covered.get(x, 0) + 1
    print("\nsp5 comparison covers (runs):")
    for x in sorted(covered):
        print(f"  {x}: {covered[x]}")
    different = [r for r in RESULTS if not r["same"]]
    print(f"\nsp5 comparison: {len(RESULTS)} runs, {len(RESULTS) - len(different)} the same, {len(different)} "
          "different")
    for r in different[:30]:
        print(f"  {r['test']} {' '.join(r['args'])}")
        for d in r["differences"][:5]:
            print(f"    {d}")
    if different or not RESULTS:
        session.exitstatus = 1
