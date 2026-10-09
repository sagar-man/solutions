#!/usr/bin/env python3
"""Render a tailored resume to an ATS-safe PDF (plus a .txt twin).

Deliberately plain: one column, one standard font, real text, conventional
section headings, no tables, no graphics, no header/footer. Those are the
things resume parsers mangle, and a posting reached through LinkedIn is
almost always read by a parser before a person.

Employers, titles, dates and education are read from the MASTER resume, never
from the tailored file, so a tailoring pass cannot alter them.

Usage:
  render_resume.py applications/4012345678/tailored.yaml \
      --master profile/master_resume.yaml \
      -o applications/4012345678/resume.pdf
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def fmt_date(value) -> str:
    """2022-03 -> Mar 2022; 2022 -> 2022; 'present' -> Present; anything else passes through."""
    text = str(value or "").strip()
    if not text:
        return ""
    if text.lower() in {"present", "current", "now"}:
        return "Present"
    if m := re.fullmatch(r"(\d{4})-(\d{1,2})", text):
        year, month = m.group(1), int(m.group(2))
        if 1 <= month <= 12:
            return f"{MONTHS[month - 1]} {year}"
    if m := re.fullmatch(r"(\d{1,2})/(\d{4})", text):
        month, year = int(m.group(1)), m.group(2)
        if 1 <= month <= 12:
            return f"{MONTHS[month - 1]} {year}"
    return text


def esc(text) -> str:
    return (str(text or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def build_styles(font: str, size: int):
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.styles import ParagraphStyle

    bold = f"{font}-Bold"
    return {
        "name": ParagraphStyle("name", fontName=bold, fontSize=size + 7, leading=size + 9,
                               spaceAfter=2, alignment=TA_LEFT),
        "contact": ParagraphStyle("contact", fontName=font, fontSize=size - 0.5, leading=size + 2,
                                  spaceAfter=8),
        "section": ParagraphStyle("section", fontName=bold, fontSize=size + 0.5, leading=size + 3,
                                  spaceBefore=9, spaceAfter=3),
        "role": ParagraphStyle("role", fontName=bold, fontSize=size, leading=size + 2, spaceBefore=4),
        "meta": ParagraphStyle("meta", fontName=font, fontSize=size - 1, leading=size + 1,
                               spaceAfter=2, textColor="#333333"),
        "body": ParagraphStyle("body", fontName=font, fontSize=size, leading=size + 3, spaceAfter=2),
        "bullet": ParagraphStyle("bullet", fontName=font, fontSize=size, leading=size + 3,
                                 leftIndent=10, bulletIndent=0, spaceAfter=1.5),
    }


def index_master(master: dict) -> dict:
    out = {"entries": {}, "bullets": {}, "summaries": {}}
    for key in ("experience", "projects"):
        for entry in master.get(key) or []:
            out["entries"][entry.get("id", "")] = {**entry, "_kind": key}
            for bullet in entry.get("bullets") or []:
                out["bullets"][bullet.get("id", "")] = bullet.get("text", "")
    for variant in master.get("summary_variants") or []:
        out["summaries"][variant.get("id", "")] = variant.get("text", "")
    return out


def contact_line(basics: dict) -> str:
    bits = [basics.get("location"), basics.get("phone"), basics.get("email")]
    bits += [v for v in (basics.get("links") or {}).values()]
    return "  ·  ".join(esc(b) for b in bits if str(b or "").strip())


def flowables(tailored: dict, master: dict, styles, section_rule, glyph: str = "-"):
    from reportlab.platypus import Paragraph, Spacer

    idx = index_master(master)
    basics = master.get("basics") or {}
    story = []
    plain: list[str] = []

    story.append(Paragraph(esc(basics.get("name", "")), styles["name"]))
    plain.append(str(basics.get("name", "")))
    story.append(Paragraph(contact_line(basics), styles["contact"]))
    plain.append(re.sub(r"\s*·\s*", " | ", contact_line(basics).replace("&amp;", "&")))

    def section(title: str):
        story.append(Paragraph(title, styles["section"]))
        story.append(section_rule())
        plain.extend(["", title.upper(), "-" * len(title)])

    summary = tailored.get("summary") or {}
    text = (summary.get("text") or "").strip() if isinstance(summary, dict) else str(summary).strip()
    if text:
        section("SUMMARY")
        story.append(Paragraph(esc(text), styles["body"]))
        plain.append(text)

    skills = tailored.get("skills") or []
    if skills:
        section("SKILLS")
        story.append(Paragraph(esc("  ·  ".join(str(s) for s in skills)), styles["body"]))
        plain.append(", ".join(str(s) for s in skills))

    for key, heading in (("experience", "EXPERIENCE"), ("projects", "PROJECTS")):
        entries = tailored.get(key) or []
        if not entries:
            continue
        section(heading)
        for sel in entries:
            src = idx["entries"].get(sel.get("from"))
            if not src:
                print(f"  skipping unknown {key} id {sel.get('from')!r}", file=sys.stderr)
                continue
            if key == "experience":
                head = f"{esc(src.get('title',''))} — {esc(src.get('company',''))}"
                dates = " – ".join(d for d in (fmt_date(src.get("start")), fmt_date(src.get("end"))) if d)
                meta = "  ·  ".join(x for x in (esc(src.get("location", "")), esc(dates)) if x)
                plain.append(f"\n{src.get('title','')} — {src.get('company','')}"
                             f"  ({src.get('location','')}{', ' if src.get('location') else ''}{dates})")
            else:
                head = esc(src.get("name", ""))
                meta = esc(src.get("url", ""))
                plain.append(f"\n{src.get('name','')}  {src.get('url','')}".rstrip())
            story.append(Paragraph(head, styles["role"]))
            if meta:
                story.append(Paragraph(meta, styles["meta"]))
            for bullet in sel.get("bullets") or []:
                btext = (bullet.get("text") or idx["bullets"].get(bullet.get("from"), "")).strip()
                if not btext:
                    continue
                story.append(Paragraph(esc(btext), styles["bullet"], bulletText=glyph))
                plain.append(f"- {btext}")

    education = master.get("education") or []
    if education and tailored.get("include_education", True):
        section("EDUCATION")
        for edu in education:
            head = ", ".join(x for x in (esc(edu.get("degree", "")), esc(edu.get("school", ""))) if x)
            dates = " – ".join(d for d in (fmt_date(edu.get("start")), fmt_date(edu.get("end"))) if d)
            story.append(Paragraph(head, styles["role"]))
            meta = "  ·  ".join(x for x in (esc(edu.get("location", "")), esc(dates)) if x)
            if meta:
                story.append(Paragraph(meta, styles["meta"]))
            plain.append(f"{edu.get('degree','')}, {edu.get('school','')}  ({dates})")

    certs = master.get("certifications") or []
    if certs and tailored.get("include_certifications", True):
        section("CERTIFICATIONS")
        for cert in certs:
            story.append(Paragraph(esc(cert), styles["bullet"], bulletText=glyph))
            plain.append(f"- {cert}")

    story.append(Spacer(1, 2))
    return story, "\n".join(plain)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tailored", type=Path)
    ap.add_argument("--master", type=Path, default=Path("profile/master_resume.yaml"))
    ap.add_argument("-o", "--out", type=Path, required=True)
    ap.add_argument("--font", default="Helvetica", choices=["Helvetica", "Times-Roman", "Courier"])
    ap.add_argument("--size", type=float, default=10)
    ap.add_argument("--margin-mm", type=float, default=15)
    ap.add_argument("--max-pages", type=int, default=2)
    ap.add_argument("--bullet", default="-",
                    help="bullet glyph. The default hyphen is the only common choice that\nsurvives text extraction from the built-in fonts; '\u2022' renders as (cid:127)\nto a parser, so avoid it on anything an ATS will read.")
    args = ap.parse_args()

    import yaml
    from reportlab.lib.pagesizes import LETTER
    from reportlab.lib.units import mm
    from reportlab.platypus import HRFlowable, SimpleDocTemplate

    tailored = yaml.safe_load(args.tailored.read_text(encoding="utf-8")) or {}
    master = yaml.safe_load(args.master.read_text(encoding="utf-8")) or {}
    if "needs_review" in master:
        raise SystemExit(f"{args.master} still carries `needs_review` — confirm it first")

    styles = build_styles(args.font, args.size)
    rule = lambda: HRFlowable(width="100%", thickness=0.6, color="#888888",
                              spaceBefore=1, spaceAfter=4)
    if args.bullet in ("\u2022", "\u25aa", "\u25cf"):
        print(f"WARN    : bullet {args.bullet!r} does not survive PDF text extraction "
              "with the built-in fonts — resume parsers will see mojibake.")
    story, plain = flowables(tailored, master, styles, rule, args.bullet)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    name = (master.get("basics") or {}).get("name", "Resume")
    doc = SimpleDocTemplate(
        str(args.out), pagesize=LETTER,
        leftMargin=args.margin_mm * mm, rightMargin=args.margin_mm * mm,
        topMargin=args.margin_mm * mm, bottomMargin=args.margin_mm * mm,
        title=f"{name} — Resume", author=name,
        subject=f"Application {tailored.get('job_id','')}".strip(),
        creator="job-apply-agent",
    )
    doc.build(story)

    txt_out = args.out.with_suffix(".txt")
    txt_out.write_text(plain + "\n", encoding="utf-8")

    from pypdf import PdfReader

    pages = len(PdfReader(str(args.out)).pages)
    words = len(plain.split())
    print(f"written : {args.out} ({pages} page{'s' if pages != 1 else ''}, ~{words} words)")
    print(f"          {txt_out}  (paste-into-forms / ATS sanity copy)")
    if pages > args.max_pages:
        print(f"WARN    : {pages} pages exceeds the {args.max_pages}-page budget — drop bullets.")
    bullet_total = sum(len(e.get("bullets") or []) for k in ("experience", "projects")
                       for e in tailored.get(k) or [])
    print(f"content : {bullet_total} bullets, {len(tailored.get('skills') or [])} skills")
    return 0


if __name__ == "__main__":
    sys.exit(main())
