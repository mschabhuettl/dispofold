import json
import re
from collections import defaultdict
from datetime import date
from pathlib import Path

import pytest

from demodata.feasibility import check_feasibility, rules_for_day
from demodata.generator import dumps, generate
from demodata.holidays import easter_sunday
from demodata.timeutil import DAY, interval, minutes

FIXTURES = Path(__file__).resolve().parents[3] / "tests" / "fixtures" / "synthetic"
TIME = re.compile(r"^\d\d:\d\d$")


SMALL = {"people": 20, "areas": ["AN", "DR", "TV"]}


@pytest.fixture(scope="module")
def demo():
    return generate("2027-01", 1)


@pytest.fixture(scope="module")
def large():
    return generate("2027-01", 1, profile="large")


@pytest.mark.parametrize("name,month,kwargs", [
    ("demo-dataset-2027-01.synthetic.json", "2027-01", {}),
    ("small-dataset-2027-01.synthetic.json", "2027-01", SMALL),
    ("small-dataset-2027-03.synthetic.json", "2027-03", SMALL),
    ("small-dataset-2027-10.synthetic.json", "2027-10", SMALL),
    ("large-dataset-2027-01.synthetic.json", "2027-01", {"profile": "large"}),
])
def test_committed_fixtures_are_reproducible(name, month, kwargs):
    assert (FIXTURES / name).read_text(encoding="utf-8") == dumps(generate(month, 1, **kwargs))


@pytest.mark.parametrize("month,seed", [("2027-01", 1), ("2027-01", 2), ("2026-12", 3), ("2027-04", 4),
                                        ("2027-08", 5), ("2028-02", 6)])
def test_demo_months_are_coverable(month, seed):
    report = check_feasibility(generate(month, seed), month)
    assert report["problems"] == []
    assert 0.80 <= report["ratio"] <= 0.85


@pytest.mark.parametrize("month", ["2027-01", "2027-03", "2027-10"])
def test_small_dataset_is_coverable(month):
    report = check_feasibility(generate(month, 1, **SMALL), month)
    assert report["problems"] == []
    assert 0.75 <= report["ratio"] <= 0.9


def test_large_dataset_is_coverable(large):
    report = check_feasibility(large, "2027-01")
    assert report["problems"] == []
    assert 0.80 <= report["ratio"] <= 0.85


def test_demand_factor_makes_month_impossible():
    report = check_feasibility(generate("2027-01", 1, demand_factor=3), "2027-01")
    assert report["ratio"] > 2
    assert any("capacity" in p for p in report["problems"])


def test_feasibility_detects_uncovered_window(demo):
    broken = json.loads(json.dumps(demo))
    broken["demand"].append({"areaId": "TV", "days": ["MON"], "date": None, "from": "23:00", "to": "02:00",
                             "min": 1, "max": None})
    assert any("not covered" in p for p in check_feasibility(broken, "2027-01")["problems"])


def test_easter():
    assert easter_sunday(2027) == date(2027, 3, 28)
    assert easter_sunday(2026) == date(2026, 4, 5)


def test_shape_and_formats(demo):
    assert demo["format"] == "dispofold/dataset" and demo["version"] == 2
    assert [a["id"] for a in demo["areas"]] == ["AN", "DR", "TV", "LF", "TS"]
    ids = [s["id"] for s in demo["shifts"]]
    assert len(ids) == len(set(ids))
    for s in demo["shifts"]:
        assert TIME.match(s["start"]) and TIME.match(s["end"])
        start, end = interval(s["start"], s["end"])
        assert end - start <= 12 * 60
        assert s["category"] in {"EARLY", "DAY", "LATE", "NIGHT"}
        assert s["unpopular"] == (s["category"] == "NIGHT")
    for r in demo["demand"]:
        assert TIME.match(r["from"]) and TIME.match(r["to"])
        assert set(r["days"]) <= {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL"}
    assert len(demo["people"]) == 150
    assert len({p["shortName"] for p in demo["people"]}) == 150
    assert 200 <= len(demo["restrictions"]) <= 500
    assert {h["date"][:4] for h in demo["holidays"]} == {"2026", "2027"}
    assert sorted({h["month"] for h in demo["history"]}) == [f"2026-{m:02d}" for m in range(1, 13)]
    assert all(t["reducesTarget"] for t in demo["absenceTypes"])
    assert [t["code"] for t in demo["absenceTypes"] if t["chargesAccount"]] == ["ZA"]
    assert [t["code"] for t in demo["absenceTypes"] if t["compensatoryRest"]] == ["ER"]
    assert list(demo["people"][0])[-2:] == ["entryDate", "exitDate"]
    text = dumps(demo).lower()
    assert not re.search("clau" + "de|anthro" + "pic|op" + "enai|chat" + "gpt|l" + "lm", text)


def test_lead_exactly_one_around_the_clock(demo):
    lead = [r for r in demo["demand"] if r["areaId"] == "LF"]
    assert all(r["min"] == 1 and r["max"] == 1 for r in lead)
    for day in (date(2027, 1, 4), date(2027, 1, 9)):
        covered = sum(-(interval(r["from"], r["to"])[0] - interval(r["from"], r["to"])[1])
                      for r in rules_for_day(demo, "LF", day, set()))
        assert covered == DAY


@pytest.mark.parametrize("name", ["demo", "large"])
def test_hard_inputs_are_consistent(name, request):
    demo = request.getfixturevalue(name)
    period = demo["periods"][0]
    people = {p["id"]: p for p in demo["people"]}
    shifts = {s["id"]: s for s in demo["shifts"]}
    absent = {(a["personId"], a["date"]) for a in period["absences"]}
    never = defaultdict(list)
    for r in demo["restrictions"]:
        if r["kind"] == "NEVER":
            never[r["personId"]].append(r)
    karenz = {pid for pid in people if sum(1 for a in period["absences"]
                                           if a["personId"] == pid and a["typeCode"] == "KA") == 31}
    assert karenz

    def allowed(pid, sid, day):
        weekday = date.fromisoformat(day).isoweekday()
        for r in never[pid]:
            concerns = r["shiftId"] == sid if r["shiftId"] else r["category"] == shifts[sid]["category"]
            valid = (r["validFrom"] or day) <= day <= (r["validTo"] or day)
            if concerns and valid and r["weekday"] in (0, weekday):
                return False
        return shifts[sid]["areaId"] in people[pid]["areaIds"]

    for f in period["fixed"]:
        assert (f["personId"], f["date"]) not in absent and f["personId"] not in karenz
        assert f["date"] >= "2027-01-03"
        assert f["shiftId"] is None or allowed(f["personId"], f["shiftId"], f["date"])
    per_person = defaultdict(list)
    for w in period["wishes"]:
        assert (w["personId"], w["date"]) not in absent
        assert (w["shiftId"] is not None) == (w["kind"] in ("SHIFT", "NOT_SHIFT"))
        per_person[w["personId"]].append(w["date"])
    assert all(len(v) <= 6 and len(v) == len(set(v)) for v in per_person.values())
    assert not karenz & set(per_person)

    duties = defaultdict(list)
    for d in period["previousDuties"]:
        assert "2026-12-25" <= d["date"] <= "2026-12-31"
        assert allowed(d["personId"], d["shiftId"], d["date"])
        duties[d["personId"]].append(d)
    assert not karenz & set(duties)
    for items in duties.values():
        spans = []
        for d in sorted(items, key=lambda x: x["date"]):
            offset = (date.fromisoformat(d["date"]) - date(2026, 12, 25)).days * DAY
            s, e = interval(shifts[d["shiftId"]]["start"], shifts[d["shiftId"]]["end"])
            spans.append((offset + s, offset + e))
        for (_, e1), (s2, _) in zip(spans, spans[1:], strict=False):
            assert s2 - e1 >= 11 * 60
        dates = sorted(date.fromisoformat(d["date"]).toordinal() for d in items)
        run = best = 1
        for a, b in zip(dates, dates[1:], strict=False):
            run = run + 1 if b == a + 1 else 1
            best = max(best, run)
        assert best <= 4


@pytest.mark.parametrize("name", ["demo", "large"])
def test_previous_nights_cover_first_morning(name, request):
    demo = request.getfixturevalue(name)
    shifts = {s["id"]: s for s in demo["shifts"]}
    nights = defaultdict(int)
    for d in demo["periods"][0]["previousDuties"]:
        s = shifts[d["shiftId"]]
        if d["date"] == "2026-12-31" and 0 < minutes(s["end"]) < minutes(s["start"]):
            nights[s["areaId"]] += 1
    holidays = {h["date"] for h in demo["holidays"]}
    for area in nights.keys() | {"LF", "DR", "AN"}:
        first = [r for r in rules_for_day(demo, area, date(2027, 1, 1), holidays) if r["from"] == "00:00"]
        assert first, area
        assert nights[area] >= first[0]["min"], area


@pytest.mark.parametrize("seed", [7, 23])
def test_sweep_over_a_year(seed):
    from demodata.timeutil import add_months

    for k in range(12):
        month = add_months("2026-12", k)
        report = check_feasibility(generate(month, seed), month)
        assert report["problems"] == [], (month, seed)
        assert 0.80 <= report["ratio"] <= 0.85, (month, seed)


@pytest.mark.parametrize("name", ["demo", "large"])
def test_restrictions(name, request):
    data = request.getfixturevalue(name)
    shift_ids = {s["id"] for s in data["shifts"]}
    keys = ["personId", "shiftId", "category", "weekday", "kind", "validFrom", "validTo"]
    for r in data["restrictions"]:
        assert list(r) == keys
        assert (r["shiftId"] is None) != (r["category"] is None)
        assert r["shiftId"] is None or r["shiftId"] in shift_ids
        assert r["category"] in (None, "EARLY", "DAY", "LATE", "NIGHT")
        assert 0 <= r["weekday"] <= 7 and r["kind"] in ("NEVER", "ON_REQUEST")
        assert (r["validFrom"] is None) == (r["validTo"] is None)
        assert r["validFrom"] is None or "2027-01-01" <= r["validFrom"] <= r["validTo"] <= "2027-01-31"
    rows = data["restrictions"]
    assert any(r["category"] == "NIGHT" and r["kind"] == "NEVER" and r["weekday"] == 0 and not r["validFrom"]
               for r in rows)
    assert any(r["category"] and r["weekday"] for r in rows)
    assert any(r["validFrom"] for r in rows)


@pytest.mark.parametrize("name", ["demo", "large"])
def test_employment(name, request):
    demo = request.getfixturevalue(name)
    period = demo["periods"][0]
    changing = {p["id"]: p for p in demo["people"] if p["entryDate"] or p["exitDate"]}
    assert len(changing) == 3
    assert all(p["entryDate"] is None or p["exitDate"] is None for p in changing.values())
    for p in changing.values():
        first, last = p["entryDate"] or "2027-01-01", p["exitDate"] or "2027-01-31"
        assert "2027-01-02" <= (p["entryDate"] or p["exitDate"]) <= "2027-01-30"
        for key in ("absences", "wishes", "fixed"):
            assert all(first <= x["date"] <= last for x in period[key] if x["personId"] == p["id"])
        if p["entryDate"]:
            assert p["hoursAccount"] == 0 and p["compRestBalance"] == 0
            assert not any(x["personId"] == p["id"] for x in period["previousDuties"] + demo["history"])
    small = generate("2027-01", 1, **SMALL)
    assert sum(1 for p in small["people"] if p["entryDate"] or p["exitDate"]) == 1


@pytest.mark.parametrize("name", ["demo", "large"])
def test_history(name, request):
    data = request.getfixturevalue(name)
    keys = ["personId", "month", "source", "nights", "weekendsWorked", "holidays", "unpopular", "wishes",
            "wishesMet", "hours", "hoursAccountAfter", "compRestBalanceAfter", "compRestUsed", "compRestEarned"]
    rows = defaultdict(list)
    for h in data["history"]:
        assert list(h) == keys and h["source"] == "import"
        rows[h["personId"]].append(h)
    above = 0
    for p in data["people"]:
        own = sorted(rows[p["id"]], key=lambda h: h["month"])
        if p["entryDate"]:
            assert own == []
            continue
        assert len(own) == 12
        assert own[-1]["hoursAccountAfter"] == p["hoursAccount"]
        assert own[-1]["compRestBalanceAfter"] == p["compRestBalance"]
        surplus = []
        for before, after in zip(own, own[1:], strict=False):
            assert after["compRestBalanceAfter"] == (before["compRestBalanceAfter"] + after["compRestEarned"]
                                                     - after["compRestUsed"])
            change = after["hoursAccountAfter"] - before["hoursAccountAfter"]
            if after["hours"]:  # the account moves by worked hours minus the target of the month
                assert 17 * p["weeklyHours"] / 5 <= after["hours"] - change <= 24 * p["weeklyHours"] / 5
                surplus.append(change)
            else:
                assert change == 0
        assert all(h["compRestBalanceAfter"] >= 0 and h["compRestEarned"] == h["holidays"] for h in own)
        average = sum(h["hours"] for h in own) / 12
        if average:
            assert abs(average - p["weeklyHours"] * 4.33) <= 0.1 * p["weeklyHours"] * 4.33
            above += sum(surplus) / len(surplus) > 1
    assert above >= 10


def test_large_profile(large):
    assert len(large["people"]) == 150
    assert 230 <= len(large["shifts"]) <= 270
    assert 1200 <= len(large["restrictions"]) <= 1400
    assert len({s["id"] for s in large["shifts"]}) == len(large["shifts"])
    assert len({s["code"] for s in large["shifts"]}) == len(large["shifts"])
    for s in large["shifts"]:
        start, end = interval(s["start"], s["end"])
        assert end - start <= 12 * 60
        if s["code"][1:] not in ("1", "2", "3", "4"):  # variants: category follows from the times
            expected = ("NIGHT" if end > DAY else "EARLY" if start < 7 * 60 else "DAY" if end <= 19 * 60
                        else "LATE")
            assert s["category"] == expected, s
        assert s["unpopular"] == (s["category"] == "NIGHT")
    assert {r["areaId"] for r in large["demand"]} == {"AN", "DR", "TV", "LF", "TS"}
    assert large["demand"] == generate("2027-01", 1)["demand"]
