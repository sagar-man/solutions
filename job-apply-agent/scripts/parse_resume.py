#!/usr/bin/env python3
"""Turn an existing resume PDF into a DRAFT master_resume.yaml.

This is a heuristic extractor, not magic. It gets structure roughly right and
marks everything it is unsure about. You are expected to read the output,
fix it, and delete the `needs_review` block before the agent uses it --
every later step treats the master resume as ground truth, so a parsing
mistake here becomes a lie on your resume.

Usage:
  parse_resume.py ~/Downloads/resume.pdf -o profile/master_resume.draft.yaml
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

BULLET_CHARS = "•·‣▪●○◦-–—*"
BULLET_RE = re.compile(rf"^\s*[{re.escape(BULLET_CHARS)}]\s+(?P<text>\S.*)$")

EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
PHONE_RE = re.compile(r"(?:\+\d{1,3}[\s.-]?)?(?:\(?\d{3}\)?[\s.-]?)\d{3}[\s.-]?\d{4}")
URL_RE = re.compile(r"(?:https?://)?(?:www\.)?((?:linkedin\.com|github\.com|gitlab\.com)/[\w\-./]+)", re.I)

MONTH = r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
DATE_RANGE_RE = re.compile(
    rf"(?P<start>(?:{MONTH}\.?\s+)?\d{{4}}|\d{{1,2}}/\d{{4}})"
    rf"\s*(?:–|—|-|to|until)\s*"
    rf"(?P<end>(?:{MONTH}\.?\s+)?\d{{4}}|\d{{1,2}}/\d{{4}}|[Pp]resent|[Cc]urrent|[Nn]ow)",
    re.I,
)

SECTION_SYNONYMS = {
    "summary": ["summary", "profile", "objective", "about", "professional summary", "career summary"],
    "skills": ["skills", "technical skills", "core competencies", "technologies", "tech stack", "expertise"],
    "experience": ["experience", "work experience", "professional experience", "employment",
                   "employment history", "work history", "career history", "relevant experience"],
    "projects": ["projects", "personal projects", "selected projects", "side projects", "open source"],
    "education": ["education", "academic background", "academics", "qualifications"],
    "certifications": ["certifications", "certificates", "licenses", "licenses & certifications",
                       "courses", "training"],
    "publications": ["publications", "papers", "talks", "patents"],
    "awards": ["awards", "honors", "honours", "achievements"],
}
HEADING_LOOKUP = {alias: canon for canon, aliases in SECTION_SYNONYMS.items() for alias in aliases}


def extract_text(pdf: Path) -> str:
    """pdfminer first (keeps reading order best), pdftotext -layout as fallback."""
    try:
        from pdfminer.high_level import extract_text as pdfminer_extract
        from pdfminer.layout import LAParams

        text = pdfminer_extract(str(pdf), laparams=LAParams(line_margin=0.35, char_margin=2.2))
        if text and text.strip():
            return text
    except Exception as exc:  # noqa: BLE001 - fall through to the CLI tool
        print(f"  pdfminer failed ({type(exc).__name__}: {exc}); trying pdftotext", file=sys.stderr)

    try:
        out = subprocess.run(
            ["pdftotext", "-layout", str(pdf), "-"],
            capture_output=True, text=True, check=True, timeout=120,
        )
        if out.stdout.strip():
            return out.stdout
    except (FileNotFoundError, subprocess.SubprocessError) as exc:
        print(f"  pdftotext failed ({exc})", file=sys.stderr)

    raise SystemExit(
        f"could not extract text from {pdf}. If it is a scan, OCR it first "
        "(e.g. ocrmypdf in.pdf out.pdf) and re-run."
    )


def clean_lines(text: str) -> list[str]:
    lines = []
    for raw in text.splitlines():
        line = raw.replace("\xa0", " ").rstrip()
        line = re.sub(r"[ \t]{2,}", "  ", line)
        if line.strip():
            lines.append(line.strip())
    return lines


def is_heading(line: str) -> str | None:
    stripped = line.strip().strip(":").strip()
    if not stripped or len(stripped) > 45:
        return None
    key = re.sub(r"[^a-z& ]", "", stripped.lower()).strip()
    if key in HEADING_LOOKUP:
        # A heading is short and has no sentence punctuation after it.
        if stripped.isupper() or len(stripped.split()) <= 4:
            return HEADING_LOOKUP[key]
    return None


def split_sections(lines: list[str]) -> tuple[list[str], dict[str, list[str]]]:
    header: list[str] = []
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in lines:
        canon = is_heading(line)
        if canon:
            current = canon
            sections.setdefault(current, [])
            continue
        (sections[current] if current else header).append(line)
    return header, sections


def parse_basics(header: list[str]) -> tuple[dict, list[str]]:
    blob = "\n".join(header[:12])
    basics: dict = {"links": {}}
    notes: list[str] = []

    if m := EMAIL_RE.search(blob):
        basics["email"] = m.group(0)
    if m := PHONE_RE.search(blob):
        basics["phone"] = m.group(0).strip()
    for m in URL_RE.finditer(blob):
        path = m.group(1)
        host = path.split("/")[0].lower()
        key = {"linkedin.com": "linkedin", "github.com": "github", "gitlab.com": "gitlab"}[host]
        basics["links"].setdefault(key, path)

    contact_markers = ("@", "http", "linkedin", "github", "|", "+")
    for line in header[:5]:
        low = line.lower()
        looks_like_contact = any(mark in low for mark in contact_markers) or PHONE_RE.search(line)
        if not looks_like_contact and 2 <= len(line.split()) <= 5:
            basics["name"] = line.strip()
            break
    else:
        notes.append("basics.name: could not identify the name line — set it by hand")

    # Location: a City, ST / City, Country fragment in the header.
    for line in header[:6]:
        for chunk in re.split(r"\s*[|•·]\s*", line):
            if re.fullmatch(r"[A-Z][\w.' -]+,\s*[A-Z][\w.' -]{1,20}", chunk.strip()):
                basics.setdefault("location", chunk.strip())
                break

    for field in ("name", "email", "phone", "location"):
        basics.setdefault(field, "")
        if not basics[field]:
            notes.append(f"basics.{field}: empty — fill it in")
    return basics, notes


def parse_skills(lines: list[str]) -> list[dict]:
    groups: list[dict] = []
    loose: list[str] = []
    for line in lines:
        body = BULLET_RE.sub(lambda m: m.group("text"), line)
        if ":" in body:
            label, _, rest = body.partition(":")
            items = [s.strip() for s in re.split(r"[,;|/]| {2,}", rest) if s.strip()]
            if items and len(label.split()) <= 5:
                groups.append({"group": label.strip(), "items": items})
                continue
        loose += [s.strip() for s in re.split(r"[,;|•·]| {2,}", body) if s.strip()]
    if loose:
        groups.append({"group": "Skills", "items": loose})
    return groups


def _slug(text: str, fallback: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:24]
    return slug or fallback


TITLE_WORDS = re.compile(
    r"\b(engineer|developer|programmer|architect|manager|lead|director|analyst|scientist|"
    r"designer|consultant|administrator|specialist|intern|founder|principal|staff|senior|"
    r"junior|head|officer|president|associate|technician|researcher|sre|devops)\b", re.I)
LOCATION_RE = re.compile(r"^(?:Remote|Hybrid|On-?site)$|^[A-Z][\w.' -]+,\s*[A-Z][\w.' -]{1,20}$", re.I)
SEPARATOR_RE = re.compile(r"\s*(?:\u2014|\u2013|\||\u00b7|\u2022|\bat\b)\s*|\s{2,}")


def classify(lines: list[str]) -> list[tuple[str, str]]:
    """B = bullet, D = line carrying a date range, T = other text."""
    tagged = []
    for line in lines:
        if BULLET_RE.match(line):
            tagged.append(("B", line))
        elif DATE_RANGE_RE.search(line):
            tagged.append(("D", line))
        else:
            tagged.append(("T", line))
    return tagged


def segment(tagged: list[tuple[str, str]], max_header_lines: int = 2) -> list[dict]:
    """Split a section into entries anchored on its date lines.

    A header is the date line plus up to `max_header_lines` plain-text lines
    immediately above it -- the lookahead that stops a role line from being
    swallowed as a continuation of the bullet above it.
    """
    date_idx = [i for i, (tag, _) in enumerate(tagged) if tag == "D"]
    if not date_idx:
        return []

    records = []
    for n, d in enumerate(date_idx):
        prev_d = date_idx[n - 1] if n else -1
        start = d
        while start - 1 > prev_d and tagged[start - 1][0] == "T" and d - start < max_header_lines:
            start -= 1

        hard_end = date_idx[n + 1] if n + 1 < len(date_idx) else len(tagged)
        body_end = hard_end
        while (body_end - 1 > d and tagged[body_end - 1][0] == "T"
               and hard_end - body_end < max_header_lines):
            body_end -= 1

        records.append({
            "header": [line for _, line in tagged[start:d]],
            "date_line": tagged[d][1],
            "body": tagged[d + 1:body_end],
        })
    return records


def parse_header(header: list[str], date_line: str) -> dict:
    """Pull title / company / location out of a header block."""
    date_m = DATE_RANGE_RE.search(date_line)
    blob = " | ".join(header + [DATE_RANGE_RE.sub("", date_line)])

    chunks = [c.strip(" ,|\u00b7\u2022-\u2013\u2014\t") for c in SEPARATOR_RE.split(blob)]
    chunks = [c for c in chunks if c and c.lower() not in {"present", "current", "now"}]

    location = next((c for c in chunks if LOCATION_RE.fullmatch(c)), "")
    fields = [c for c in chunks if c != location]

    # Commas only get split when the obvious separators gave us nothing to work with.
    if len(fields) == 1 and "," in fields[0]:
        fields = [f.strip() for f in fields[0].split(",") if f.strip()]

    title = fields[0] if fields else ""
    company = fields[1] if len(fields) > 1 else ""
    # Templates disagree about the order; the title is the side with a role word.
    if company and TITLE_WORDS.search(company) and not TITLE_WORDS.search(title):
        title, company = company, title

    return {
        "company": company,
        "title": title,
        "location": location,
        "start": date_m.group("start") if date_m else "",
        "end": date_m.group("end") if date_m else "",
        "_fields": fields,
    }


def collect_bullets(body: list[tuple[str, str]]) -> list[str]:
    """Bullet markers start a bullet; plain lines continue the one above."""
    bullets: list[str] = []
    for tag, line in body:
        if tag == "B":
            bullets.append(BULLET_RE.match(line).group("text").strip())
        elif bullets and (line[:1].islower() or not re.match(r"^[A-Z][a-z]+ [A-Z]", line)):
            bullets[-1] = (bullets[-1] + " " + line).strip()
        elif len(line.split()) > 5:
            bullets.append(line.strip())  # prose entry with no bullet marker
    return bullets


def parse_experience(lines: list[str], kind: str = "exp") -> tuple[list[dict], list[str]]:
    entries: list[dict] = []
    notes: list[str] = []

    for record in segment(classify(lines)):
        head = parse_header(record["header"], record["date_line"])
        ident = f"{kind}-{_slug(head['company'] or head['title'], str(len(entries) + 1))}"
        bullets = collect_bullets(record["body"])

        entries.append({
            "id": ident,
            "company": head["company"],
            "title": head["title"],
            "location": head["location"],
            "start": head["start"],
            "end": head["end"],
            "bullets": [{"id": f"{ident}-b{i}", "text": text, "tags": []}
                        for i, text in enumerate(bullets, start=1)],
        })

        if not head["company"] or not head["title"]:
            notes.append(f"{ident}: could not split title/company from "
                         f"{head['_fields']!r} — verify")
        if not bullets:
            notes.append(f"{ident}: no bullets found — check the PDF")

    return entries, notes


SCHOOL_RE = re.compile(
    r"\b(universit|college|institute|school|academy|polytechnic|conservator|seminary)", re.I)
DEGREE_RE = re.compile(
    r"\b(b\.?e|b\.?s|b\.?sc|b\.?a|b\.?tech|b\.?com|m\.?e|m\.?s|m\.?sc|m\.?a|m\.?tech|"
    r"mba|ph\.?d|bachelor|master|doctor|diploma|associate|certificate|foundation)\b", re.I)
URLISH_RE = re.compile(r"(?:https?://|www\.)|\b[\w-]+\.(?:com|org|net|io|dev|ai|co|me)\b", re.I)


def parse_education(lines: list[str]) -> list[dict]:
    """Group lines into entries at each year/date line, then label the chunks.

    Templates put degree and school in either order, so each chunk is labelled
    by what it looks like rather than by position.
    """
    entries: list[dict] = []
    buffer: list[str] = []

    def flush() -> None:
        if not buffer:
            return
        blob = " | ".join(buffer)
        date_m = DATE_RANGE_RE.search(blob)
        years = re.findall(r"\b(?:19|20)\d{2}\b", blob)

        # Split on explicit separators first so a "City, Region" stays whole,
        # then split the survivors on commas.
        outer = [c.strip(" |\u00b7-\u2013\u2014")
                 for c in re.split(r"\s*[|\u00b7\u2022]\s*|\s{2,}", DATE_RANGE_RE.sub("", blob))]
        outer = [c for c in outer if c]
        location = next((c for c in outer if LOCATION_RE.fullmatch(c)), "")

        chunks = []
        for chunk in outer:
            if chunk == location:
                continue
            chunks += [c.strip() for c in chunk.split(",") if c.strip()]
        chunks = [c for c in chunks if not re.fullmatch(r"(?:19|20)\d{2}", c)]

        school = next((c for c in chunks if SCHOOL_RE.search(c)), "")
        degree_parts = [c for c in chunks if c != school and DEGREE_RE.search(c)]
        leftovers = [c for c in chunks if c not in (school, *degree_parts)]

        # "BE, Computer Engineering" arrives as two chunks: the degree token and its field.
        degree = ", ".join(degree_parts + [l for l in leftovers if l and not LOCATION_RE.fullmatch(l)])
        if not school and leftovers:
            school, degree = leftovers[0], ", ".join(degree_parts + leftovers[1:])

        entries.append({
            "id": f"edu-{len(entries) + 1}",
            "school": school,
            "degree": degree.strip(", "),
            "location": location,
            "start": date_m.group("start") if date_m else (years[0] if years else ""),
            "end": date_m.group("end") if date_m else (years[-1] if len(years) > 1 else ""),
        })
        buffer.clear()

    for line in lines:
        buffer.append(line)
        if DATE_RANGE_RE.search(line) or re.search(r"\b(?:19|20)\d{2}\b", line):
            flush()
    flush()
    return entries


def parse_projects(lines: list[str]) -> tuple[list[dict], list[str]]:
    """Projects rarely carry dates, so fall back to 'short title line, then bullets'."""
    dated, notes = parse_experience(lines, "proj")
    if dated:
        return ([{"id": e["id"], "name": e["title"] or e["company"],
                  "url": "", "bullets": e["bullets"]} for e in dated], notes)

    projects: list[dict] = []
    current: dict | None = None

    def new_project(name: str) -> dict:
        ident = f"proj-{_slug(name, str(len(projects) + 1))}"
        return {"id": ident, "name": name, "url": "", "bullets": []}

    for line in lines:
        if bullet_m := BULLET_RE.match(line):
            text = bullet_m.group("text").strip()
            if current is None:
                current = new_project("")
                projects.append(current)
            current["bullets"].append(text)
            continue
        if URLISH_RE.search(line) and len(line.split()) <= 3:
            if current is not None and not current["url"]:
                current["url"] = line.strip()
                continue
        # A short line with no sentence punctuation is a project title.
        if len(line.split()) <= 8 and not line.rstrip().endswith((".", ";", ":")):
            current = new_project(line.strip())
            projects.append(current)
            continue
        if current is None:
            current = new_project("")
            projects.append(current)
        if current["bullets"]:
            current["bullets"][-1] = (current["bullets"][-1] + " " + line).strip()
        else:
            current["bullets"].append(line.strip())

    out = []
    for project in projects:
        if not project["name"] and not project["bullets"]:
            continue
        out.append({
            "id": project["id"], "name": project["name"], "url": project["url"],
            "bullets": [{"id": f"{project['id']}-b{i}", "text": t, "tags": []}
                        for i, t in enumerate(project["bullets"], start=1)],
        })
        if not project["name"]:
            notes.append(f"{project['id']}: no project name identified — add one")
    return out, notes


def build_yaml(basics, sections) -> tuple[dict, list[str]]:
    notes: list[str] = []
    doc: dict = {"basics": basics}

    if summary := sections.get("summary"):
        doc["summary_variants"] = [{
            "id": "sum-original",
            "angle": [],
            "text": " ".join(BULLET_RE.sub(lambda m: m.group("text"), s) for s in summary).strip(),
        }]
    else:
        doc["summary_variants"] = [{"id": "sum-original", "angle": [], "text": ""}]
        notes.append("summary_variants: no summary section found — write one, or let the agent draft it")

    doc["skills"] = parse_skills(sections.get("skills", []))
    if not doc["skills"]:
        notes.append("skills: nothing parsed — add your skills by hand (the gap report leans on this)")

    experience, exp_notes = parse_experience(sections.get("experience", []), "exp")
    doc["experience"] = experience
    notes += exp_notes
    if not experience:
        notes.append("experience: no dated entries found — the section heading may be non-standard")

    projects, proj_notes = parse_projects(sections.get("projects", []))
    doc["projects"] = projects
    notes += proj_notes

    doc["education"] = parse_education(sections.get("education", []))
    doc["certifications"] = [
        BULLET_RE.sub(lambda m: m.group("text"), s) for s in sections.get("certifications", [])
    ]

    total_bullets = sum(len(e["bullets"]) for e in doc["experience"])
    if total_bullets < 3:
        notes.append(f"only {total_bullets} experience bullets extracted — likely a layout the "
                     "heuristics missed; check job_description-style two-column PDFs by hand")
    return doc, notes


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pdf", type=Path)
    ap.add_argument("-o", "--out", type=Path, default=Path("profile/master_resume.draft.yaml"))
    ap.add_argument("--dump-text", type=Path, help="also write the raw extracted text here")
    args = ap.parse_args()

    if not args.pdf.exists():
        raise SystemExit(f"no such file: {args.pdf}")

    import yaml

    text = extract_text(args.pdf)
    if args.dump_text:
        args.dump_text.write_text(text, encoding="utf-8")

    lines = clean_lines(text)
    header, sections = split_sections(lines)
    basics, basics_notes = parse_basics(header)
    doc, notes = build_yaml(basics, sections)
    notes = basics_notes + notes

    doc["needs_review"] = notes or ["nothing flagged — still read it through before trusting it"]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    body = yaml.safe_dump(doc, sort_keys=False, allow_unicode=True, width=100)
    args.out.write_text(
        "# DRAFT extracted from " + args.pdf.name + " — verify every line, then\n"
        "# delete the `needs_review` key and save as profile/master_resume.yaml.\n"
        "# Later steps treat this file as the truth, so errors here become errors on your resume.\n\n"
        + body,
        encoding="utf-8",
    )

    print(f"sections found : {', '.join(sorted(sections)) or 'none'}")
    print(f"experience     : {len(doc['experience'])} entries, "
          f"{sum(len(e['bullets']) for e in doc['experience'])} bullets")
    print(f"skill groups   : {len(doc['skills'])}")
    print(f"written        : {args.out}")
    print("\nneeds_review:")
    for note in doc["needs_review"]:
        print(f"  - {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
