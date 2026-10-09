#!/usr/bin/env python3
"""Guard rail: prove a tailored resume says nothing the master resume does not.

This is the only part of the pipeline that exists purely to distrust the model.
A language model asked to "make this resume fit the job" will happily round 30%
up to 40%, promote you a level, or add a framework you have never run. This
script fails the build when that happens.

Checks, all mechanical:
  1. every tailored bullet cites a `from:` id that exists in the master resume
  2. a bullet marked `edit: verbatim` is byte-identical (modulo whitespace)
  3. a bullet marked `edit: reworded` may not introduce a NUMBER, METRIC,
     TECHNOLOGY or EMPLOYER absent from the master resume
  4. every listed skill appears in the master resume's own skills section
  5. employers, titles, dates and education are never taken from the tailored
     file at all -- render_resume.py reads them from the master -- so this
     checks that the tailored file does not even try to set them

Exit status is 1 on any error, so it can gate the render step.

Usage:
  verify_tailored.py applications/4012345678/tailored.yaml --master profile/master_resume.yaml
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A "claim" is something a reader would treat as a verifiable fact. A figure is
# checked as a (quantity, unit) PAIR, because "12 services" in the master must
# not license "12 years" in the tailored copy.
UNIT_WORDS = (r"%|x|k|m|bn|ms|\u00b5s|ns|s|gb|tb|qps|rps|million|billion|thousand|"
              r"years?|yrs?|months?|weeks?|days?|hours?|people|engineers?|reports?|"
              r"nodes?|servers?|services?|teams?|customers?|users?|clients?|"
              r"requests?|queries?|events?|records?|rows?|lines?|repos?|regions?|"
              r"clusters?|scenarios?|releases?|deploys?|points?|hires?")
NUMBER_RE = re.compile(rf"(?P<qty>\d[\d,.]*)\s*(?P<unit>{UNIT_WORDS})?(?!\w)", re.I)

TECHISH_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9+#.]*(?:\.[a-z]{2,})?\b")

UNIT_ALIASES = {"yr": "year", "yrs": "year", "years": "year", "m": "million", "bn": "billion"}


def norm_unit(unit: str | None) -> str:
    """Fold plurals and shorthands so '3 yrs' and '3 years' are the same claim."""
    if not unit:
        return ""
    unit = unit.lower().strip()
    unit = UNIT_ALIASES.get(unit, unit)
    if unit not in {"s", "ms", "ns", "gb", "tb", "qps", "rps", "%", "x", "k"} and unit.endswith("s"):
        unit = UNIT_ALIASES.get(unit[:-1], unit[:-1])
    return unit


def claim_pairs(text: str) -> set[tuple[str, str]]:
    pairs = set()
    for m in NUMBER_RE.finditer(text or ""):
        qty = m.group("qty").rstrip(".").replace(",", "")
        if not qty:
            continue
        pairs.add((qty, norm_unit(m.group("unit"))))
    return pairs


FORBIDDEN_TAILORED_KEYS = {
    "company", "title", "start", "end", "location", "dates", "employer",
    "school", "degree", "education", "basics", "name", "email", "phone",
}

# Words that look like claims but are ordinary English.
GENERIC = {
    "a", "an", "and", "the", "to", "of", "in", "on", "for", "with", "by", "at", "as", "from",
    "built", "led", "owned", "drove", "ran", "shipped", "delivered", "designed", "developed",
    "implemented", "improved", "reduced", "increased", "cut", "raised", "created", "wrote",
    "maintained", "scaled", "migrated", "refactored", "automated", "introduced", "replaced",
    "across", "into", "through", "using", "while", "after", "before", "that", "which", "whose",
    "team", "teams", "service", "services", "system", "systems", "code", "codebase", "release",
    "releases", "production", "latency", "throughput", "traffic", "load", "test", "tests",
    "testing", "review", "reviews", "work", "working", "new", "first", "every", "all", "more",
    "less", "per", "it", "its", "their", "our", "my", "me", "i", "was", "were", "is", "are",
    "has", "had", "have", "been", "than", "then", "when", "where", "why", "how", "so", "up",
    "down", "out", "over", "under", "between", "without", "within", "end", "day", "daily",
    "weekly", "monthly", "quarterly", "now", "still", "plus", "also", "both", "each", "one",
    "two", "three", "engineer", "engineers", "engineering", "software", "senior", "staff",
}


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")


def norm_ws(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip())


def norm_token(token: str) -> str:
    return re.sub(r"[^a-z0-9+#.]", "", token.lower()).rstrip(".")


def index_master(master: dict) -> dict:
    bullets: dict[str, str] = {}
    parents: dict[str, str] = {}
    corpus_parts: list[str] = []
    skills: set[str] = set()

    for variant in master.get("summary_variants") or []:
        bullets[variant.get("id", "")] = variant.get("text", "")
        corpus_parts.append(variant.get("text", ""))
    for group in master.get("skills") or []:
        for item in group.get("items") or []:
            skills.add(norm_token(str(item)))
            corpus_parts.append(str(item))
        corpus_parts.append(group.get("group", ""))
    for kind, key in (("experience", "experience"), ("project", "projects")):
        for entry in master.get(key) or []:
            parents[entry.get("id", "")] = kind
            corpus_parts += [str(entry.get(k, "")) for k in ("company", "title", "name", "location")]
            for bullet in entry.get("bullets") or []:
                bullets[bullet.get("id", "")] = bullet.get("text", "")
                corpus_parts.append(bullet.get("text", ""))
                corpus_parts += [str(t) for t in bullet.get("tags") or []]
    for edu in master.get("education") or []:
        corpus_parts += [str(edu.get(k, "")) for k in ("school", "degree", "location")]
    corpus_parts += [str(c) for c in master.get("certifications") or []]

    corpus_tokens = {norm_token(t) for t in TECHISH_RE.findall(" ".join(corpus_parts))}
    corpus_tokens.discard("")
    pairs = claim_pairs(" ".join(corpus_parts))

    return {
        "bullets": {k: v for k, v in bullets.items() if k},
        "parents": {k: v for k, v in parents.items() if k},
        "skills": skills,
        "tokens": corpus_tokens,
        "pairs": pairs,
        "quantities": {qty for qty, _ in pairs},
        "text": norm_ws(" ".join(corpus_parts)).lower(),
    }


SUFFIXES = ("s", "es", "ed", "ing", "ly", "er", "ers", "ion", "ions", "ment", "d")


def inflections(word: str) -> set[str]:
    """Ordinary morphological variants, so 'owning' matches a master that says 'owns'.

    str.rstrip takes a character SET, not a suffix, so rstrip('ing') turns
    'owning' into 'ow' -- hence the explicit suffix handling here.
    """
    out = {word}
    for suffix in SUFFIXES:
        if word.endswith(suffix) and len(word) - len(suffix) >= 3:
            base = word[: -len(suffix)]
            out |= {base, base + "e"}
            out |= {base + s for s in ("s", "es", "ed", "ing")}
            out |= {base + "e" + s for s in ("s", "d", "ing")}
    out |= {word + s for s in ("s", "es", "ed", "ing")}
    return out


def check_claims(text: str, idx: dict, allowlist: set[str], where: str, rep: Report) -> None:
    """Every number and every technology-looking token must exist in the master."""
    for qty, unit in claim_pairs(text):
        if (qty, unit) in idx["pairs"]:
            continue
        if not unit and qty in idx["quantities"]:
            continue  # bare number, and the master uses that quantity somewhere
        shown = f"{qty} {unit}".strip()
        if unit and qty in idx["quantities"]:
            rep.error(where, f"re-uses the quantity {qty!r} as {shown!r} — the master resume "
                             f"never pairs it with {unit!r}")
        else:
            rep.error(where, f"introduces the figure {shown!r}, which appears nowhere "
                             "in the master resume")

    for token in TECHISH_RE.findall(text):
        norm = norm_token(token)
        if not norm or norm in GENERIC or norm in allowlist or norm in idx["tokens"]:
            continue
        if norm.isdigit() or len(norm) < 2:
            continue
        if inflections(norm) & idx["tokens"]:
            continue  # ordinary inflection of a word the master does use
        # Capitalised or punctuated tokens read as proper nouns / tech names: hard error.
        if token[:1].isupper() or any(c in token for c in "+#.") or token.isupper():
            rep.error(where, f"introduces {token!r}, which appears nowhere in the master resume")
        else:
            rep.warn(where, f"uses the word {token!r}, which is not in the master resume")


def verify(tailored: dict, master: dict, allowlist: set[str]) -> Report:
    rep = Report()
    idx = index_master(master)

    stray = FORBIDDEN_TAILORED_KEYS & set(tailored)
    if stray:
        rep.error("tailored", f"must not set {sorted(stray)} — those come from the master resume")

    if not tailored.get("job_id"):
        rep.warn("tailored", "no job_id — the application folder will be hard to trace")

    summary = tailored.get("summary")
    if isinstance(summary, dict) and norm_ws(summary.get("text", "")):
        src = summary.get("from")
        if src and src not in idx["bullets"]:
            rep.error("summary", f"cites from: {src!r}, which is not a master summary_variants id")
        check_claims(summary["text"], idx, allowlist, "summary", rep)
        if len(summary["text"].split()) > 60:
            rep.warn("summary", f"{len(summary['text'].split())} words — over 60 reads as a paragraph, not a summary")

    for skill in tailored.get("skills") or []:
        if norm_token(str(skill)) not in idx["skills"]:
            rep.error("skills", f"lists {skill!r}, which is not in the master resume's skills section")

    seen_bullets: set[str] = set()
    for key in ("experience", "projects"):
        for i, entry in enumerate(tailored.get(key) or []):
            where_entry = f"{key}[{i}]"
            src = entry.get("from")
            if not src:
                rep.error(where_entry, "has no `from:` pointing at a master entry id")
                continue
            if src not in idx["parents"]:
                rep.error(where_entry, f"cites from: {src!r}, which is not a master {key} id")
                continue
            stray_entry = FORBIDDEN_TAILORED_KEYS & set(entry)
            if stray_entry:
                rep.error(f"{where_entry} ({src})", f"must not set {sorted(stray_entry)}")

            for j, bullet in enumerate(entry.get("bullets") or []):
                where = f"{where_entry}.bullets[{j}]"
                bsrc = bullet.get("from")
                text = norm_ws(bullet.get("text", ""))
                edit = (bullet.get("edit") or "").lower()

                if not bsrc:
                    rep.error(where, "has no `from:` — every bullet must cite a master bullet id")
                    continue
                if bsrc not in idx["bullets"]:
                    rep.error(where, f"cites from: {bsrc!r}, which is not a master bullet id")
                    continue
                if bsrc in seen_bullets:
                    rep.warn(where, f"reuses master bullet {bsrc} that is already on the resume")
                seen_bullets.add(bsrc)

                master_text = norm_ws(idx["bullets"][bsrc])
                if not text:
                    rep.error(where, f"({bsrc}) has empty text")
                    continue
                if edit not in ("verbatim", "reworded"):
                    rep.error(where, f"({bsrc}) edit must be 'verbatim' or 'reworded', got {edit!r}")
                elif edit == "verbatim" and text != master_text:
                    rep.error(where, f"({bsrc}) marked verbatim but differs from the master text")
                elif edit == "reworded":
                    if text == master_text:
                        rep.warn(where, f"({bsrc}) marked reworded but is unchanged")
                    check_claims(text, idx, allowlist, f"{where} ({bsrc})", rep)
                    if len(text.split()) > len(master_text.split()) * 2 + 6:
                        rep.warn(where, f"({bsrc}) is {len(text.split())} words vs {len(master_text.split())} "
                                        "in the master — padding usually means invention")

    if not seen_bullets:
        rep.error("tailored", "selects no bullets at all")
    return rep


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tailored", type=Path)
    ap.add_argument("--master", type=Path, default=Path("profile/master_resume.yaml"))
    ap.add_argument("--allow", nargs="*", default=[], help="tokens that are acceptable to introduce")
    ap.add_argument("--warnings-as-errors", action="store_true")
    args = ap.parse_args()

    import yaml

    tailored = yaml.safe_load(args.tailored.read_text(encoding="utf-8")) or {}
    master = yaml.safe_load(args.master.read_text(encoding="utf-8")) or {}
    if "needs_review" in master:
        raise SystemExit(f"{args.master} still carries `needs_review` — confirm it first")

    allowlist = {norm_token(a) for a in args.allow} | {
        norm_token(a) for a in (master.get("token_allowlist") or [])
    }
    rep = verify(tailored, master, allowlist)

    for warning in rep.warnings:
        print(f"WARN  {warning}")
    for error in rep.errors:
        print(f"ERROR {error}")

    failed = bool(rep.errors) or (args.warnings_as_errors and rep.warnings)
    print(f"\n{len(rep.errors)} error(s), {len(rep.warnings)} warning(s) — "
          f"{'REJECTED' if failed else 'OK to render'}")
    if rep.errors:
        print("\nFix by either (a) rewording the tailored bullet back to what the master supports,\n"
              "or (b) adding the fact to the master resume — if and only if it is true.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
