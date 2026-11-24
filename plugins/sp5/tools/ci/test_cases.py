"""Cases the comparison adds to sp5-export's own tests (run by compare.sh, next to them).

- Cycle assignments with START and END, and one without START: every run is compared by sp5_compare_plugin.
- A cycle assignment without END: the one wanted difference. The Java port follows Schichtplaner 5 (and
  openschichtplaner5 1.0.1), where such an assignment yields no duties; the vendored sp5lib of sp5-export reads it as
  open. So Java on the folder with it must equal Python on the same folder without it, and Python on the folder with
  it must give more duties.
- sp5-vergleich.py, the owner's script, on a bundle made from a Java run: identical, and after a change to the
  bundle a difference that names no name, id or value.
"""

import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest
from exportcli import MAPPING, ROOT

import sp5_compare_plugin as cmp
from tools.generator import generate_with_mixes
from tools.sp5writer import write_sp5_folder

MONTH = "2027-01"
HERE = Path(__file__).resolve().parent


@pytest.fixture(scope="module")
def source():
    return generate_with_mixes(MONTH, 3, 40)


def _folder(source, path: Path, assignments: list[dict]) -> Path:
    from sp5lib.dbf_reader import read_dbf
    from sp5lib.dbf_writer import append_record, get_table_fields

    dataset, mixes = source
    write_sp5_folder(dataset, mixes, path)
    shift_id = next(r["ID"] for r in read_dbf(str(path / "5SHIFT.DBF")) if r["SHORTNAME"] == "A2")
    people = [r["ID"] for r in read_dbf(str(path / "5EMPL.DBF"))]

    def add(table, **values):
        p = str(path / f"{table}.DBF")
        append_record(p, get_table_fields(p), values)

    add("5CYCLE", ID=1, NAME="Tagdienst", POSITION=1, SIZE=2, UNIT=0, HIDE=0)
    add("5CYENT", ID=1, CYCLEEID=1, INDEX=0, SHIFTID=shift_id, WORKPLACID=0)
    for i, a in enumerate(assignments):
        add("5CYASS", ID=i + 1, EMPLOYEEID=people[a["person"]], CYCLEID=1, ENTRANCE=0,
            **{k: a[k] for k in ("START", "END") if k in a})
    return path


def _cmd(folder: Path, out: Path, *options: str) -> list[str]:
    return [sys.executable, str(ROOT / "sp5_export.py"), "--data", str(folder), "--month", MONTH, "--mapping",
            str(MAPPING), "--out", str(out), "--report", str(out.with_suffix(".csv")), *options]


def _run(cmd: list[str]):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", cwd=ROOT)


CLOSED = [{"person": 2, "START": "2026-12-01", "END": "2027-01-31"},  # history and planning month
          {"person": 3, "END": "2027-01-15"},  # no START: no duties in both
          {"person": 4, "START": "2026-11-20", "END": "2026-12-24"}]  # history only
OPEN = [{"person": 5, "START": "2026-12-10"}]  # no END: the wanted difference


def test_cycles_with_start_and_end_are_the_same(source, tmp_path):
    folder = _folder(source, tmp_path / "sp5", CLOSED)
    for options in ([], ["--demand", "sp5"], ["--to", "2027-02"]):
        proc = _run(_cmd(folder, tmp_path / "dataset.json", *options))
        assert proc.returncode == 0, proc.stderr


def test_cycle_without_end_yields_no_duties_in_the_port(source, tmp_path):
    with_open = _folder(source, tmp_path / "offen" / "sp5", CLOSED + OPEN)
    without = _folder(source, tmp_path / "ohne" / "sp5", CLOSED)
    out = tmp_path / "dataset.json"
    cmp.ENABLED[0] = False
    try:
        py_open, java_open, py_open_files, java_open_files = cmp.run_both(_cmd(with_open, out),
                                                                          capture_output=True, text=True,
                                                                          encoding="utf-8", cwd=ROOT)
        py_without = _run(_cmd(without, out))
        py_without_files = cmp._state(cmp._written(_cmd(without, out)[2:], ROOT))
    finally:
        cmp.ENABLED[0] = True
    assert py_open.returncode == java_open.returncode == py_without.returncode == 0
    # the port on the folder with the open assignment = Python on the folder without it (stderr names the folder)
    assert java_open.stderr.replace(str(with_open), "F") == py_without.stderr.replace(str(without), "F")
    assert java_open_files == py_without_files
    # Python reads the open assignment as running on: more previous duties and history for that person
    assert py_open_files[str(out)] != java_open_files[str(out)]
    cmp.RESULTS.append({"test": "cycle without END (wanted difference: no duties, as SP5)", "args": [],
                        "same": True, "differences": [], "features": ["cycle without END"]})


def _bundle(path: Path, out: Path, args: list[str], nights_fair: bool | None = None, extra: dict | None = None) -> Path:
    report = out.with_suffix(".csv")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("dataset.json", out.read_bytes())
        z.writestr("report.csv", report.read_bytes())
        z.writestr("warnings.txt", "".join(w + "\n" for w in out.with_suffix(".warnings").read_text(
            encoding="utf-8").splitlines()))
        request = {"format": "dispofold/sp5-ergebnis", "version": 1, "args": args, "mapping": "mapping.json",
                   "corrections": None}
        if nights_fair is not None:
            request["nightsFair"] = nights_fair
        request.update(extra or {})
        z.writestr("anfrage.json", json.dumps(request))
        z.writestr("mapping.json", Path(MAPPING).read_bytes())
    return path


def test_owner_script_finds_identical_and_anonymous_differences(source, tmp_path):
    dataset = source[0]
    folder = _folder(source, tmp_path / "sp5", CLOSED)
    out = tmp_path / "java" / "dataset.json"
    out.parent.mkdir()
    args = ["--month", MONTH, "--history-months", "12", "--demand", "history", "--mix-min-duties", "10",
            "--hidden-shifts-months", "3", "--recent-months", "3"]
    java = subprocess.run(cmp.JAVA + ["--data", str(folder), *args, "--mapping", str(MAPPING), "--out", str(out),
                                      "--report", str(out.with_suffix(".csv"))],
                          capture_output=True, text=True, encoding="utf-8")
    assert java.returncode == 0, java.stderr
    out.with_suffix(".warnings").write_text("".join(line[len("Warnung: "):] + "\n" for line in java.stderr.splitlines()
                                                    if line.startswith("Warnung: ")), encoding="utf-8")
    bundle = _bundle(tmp_path / "ergebnis.zip", out, args)
    script = HERE.parent / "sp5-vergleich.py"

    def compare(zip_path: Path) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, "-X", "utf8", str(script), "--ergebnis", str(zip_path), "--daten",
                               str(folder), "--sp5-export", str(ROOT), "--ausgabe", str(tmp_path / "ergebnis.txt")],
                              capture_output=True, text=True, encoding="utf-8")

    same = compare(bundle)
    assert same.returncode == 0 and "ERGEBNIS: alles identisch" in same.stdout, same.stdout + same.stderr
    assert "nicht automatisch fair verteilt" not in same.stdout
    # Dispofold without automatic night marking (nightsFair false): its result is that of a mapping whose shift rules
    # all say "unpopular": false; the script gives sp5_export.py such a mapping and finds no difference
    plain = json.loads(Path(MAPPING).read_text(encoding="utf-8"))
    for rule in plain["shifts"]:
        rule.setdefault("unpopular", False)
    plain_path = tmp_path / "plain-mapping.json"
    plain_path.write_text(json.dumps(plain), encoding="utf-8")
    plain_out = tmp_path / "plain" / "dataset.json"
    plain_out.parent.mkdir()
    run = subprocess.run(cmp.JAVA + ["--data", str(folder), *args, "--mapping", str(plain_path), "--out",
                                     str(plain_out), "--report", str(plain_out.with_suffix(".csv"))],
                         capture_output=True, text=True, encoding="utf-8")
    assert run.returncode == 0, run.stderr
    assert not any(s["unpopular"] for s in json.loads(plain_out.read_text(encoding="utf-8"))["shifts"])
    plain_out.with_suffix(".warnings").write_text("".join(line[len("Warnung: "):] + "\n" for line in
                                                          run.stderr.splitlines() if line.startswith("Warnung: ")),
                                                  encoding="utf-8")
    off = compare(_bundle(tmp_path / "ohne-nacht.zip", plain_out, args, nights_fair=False))
    assert off.returncode == 0 and "ERGEBNIS: alles identisch" in off.stdout, off.stdout + off.stderr
    # since 0.25.0: areas from history (Dispofold only) are named, with the counts, as a reason for differences
    counts = {"fromGroups": 40, "fromHistory": 30, "reserve": 12, "droppedGroupAreas": 0, "peopleWithoutHistory": 3}
    hist = compare(_bundle(tmp_path / "historie.zip", out, args,
                           extra={"areaSource": "reserve", "historyAreaDuties": 3, "areaCounts": counts}))
    assert "Bereiche einer Person aus: Historie, Gruppen als Reserve (ab 3 Diensten)" in hist.stdout, hist.stdout
    assert "Bereiche: 40 aus Gruppen, 30 aus der Historie, 12 aus Gruppen als Reserve" in hist.stdout
    assert "nicht automatisch fair verteilt" in off.stdout
    # the same result compared as if nights were marked automatically: the difference shows
    assert compare(_bundle(tmp_path / "mit-nacht.zip", plain_out, args, nights_fair=True)).returncode == 1
    # a changed person name and a changed number: found, and nothing of the data in the result
    changed = json.loads(out.read_text(encoding="utf-8"))
    person = changed["people"][1]
    secret = person["lastName"]
    person["lastName"] = "Anders"
    changed["people"][2]["weeklyHours"] = 1.5
    out.write_text(json.dumps(changed, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    diff = compare(_bundle(tmp_path / "geaendert.zip", out, args))
    text = (tmp_path / "ergebnis.txt").read_text(encoding="utf-8")
    assert diff.returncode == 1 and "dataset.json: VERSCHIEDEN" in text
    assert "$.people[1].lastName: Wert verschieden" in text and "$.people[2].weeklyHours" in text
    names = {p["lastName"] for p in dataset["people"]} | {p["firstName"] for p in dataset["people"]}
    ids = {p["id"] for p in changed["people"]} | {p.get("externalId") or "" for p in changed["people"]}
    assert secret not in text and "Anders" not in text
    assert not [n for n in names | ids if n and re.search(rf"\b{re.escape(n)}\b", text)]
    shutil.rmtree(tmp_path / "java")


def _with_decimals(path: Path, table: str, field: str) -> None:
    """Declares the numeric column `field` of `table` with one decimal and writes its values so (5 becomes 5.0)."""
    p = path / f"{table}.DBF"
    data = bytearray(p.read_bytes())
    count, header, size = int.from_bytes(data[4:8], "little"), int.from_bytes(data[8:10], "little"), \
        int.from_bytes(data[10:12], "little")
    offset, at = 1, 32
    while data[at] != 0x0D:
        name = data[at:at + 11].split(b"\0")[0].decode("ascii")
        length = data[at + 16]
        if name == field:
            assert chr(data[at + 11]) == "N" and data[at + 17] == 0
            data[at + 17] = 1
            for i in range(count):
                start = header + i * size + offset
                raw = data[start:start + length].strip()
                if raw:
                    data[start:start + length] = f"{int(raw)}.0".rjust(length).encode("ascii")
            p.write_bytes(bytes(data))
            return
        offset += length
        at += 32
    raise AssertionError(f"{table}.{field} missing")


@pytest.mark.parametrize("columns", [[("5EMPL", "ID"), ("5MASHI", "EMPLOYEEID")], [("5SHIFT", "ID"), ("5MASHI", "SHIFTID")],
                                     [("5EMPL", "ID")]])
def test_ids_stored_with_decimals(source, tmp_path, columns):
    """Ids in columns declared with decimals: whatever Python makes of them (a dataset or an error), the port too."""
    folder = _folder(source, tmp_path / "sp5", CLOSED)
    for table, field in columns:
        _with_decimals(folder, table, field)
    _run(_cmd(folder, tmp_path / "dataset.json", "--to", "2027-02"))
    assert cmp.RESULTS[-1]["same"], cmp.RESULTS[-1]["differences"]


def test_the_proposal_mode_prints_only_counts(tmp_path):
    # sp5-vergleich.py --vorschlag: the counts Dispofold wrote into the result file, nothing else
    counts = {"areas": {"matched": 5, "different": 0, "missing": 0, "extra": 2},
              "shifts": {"matched": 13, "different": 5, "missing": 0, "extra": 0},
              "groups": {"matched": 11, "different": 3, "missing": 0, "extra": 0},
              "compensatoryRest": {"matched": 1, "different": 0, "missing": 0, "extra": 0}}
    bundle = tmp_path / "ergebnis.zip"
    with zipfile.ZipFile(bundle, "w") as z:
        z.writestr("vorschlag-vergleich.json", json.dumps(counts))
    script = HERE.parent / "sp5-vergleich.py"
    out = tmp_path / "vorschlag.txt"
    run = subprocess.run([sys.executable, "-X", "utf8", str(script), "--ergebnis", str(bundle), "--vorschlag",
                          "--ausgabe", str(out)], capture_output=True, text=True, encoding="utf-8")
    assert run.returncode == 0, run.stderr
    text = out.read_text(encoding="utf-8")
    assert "Bereiche: 5 gleich, 0 verschieden, 0 fehlen im Vorschlag, 2 zusätzlich im Vorschlag" in text
    assert "Schichten: 13 gleich, 5 verschieden" in text and "Ersatzruhe: 1 gleich" in text
    # without the file (an older Dispofold): an error, no counts
    with zipfile.ZipFile(tmp_path / "alt.zip", "w") as z:
        z.writestr("anfrage.json", "{}")
    old = subprocess.run([sys.executable, "-X", "utf8", str(script), "--ergebnis", str(tmp_path / "alt.zip"),
                          "--vorschlag", "--ausgabe", str(out)], capture_output=True, text=True, encoding="utf-8")
    assert old.returncode == 2 and "vorschlag-vergleich.json" in old.stderr
    # since 0.24.0: the basis the proposals took, and the counts with the other basis
    counts2 = dict(counts, basis="families", other=dict(counts, basis="groups"))
    with zipfile.ZipFile(bundle, "w") as z:
        z.writestr("vorschlag-vergleich.json", json.dumps(counts2))
    run = subprocess.run([sys.executable, "-X", "utf8", str(script), "--ergebnis", str(bundle), "--vorschlag",
                          "--ausgabe", str(out)], capture_output=True, text=True, encoding="utf-8")
    assert run.returncode == 0, run.stderr
    text = out.read_text(encoding="utf-8")
    assert "Bereiche aus Schichtnamen (vorgeschlagen):" in text and "Bereiche aus Gruppen (zum Vergleich):" in text
    assert text.count("Bereiche: 5 gleich") == 2
