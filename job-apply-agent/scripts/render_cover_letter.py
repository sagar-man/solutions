#!/usr/bin/env python3
"""Render a cover-letter body (plain text / light Markdown) to PDF.

The body is written by you or drafted by the agent into
applications/<job_id>/cover_letter.md — this script only lays it out, so the
words are always reviewable as text before they become a PDF.

Blank-line-separated paragraphs. `- ` lines become bullets. **bold** and
*italic* are honoured. Nothing else is interpreted.

Usage:
  render_cover_letter.py applications/4012345678 --master profile/master_resume.yaml
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import date
from pathlib import Path


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def inline_md(text: str) -> str:
    text = esc(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"<i>\1</i>", text)
    return text


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("appdir", type=Path)
    ap.add_argument("--master", type=Path, default=Path("profile/master_resume.yaml"))
    ap.add_argument("--body", type=Path, help="default: <appdir>/cover_letter.md")
    ap.add_argument("-o", "--out", type=Path, help="default: <appdir>/cover_letter.pdf")
    ap.add_argument("--font", default="Helvetica")
    ap.add_argument("--size", type=float, default=10.5)
    ap.add_argument("--margin-mm", type=float, default=20)
    args = ap.parse_args()

    import yaml
    from reportlab.lib.pagesizes import LETTER
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

    body_file = args.body or (args.appdir / "cover_letter.md")
    out_file = args.out or (args.appdir / "cover_letter.pdf")
    if not body_file.exists():
        raise SystemExit(f"{body_file} not found — write the letter body there first")

    master = yaml.safe_load(args.master.read_text(encoding="utf-8")) or {}
    basics = master.get("basics") or {}
    job = {}
    job_file = args.appdir / "job.json"
    if job_file.exists():
        job = json.loads(job_file.read_text(encoding="utf-8"))

    raw = body_file.read_text(encoding="utf-8").strip()
    if not raw:
        raise SystemExit(f"{body_file} is empty")

    bold = f"{args.font}-Bold"
    st = {
        "name": ParagraphStyle("n", fontName=bold, fontSize=args.size + 5, leading=args.size + 7),
        "contact": ParagraphStyle("c", fontName=args.font, fontSize=args.size - 1,
                                  leading=args.size + 2, spaceAfter=14),
        "meta": ParagraphStyle("m", fontName=args.font, fontSize=args.size, leading=args.size + 3,
                               spaceAfter=10),
        "body": ParagraphStyle("b", fontName=args.font, fontSize=args.size,
                               leading=args.size + 4.5, spaceAfter=8),
        "bullet": ParagraphStyle("l", fontName=args.font, fontSize=args.size,
                                 leading=args.size + 4, leftIndent=12, spaceAfter=3),
    }

    contact = [basics.get("location"), basics.get("phone"), basics.get("email")]
    contact += list((basics.get("links") or {}).values())

    story = [
        Paragraph(esc(str(basics.get("name", ""))), st["name"]),
        Paragraph("  ·  ".join(esc(str(c)) for c in contact if str(c or "").strip()), st["contact"]),
        Paragraph(date.today().strftime("%d %B %Y"), st["meta"]),
    ]

    target = []
    if job.get("company"):
        target.append(f"Hiring team, {esc(job['company'])}")
    if job.get("title"):
        target.append(f"Re: {esc(job['title'])}"
                      + (f" (LinkedIn job {esc(job.get('job_id',''))})" if job.get("job_id") else ""))
    if target:
        story.append(Paragraph("<br/>".join(target), st["meta"]))

    for block in re.split(r"\n\s*\n", raw):
        block = block.strip()
        if not block:
            continue
        if all(line.lstrip().startswith(("- ", "* ")) for line in block.splitlines()):
            for line in block.splitlines():
                story.append(Paragraph(inline_md(line.lstrip()[2:].strip()),
                                       st["bullet"], bulletText="-"))
            story.append(Spacer(1, 4))
        else:
            story.append(Paragraph(inline_md(" ".join(block.split())), st["body"]))

    out_file.parent.mkdir(parents=True, exist_ok=True)
    name = str(basics.get("name", "Cover letter"))
    SimpleDocTemplate(
        str(out_file), pagesize=LETTER,
        leftMargin=args.margin_mm * mm, rightMargin=args.margin_mm * mm,
        topMargin=args.margin_mm * mm, bottomMargin=args.margin_mm * mm,
        title=f"{name} — Cover letter", author=name, creator="job-apply-agent",
    ).build(story)

    from pypdf import PdfReader

    pages = len(PdfReader(str(out_file)).pages)
    words = len(raw.split())
    print(f"written : {out_file} ({pages} page{'s' if pages != 1 else ''}, {words} words)")
    if words > 400:
        print("WARN    : over 400 words — most hiring teams skim the first paragraph only.")
    if pages > 1:
        print("WARN    : more than one page for a cover letter is unusual.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
