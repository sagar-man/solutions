#!/usr/bin/env python3
"""End-to-end test of the offline half: render -> parse -> analyse -> verify.

The resume is rendered from known data, parsed back out of the PDF, and the
result compared to what went in. That round trip is what catches the two
classes of bug this pipeline is prone to: glyphs that do not survive text
extraction (an ATS sees mojibake) and layout heuristics that mislabel fields.

Run:  python3 tests/test_pipeline.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import jd_analyze as JD          # noqa: E402
import parse_resume as PR        # noqa: E402
import render_resume as RR       # noqa: E402
import verify_tailored as VT     # noqa: E402

import yaml                      # noqa: E402

PASSED, FAILED = [], []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASSED if condition else FAILED).append(name)
    print(f"  {'PASS' if condition else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not condition else ""))


MASTER = yaml.safe_load((ROOT / "profile" / "master_resume.example.yaml").read_text())

FULL_TAILORED = {
    "job_id": "TEST",
    "summary": {"from": "sum-systems", "edit": "verbatim",
                "text": MASTER["summary_variants"][0]["text"].strip()},
    "skills": ["C++17", "C++20", "Python", "Go", "Linux", "gRPC", "Kubernetes", "Terraform"],
    "experience": [
        {"from": entry["id"],
         "bullets": [{"from": b["id"], "edit": "verbatim", "text": b["text"]}
                     for b in entry["bullets"]]}
        for entry in MASTER["experience"]
    ],
    "projects": [
        {"from": p["id"],
         "bullets": [{"from": b["id"], "edit": "verbatim", "text": b["text"]}
                     for b in p["bullets"]]}
        for p in MASTER["projects"]
    ],
}


def test_date_formatting() -> None:
    print("\ndate formatting")
    for raw, expected in [("2022-03", "Mar 2022"), ("2022-3", "Mar 2022"), ("03/2022", "Mar 2022"),
                          ("2017", "2017"), ("present", "Present"), ("Present", "Present"),
                          ("now", "Present"), ("", ""), ("Spring 2020", "Spring 2020"),
                          ("2022-13", "2022-13")]:
        got = RR.fmt_date(raw)
        check(f"fmt_date({raw!r}) == {expected!r}", got == expected, f"got {got!r}")


def test_job_id_extraction() -> None:
    print("\njob id extraction")
    cases = [
        ("4012345678", "4012345678"),
        ("https://www.linkedin.com/jobs/view/4012345678/", "4012345678"),
        ("https://www.linkedin.com/jobs/view/4012345678", "4012345678"),
        ("linkedin.com/jobs/collections/recommended/?currentJobId=4012345678", "4012345678"),
        ("  4012345678\n", "4012345678"),
    ]
    sys.path.insert(0, str(ROOT / "scripts"))
    import fetch_job as FJ
    for raw, expected in cases:
        got = FJ.extract_job_id(raw)
        check(f"extract_job_id({raw[:46]!r})", got == expected, f"got {got!r}")
    try:
        FJ.extract_job_id("https://example.com/careers/engineer")
        check("rejects a non-LinkedIn url", False, "it returned something")
    except SystemExit:
        check("rejects a non-LinkedIn url", True)


def test_render_then_parse_roundtrip(tmp: Path) -> None:
    print("\nrender -> PDF -> parse round trip")
    master_file = tmp / "master.yaml"
    master_file.write_text(yaml.safe_dump(MASTER, sort_keys=False))
    tailored_file = tmp / "full.yaml"
    tailored_file.write_text(yaml.safe_dump(FULL_TAILORED, sort_keys=False))
    pdf = tmp / "resume.pdf"

    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "render_resume.py"), str(tailored_file),
         "--master", str(master_file), "-o", str(pdf)],
        capture_output=True, text=True, cwd=ROOT)
    check("render exits 0", run.returncode == 0, run.stderr[-400:])
    check("pdf exists and is non-trivial", pdf.exists() and pdf.stat().st_size > 2000)
    check("txt twin written", pdf.with_suffix(".txt").exists())

    text = PR.extract_text(pdf)
    check("no unmapped glyphs in extracted text", "(cid:" not in text,
          "bullet glyph does not survive extraction — an ATS would see mojibake")
    for fragment in ["Sagar Manandhar", "Acme Data Systems", "Globex", "Tribhuvan University",
                     "sharded lock-free index", "Protocol Buffers"]:
        check(f"extracted text contains {fragment!r}", fragment in text)

    parsed_file = tmp / "parsed.yaml"
    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "parse_resume.py"), str(pdf), "-o", str(parsed_file)],
        capture_output=True, text=True, cwd=ROOT)
    check("parse exits 0", run.returncode == 0, run.stderr[-400:])
    parsed = yaml.safe_load(parsed_file.read_text())

    check("name recovered", parsed["basics"]["name"] == MASTER["basics"]["name"],
          repr(parsed["basics"].get("name")))
    check("email recovered", parsed["basics"]["email"] == MASTER["basics"]["email"])
    check("phone recovered", parsed["basics"]["phone"] == MASTER["basics"]["phone"])
    check("both employers recovered", len(parsed["experience"]) == 2,
          f"{len(parsed['experience'])} entries")

    by_company = {e["company"]: e for e in parsed["experience"]}
    check("Acme parsed with its title", by_company.get("Acme Data Systems", {}).get("title")
          == "Senior Software Engineer", str(by_company.get("Acme Data Systems", {}).get("title")))
    check("Globex parsed with its title", by_company.get("Globex", {}).get("title")
          == "Software Engineer", str(by_company.get("Globex", {}).get("title")))
    check("Acme dates recovered", by_company.get("Acme Data Systems", {}).get("start") == "Mar 2022"
          and by_company["Acme Data Systems"]["end"] == "Present")
    check("Acme location recovered", by_company.get("Acme Data Systems", {}).get("location") == "Austin, TX")
    check("Globex remote location recovered", by_company.get("Globex", {}).get("location") == "Remote")

    total_in = sum(len(e["bullets"]) for e in MASTER["experience"])
    total_out = sum(len(e["bullets"]) for e in parsed["experience"])
    check(f"all {total_in} experience bullets recovered", total_out == total_in, f"got {total_out}")

    check("project name recovered",
          any(p["name"] == "Thread-safe LRU cache" for p in parsed["projects"]),
          str([p["name"] for p in parsed["projects"]]))
    check("project url recovered",
          any("github.com/sagar-man" in (p["url"] or "") for p in parsed["projects"]),
          str([p["url"] for p in parsed["projects"]]))

    edu = parsed["education"][0] if parsed["education"] else {}
    check("school not confused with degree", edu.get("school") == "Tribhuvan University",
          repr(edu.get("school")))
    check("degree not confused with school", "Computer Engineering" in (edu.get("degree") or ""),
          repr(edu.get("degree")))
    check("education location separated", edu.get("location") == "Kathmandu, Nepal",
          repr(edu.get("location")))


def test_verify_guard(tmp: Path) -> None:
    print("\nfabrication guard")
    master_file = tmp / "master.yaml"
    master_file.write_text(yaml.safe_dump(MASTER, sort_keys=False))

    def errors_for(doc: dict) -> list[str]:
        return VT.verify(doc, MASTER, set()).errors

    check("honest selection passes", errors_for(FULL_TAILORED) == [],
          str(errors_for(FULL_TAILORED))[:300])

    def one(mutate) -> dict:
        doc = json.loads(json.dumps(FULL_TAILORED))
        mutate(doc)
        return doc

    def set_bullet(doc, text, edit="reworded"):
        doc["experience"][0]["bullets"][0].update({"text": text, "edit": edit})

    cases = [
        ("inflated percentage",
         one(lambda d: set_bullet(d, "Cut p99 read latency by 95% in a 40-node C++ storage tier."))),
        ("inflated node count",
         one(lambda d: set_bullet(d, "Cut p99 read latency from 180 ms to 42 ms in a 400-node C++ tier."))),
        ("quantity reused with a different unit",
         one(lambda d: set_bullet(d, "12 years building C++ storage tiers."))),
        ("invented technology in a bullet",
         one(lambda d: set_bullet(d, "Cut p99 read latency from 180 ms to 42 ms using Rust and Kafka."))),
        ("invented skill",
         one(lambda d: d["skills"].append("Kafka"))),
        ("skill not in the master skills section",
         one(lambda d: d["skills"].append("mentorship"))),
        ("employer overridden",
         one(lambda d: d["experience"][0].update({"company": "Acme International"}))),
        ("title overridden",
         one(lambda d: d["experience"][0].update({"title": "Staff Engineer"}))),
        ("dates overridden",
         one(lambda d: d["experience"][0].update({"start": "2019-01"}))),
        ("verbatim bullet silently edited",
         one(lambda d: set_bullet(d, "Cut p99 read latency to 42 ms.", edit="verbatim"))),
        ("unknown bullet id",
         one(lambda d: d["experience"][0]["bullets"].append(
             {"from": "exp-acme-b99", "edit": "verbatim", "text": "Something else entirely."}))),
        ("unknown employer id",
         one(lambda d: d["experience"].append({"from": "exp-initech", "bullets": []}))),
        ("missing from: on a bullet",
         one(lambda d: d["experience"][0]["bullets"].append(
             {"edit": "reworded", "text": "Did a thing."}))),
        ("bad edit mode",
         one(lambda d: d["experience"][0]["bullets"][0].update({"edit": "polished"}))),
        ("empty selection",
         one(lambda d: (d.update({"experience": [], "projects": []})))),
    ]
    for name, doc in cases:
        errs = errors_for(doc)
        check(f"rejects: {name}", bool(errs), "no error raised")

    print("\n  guard tolerances")
    tolerated = [
        ("reworded with an inflection of a master word",
         one(lambda d: set_bullet(d, "Owning p99 latency, cut it from 180 ms to 42 ms in a 40-node C++ tier."))),
        ("reworded reordering master facts",
         one(lambda d: set_bullet(d, "In a 40-node C++ storage tier, replaced a global mutex with a "
                                     "sharded lock-free index, cutting p99 reads from 180 ms to 42 ms."))),
        ("dropping bullets entirely",
         one(lambda d: d["experience"][0].__setitem__("bullets", d["experience"][0]["bullets"][:1]))),
    ]
    for name, doc in tolerated:
        errs = errors_for(doc)
        check(f"allows: {name}", errs == [], str(errs)[:240])


def test_jd_analysis(tmp: Path) -> None:
    print("\njob description analysis")
    jd = """Staff Engineer

Requirements
- Must have deep expertise in modern C++ (C++17 or later)
- Experience with Kubernetes in production is required
- Experience with Terraform or similar tooling

Nice to have
- Rust experience
- Exposure to Kafka stream processing

Benefits
- Unlimited PTO and a Kubernetes-shaped desk ornament
- We are an equal opportunity employer
"""
    appdir = tmp / "4012345678"
    appdir.mkdir(parents=True, exist_ok=True)
    (appdir / "job.json").write_text(json.dumps({
        "job_id": "4012345678", "title": "Staff Engineer", "company": "Initech",
        "url": "https://www.linkedin.com/jobs/view/4012345678/", "description": jd,
    }))
    master_file = tmp / "master.yaml"
    master_file.write_text(yaml.safe_dump(MASTER, sort_keys=False))

    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "jd_analyze.py"), str(appdir),
         "--master", str(master_file)], capture_output=True, text=True, cwd=ROOT)
    check("jd_analyze exits 0", run.returncode == 0, run.stderr[-400:])
    gap = json.loads((appdir / "gap.json").read_text())

    reqs = {r["text"]: r["priority"] for r in gap["requirements"]}
    check("'must have' line is must-have",
          any(p == "must-have" and "modern C++" in t for t, p in reqs.items()), str(reqs))
    check("'is required' line is must-have",
          any(p == "must-have" and "Kubernetes" in t for t, p in reqs.items()), str(reqs))
    check("line under 'Nice to have' inherits that priority",
          reqs.get("Rust experience") == "nice-to-have", str(reqs))
    check("short nice-to-have bullet is not dropped", "Rust experience" in reqs, str(list(reqs)))
    check("benefits lines are excluded",
          not any("PTO" in t for t in reqs), str(list(reqs)))
    check("EEO statement is excluded",
          not any("equal opportunity" in t.lower() for t in reqs), str(list(reqs)))

    check("c++ counted as covered", "c++" in gap["covered_terms"], str(list(gap["covered_terms"])))
    check("kubernetes counted as covered", "kubernetes" in gap["covered_terms"])
    check("rust counted as missing", "rust" in gap["missing_terms"], str(list(gap["missing_terms"])))
    check("kafka counted as missing", "kafka" in gap["missing_terms"])
    # Bullet tags feed both coverage and scoring, so the two must agree.
    scored = {b["id"]: b["matched_terms"] for b in gap["ranked_bullets"]}
    tag_terms = {t for terms in scored.values() for t in terms}
    check("every term credited to a bullet is also counted as covered",
          tag_terms <= set(gap["covered_terms"]),
          str(sorted(tag_terms - set(gap["covered_terms"]))))
    check("most relevant bullet is the C++ latency one",
          gap["ranked_bullets"][0]["id"] in {"exp-acme-b1", "exp-acme-b2"},
          gap["ranked_bullets"][0]["id"])
    check("gap report written", (appdir / "gap_report.md").exists())


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as raw:
        tmp = Path(raw)
        test_date_formatting()
        test_job_id_extraction()
        test_render_then_parse_roundtrip(tmp)
        test_verify_guard(tmp)
        test_jd_analysis(tmp)
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("failed: " + ", ".join(FAILED))
    sys.exit(1 if FAILED else 0)
