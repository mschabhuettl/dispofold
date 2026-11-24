#!/usr/bin/env python3
"""Compares the result of the Dispofold plugin sp5 with sp5_export.py on the same SP5 folder.

    python -X utf8 sp5-vergleich.py --ergebnis dispofold-sp5-ergebnis-2027-01.zip --daten <SP5 folder>
        --sp5-export <sp5-export checkout> [--ausgabe sp5-vergleich.txt]

The ZIP file comes from Dispofold (Einstellungen -> Erweiterungen -> Schichtplaner 5 -> "Ergebnis speichern"): the
plugin's dataset.json, report.csv and warnings.txt and what they were made with (anfrage.json, mapping.json,
korrekturen.json). The script runs sp5_export.py of the checkout with the same options, mapping and corrections
file on the SP5 folder and compares the three files. It prints only an anonymous result: per file identical or not,
and for differences JSON paths with list positions instead of ids, field names of the data format, column names of
the report and counts; no names, codes, ids, dates or values. The same text goes to --ausgabe to send back.
Python 3.10 or later, standard library only. Nothing is written into the SP5 folder.

    python -X utf8 sp5-vergleich.py --ergebnis dispofold-sp5-ergebnis-2027-01.zip --vorschlag [--ausgabe ...]

compares instead what "Zuordnung vorschlagen" proposes for this folder from an empty mapping with the own mapping in
the ZIP file (vorschlag-vergleich.json, made by Dispofold): per section only counts of entries that are the same,
different, missing in the proposal and extra in it; no codes or names. It needs neither the folder nor sp5-export.
"""

import argparse
import csv
import io
import json
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter
from pathlib import Path

# Field names of the dataset format and of the corrections section: printed as they are. Any other object key (an
# id, a code) is printed as its position.
SCHEMA_KEYS = {
    "format", "version", "source", "areas", "id", "name", "color", "shifts", "areaId", "code", "start", "end",
    "category", "unpopular", "people", "firstName", "lastName", "shortName", "externalId", "weeklyHours", "areaIds",
    "hoursAccount", "compRestBalance", "familyRules", "weekdays", "latestEnd", "earliestStart", "noNights", "note",
    "entryDate", "exitDate", "shiftMix", "excludedFrom", "restrictions", "personId", "shiftId", "weekday", "kind",
    "validFrom", "validTo", "demand", "days", "date", "from", "to", "min", "max", "holidays", "absenceTypes",
    "reducesTarget", "compensatoryRest", "creditsHours", "periods", "month", "absences", "typeCode", "wishes",
    "fixed", "previousDuties", "history", "appliedCorrections", "exclude", "categories", "duties", "hours",
    "nightDuties", "weekendDuties", "holidayDuties", "sundays", "freeSundays", "unpopular", "compRestEarned",
    "compRestUsed", "hoursAccountAfter", "compRestBalanceAfter", "wishesMet", "type", "half",
}
WARNING = "Warnung: "
SECTIONS = {"areas": "Bereiche", "shifts": "Schichten", "groups": "Gruppen", "compensatoryRest": "Ersatzruhe"}
BASIS = {"groups": "Gruppen", "families": "Schichtnamen"}


def run_python(checkout: Path, data: Path, work: Path, request: dict) -> tuple[int, str]:
    out = work / "python"
    out.mkdir()
    cmd = [sys.executable, "-X", "utf8", str(checkout / "sp5_export.py"), "--data", str(data), *request["args"],
           "--mapping", str(work / "mapping.json"), "--out", str(out / "dataset.json"),
           "--report", str(out / "report.csv")]
    if request.get("corrections"):
        cmd += ["--corrections", str(work / request["corrections"])]
    proc = subprocess.run(cmd, cwd=checkout, capture_output=True)
    # lines as Dispofold writes them: split at \n only (a warning may hold other line separators), the \r\n of a
    # Windows pipe taken as \n
    stderr = proc.stderr.decode("utf-8", "replace").replace("\r\n", "\n")
    warnings = "".join(line[len(WARNING):] + "\n" for line in lines(stderr) if line.startswith(WARNING))
    (out / "warnings.txt").write_text(warnings, encoding="utf-8", newline="")
    return proc.returncode, stderr


def lines(text: str) -> list[str]:
    return text.split("\n")[:-1] if text.endswith("\n") else text.split("\n") if text else []


def key_name(key: str, keys: list) -> str:
    return key if key in SCHEMA_KEYS else f"#{keys.index(key)}"


def json_diff(a, b, path: str, found: list) -> None:
    if type(a) is not type(b):
        found.append(f"{path}: Typ verschieden")
    elif isinstance(a, dict):
        keys = sorted(set(a) | set(b), key=lambda k: (k not in SCHEMA_KEYS, list(a).index(k) if k in a else 10**9))
        if list(a) != list(b) and set(a) == set(b):
            found.append(f"{path}: Reihenfolge der Felder verschieden")
        for k in keys:
            name = f"{path}.{key_name(k, keys)}"
            if k not in a:
                found.append(f"{name}: nur bei Dispofold")
            elif k not in b:
                found.append(f"{name}: nur bei sp5_export.py")
            else:
                json_diff(a[k], b[k], name, found)
    elif isinstance(a, list):
        if len(a) != len(b):
            found.append(f"{path}: {len(b)} Einträge bei sp5_export.py, {len(a)} bei Dispofold")
        for i, (x, y) in enumerate(zip(a, b)):
            json_diff(x, y, f"{path}[{i}]", found)
    elif a != b:
        found.append(f"{path}: Wert verschieden")


def compare_dataset(java: bytes, python: bytes, lines: list) -> bool:
    if java == python:
        lines.append("dataset.json: identisch")
        return True
    lines.append("dataset.json: VERSCHIEDEN")
    try:
        a, b = json.loads(java), json.loads(python)
    except ValueError:
        lines.append("  eine der Dateien ist kein JSON")
        return False
    found: list = []
    json_diff(a, b, "$", found)
    if not found:
        lines.append("  gleicher Inhalt, nur die Schreibweise unterscheidet sich (Zahlen, Leerzeichen)")
    # per section and kind of difference, then the first paths
    sections = Counter(f.split(":")[0].split("[")[0].split(".")[1] if "." in f else "$" for f in found)
    for section, n in sorted(sections.items()):
        lines.append(f"  {section}: {n} Unterschiede")
    for f in found[:50]:
        lines.append(f"  {f}")
    if len(found) > 50:
        lines.append(f"  ... und {len(found) - 50} weitere")
    return False


def compare_report(java: bytes, python: bytes, lines: list) -> bool:
    if java == python:
        lines.append("report.csv: identisch")
        return True
    lines.append("report.csv: VERSCHIEDEN")
    ra = list(csv.reader(io.StringIO(java.decode("utf-8", "replace")), delimiter=";"))
    rb = list(csv.reader(io.StringIO(python.decode("utf-8", "replace")), delimiter=";"))
    header = rb[0] if rb else []
    lines.append(f"  Zeilen: {len(rb)} bei sp5_export.py, {len(ra)} bei Dispofold")
    checks = Counter()
    columns = Counter()
    for i, (x, y) in enumerate(zip(ra, rb)):
        if x != y:
            checks[y[0] if y and y[0].isidentifier() else "?"] += 1
            for j in range(max(len(x), len(y))):
                if (x[j] if j < len(x) else None) != (y[j] if j < len(y) else None):
                    columns[header[j] if j < len(header) else f"Spalte {j + 1}"] += 1
    for name, n in sorted(checks.items()):
        lines.append(f"  pruefung {name}: {n} Zeilen verschieden")
    for name, n in sorted(columns.items()):
        lines.append(f"  Spalte {name}: {n} Werte verschieden")
    if not checks and len(ra) == len(rb):
        lines.append("  gleicher Inhalt, nur die Schreibweise unterscheidet sich")
    return False


def compare_warnings(java: bytes, python: bytes, lines: list) -> bool:
    if java == python:
        lines.append("warnings.txt: identisch")
        return True
    lines.append("warnings.txt: VERSCHIEDEN")
    a, b = lines(java.decode("utf-8", "replace")), lines(python.decode("utf-8", "replace"))
    lines.append(f"  Warnungen: {len(b)} bei sp5_export.py, {len(a)} bei Dispofold")
    only_b = [i for i, w in enumerate(b) if w not in a]
    only_a = [i for i, w in enumerate(a) if w not in b]
    lines.append(f"  nur bei sp5_export.py: {len(only_b)} (Positionen {only_b[:20]})")
    lines.append(f"  nur bei Dispofold: {len(only_a)} (Positionen {only_a[:20]})")
    if not only_a and not only_b:
        lines.append("  dieselben Warnungen in anderer Reihenfolge")
    return False


def proposal(args) -> int:
    with zipfile.ZipFile(args.ergebnis) as z:
        if "vorschlag-vergleich.json" not in z.namelist():
            print("Fehler: die ZIP-Datei hat keine vorschlag-vergleich.json (Dispofold 0.22.0 oder neuer)",
                  file=sys.stderr)
            return 2
        counts = json.loads(z.read("vorschlag-vergleich.json").decode("utf-8"))
    lines = ["Vorschlag der Zuordnung (aus leerer Zuordnung) gegen die eigene Zuordnung"]
    # since 0.24.0: the basis the proposals took for this folder, and the counts with the other one
    for part in [counts] + ([counts["other"]] if isinstance(counts.get("other"), dict) else []):
        if "basis" in part:
            lines.append(f"Bereiche aus {BASIS.get(part['basis'], part['basis'])}"
                         + (" (vorgeschlagen)" if part is counts else " (zum Vergleich)") + ":")
        for key, label in SECTIONS.items():
            c = part.get(key, {})
            lines.append(f"{label}: {c.get('matched', 0)} gleich, {c.get('different', 0)} verschieden, "
                         f"{c.get('missing', 0)} fehlen im Vorschlag, {c.get('extra', 0)} zusätzlich im Vorschlag")
    text = "\n".join(lines) + "\n"
    print(text, end="")
    Path(args.ausgabe).write_text(text, encoding="utf-8")
    print(f"gespeichert: {args.ausgabe}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Ergebnis der Erweiterung sp5 mit sp5_export.py vergleichen "
                                            "(anonymes Ergebnis).")
    p.add_argument("--ergebnis", required=True, help="ZIP-Datei aus Dispofold („Ergebnis speichern“)")
    p.add_argument("--daten", help="Datenordner von Schichtplaner 5 (wird nur gelesen)")
    p.add_argument("--sp5-export", help="Ordner mit sp5-export (sp5_export.py)")
    p.add_argument("--vorschlag", action="store_true",
                   help="statt des Vergleichs: Vorschlag aus dem Ordner gegen die eigene Zuordnung (nur Zahlen)")
    p.add_argument("--ausgabe", default="sp5-vergleich.txt", help="Textdatei mit dem Ergebnis zum Zurückschicken")
    args = p.parse_args(argv)
    if args.vorschlag:
        return proposal(args)
    if not args.daten or not args.sp5_export:
        p.error("--daten und --sp5-export fehlen (oder --vorschlag)")
    checkout = Path(args.sp5_export).resolve()
    if not (checkout / "sp5_export.py").is_file():
        print(f"Fehler: {checkout} enthält kein sp5_export.py", file=sys.stderr)
        return 2
    lines = ["Vergleich Dispofold (Erweiterung sp5) mit sp5_export.py"]
    commit = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], capture_output=True, text=True)
    lines.append(f"sp5-export: {commit.stdout.strip() if commit.returncode == 0 else 'Commit unbekannt'}, "
                 f"Python {sys.version.split()[0]}")
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        with zipfile.ZipFile(args.ergebnis) as z:
            for name in ("dataset.json", "report.csv", "warnings.txt", "anfrage.json", "mapping.json",
                         "korrekturen.json"):
                if name in z.namelist():
                    (work / name).write_bytes(z.read(name))
        request = json.loads((work / "anfrage.json").read_text(encoding="utf-8"))
        lines.append(f"Optionen: {' '.join(a for a in request['args'] if a.startswith('--'))}; Korrekturdatei: "
                     f"{'ja' if request.get('corrections') else 'nein'}")
        if request.get("nightsFair") is False:
            # Dispofold marks night shifts only when chosen; sp5_export.py marks them when a shift rule has no
            # "unpopular". The same result: every such rule with "unpopular": false for the Python run.
            mapping = json.loads((work / "mapping.json").read_text(encoding="utf-8"))
            for rule in mapping.get("shifts", []):
                if isinstance(rule, dict):
                    rule.setdefault("unpopular", False)
            (work / "mapping.json").write_text(json.dumps(mapping, ensure_ascii=False), encoding="utf-8")
            lines.append("Nachtschichten nicht automatisch fair verteilt: für sp5_export.py hat jede Schichtregel ohne "
                         "Angabe „unpopular: false“")
        code, stderr = run_python(checkout, Path(args.daten).resolve(), work, request)
        if code != 0:
            lines.append(f"sp5_export.py endete mit Fehler (Code {code}); kein Vergleich möglich")
            same = False
        else:
            py = work / "python"
            same = all([
                compare_dataset((work / "dataset.json").read_bytes(), (py / "dataset.json").read_bytes(), lines),
                compare_report((work / "report.csv").read_bytes(), (py / "report.csv").read_bytes(), lines),
                compare_warnings((work / "warnings.txt").read_bytes(), (py / "warnings.txt").read_bytes(), lines)])
    lines.append("ERGEBNIS: alles identisch" if same else "ERGEBNIS: Unterschiede gefunden")
    text = "\n".join(lines) + "\n"
    print(text, end="")
    Path(args.ausgabe).write_text(text, encoding="utf-8")
    print(f"gespeichert: {args.ausgabe}")
    return 0 if same else 1


if __name__ == "__main__":
    sys.exit(main())
