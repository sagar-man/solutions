#!/usr/bin/env python3
"""Fetch a LinkedIn job posting into a per-application working directory.

Three sources, in order of how much they need from you:

  --via guest    anonymous jobs-guest endpoint, no login. Works for most
                 public postings; LinkedIn rate-limits it, so expect the
                 occasional 429 and fall back.
  --via browser  reuses your logged-in Chromium profile via Playwright.
                 Needed for postings the guest endpoint hides.
  --from-file    you paste the description into a file. Always works, needs
                 no network, and is the only source that touches nothing.

Usage:
  fetch_job.py 4012345678
  fetch_job.py https://www.linkedin.com/jobs/view/4012345678/ --via browser
  fetch_job.py 4012345678 --from-file jd.txt --title "Staff Engineer" --company Acme
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _browser import chromium_executable  # noqa: E402

GUEST_URL = "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/{job_id}"
VIEW_URL = "https://www.linkedin.com/jobs/view/{job_id}/"

UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def extract_job_id(raw: str) -> str:
    """Accept a bare id, a /jobs/view/ URL, or a collections URL."""
    raw = raw.strip()
    if raw.isdigit():
        return raw
    for pattern in (r"/jobs/view/(\d+)", r"currentJobId=(\d+)", r"/jobs-guest/jobs/api/jobPosting/(\d+)"):
        m = re.search(pattern, raw)
        if m:
            return m.group(1)
    raise SystemExit(f"could not find a LinkedIn job id in {raw!r}")


def _first_text(soup, selectors: list[str]) -> str:
    for sel in selectors:
        node = soup.select_one(sel)
        if node:
            text = node.get_text(" ", strip=True)
            if text:
                return text
    return ""


def _html_to_text(node) -> str:
    """Flatten LinkedIn's description markup, keeping list and paragraph breaks."""
    if node is None:
        return ""
    for br in node.find_all("br"):
        br.replace_with("\n")
    for li in node.find_all("li"):
        li.insert_before("\n- ")
    for p in node.find_all(["p", "ul", "ol", "div"]):
        p.insert_after("\n")
    text = node.get_text("", strip=False)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
    return "\n".join(line.strip() for line in text.splitlines()).strip()


def parse_posting_html(html: str, job_id: str) -> dict:
    from bs4 import BeautifulSoup

    soup = BeautifulSoup(html, "html.parser")

    title = _first_text(soup, [
        "h2.top-card-layout__title", "h1.top-card-layout__title",
        "h2.topcard__title", "h1.topcard__title",
        "h1.t-24", "h1",
    ])
    company = _first_text(soup, [
        "a.topcard__org-name-link", "span.topcard__flavor",
        "a.top-card-layout__card-link", ".topcard__org-name-link",
        ".job-details-jobs-unified-top-card__company-name",
    ])
    location = _first_text(soup, [
        "span.topcard__flavor--bullet", ".topcard__flavor-row .topcard__flavor--bullet",
        ".job-details-jobs-unified-top-card__bullet",
    ])

    desc_node = (
        soup.select_one("div.show-more-less-html__markup")
        or soup.select_one("div.description__text")
        or soup.select_one(".jobs-description__content")
        or soup.select_one("article")
    )
    description = _html_to_text(desc_node)

    criteria: dict[str, str] = {}
    for item in soup.select("li.description__job-criteria-item"):
        key = _first_text(item, ["h3", ".description__job-criteria-subheader"])
        val = _first_text(item, ["span", ".description__job-criteria-text"])
        if key:
            criteria[key.strip().rstrip(":")] = val

    # Structured data, when LinkedIn includes it, beats scraping.
    for script in soup.find_all("script", {"type": "application/ld+json"}):
        try:
            data = json.loads(script.string or "{}")
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(data, list):
            data = next((d for d in data if isinstance(d, dict)), {})
        if data.get("@type") == "JobPosting":
            title = title or data.get("title", "")
            org = data.get("hiringOrganization") or {}
            company = company or (org.get("name", "") if isinstance(org, dict) else "")
            criteria.setdefault("Employment type", data.get("employmentType", "") or "")
            criteria.setdefault("Posted", data.get("datePosted", "") or "")

    return {
        "job_id": job_id,
        "url": VIEW_URL.format(job_id=job_id),
        "title": title,
        "company": company,
        "location": location,
        "criteria": criteria,
        "description": description,
    }


def fetch_via_guest(job_id: str, retries: int = 3) -> dict:
    import requests

    url = GUEST_URL.format(job_id=job_id)
    delay = 2
    last = ""
    for attempt in range(1, retries + 1):
        try:
            resp = requests.get(url, headers={"User-Agent": UA, "Accept": "text/html"}, timeout=30)
        except requests.RequestException as exc:
            last = f"{type(exc).__name__}: {exc}"
        else:
            if resp.status_code == 200 and resp.text.strip():
                return parse_posting_html(resp.text, job_id)
            last = f"HTTP {resp.status_code}"
            if resp.status_code in (429, 999) and attempt < retries:
                pass  # back off and retry
            elif resp.status_code == 404:
                raise SystemExit(f"job {job_id} not found on the guest endpoint (HTTP 404)")
        if attempt < retries:
            print(f"  guest attempt {attempt} failed ({last}); retrying in {delay}s", file=sys.stderr)
            time.sleep(delay)
            delay *= 2
    raise SystemExit(
        f"guest fetch failed after {retries} attempts ({last}).\n"
        f"Try:  --via browser      (uses your logged-in profile)\n"
        f"  or: --from-file jd.txt (paste the description yourself)"
    )


def fetch_via_browser(job_id: str, profile_dir: str, headless: bool,
                      chromium_path: str | None = None) -> dict:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("playwright not installed. pip install playwright && playwright install chromium")

    url = VIEW_URL.format(job_id=job_id)
    profile = Path(profile_dir).expanduser()
    profile.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(str(profile), headless=headless,
                                                  executable_path=chromium_executable(chromium_path))
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.goto(url, wait_until="domcontentloaded", timeout=60_000)
        # Expand the truncated description if the control is present.
        for sel in ("button.show-more-less-html__button",
                    "button[aria-label*='see more' i]",
                    ".jobs-description__footer-button"):
            try:
                btn = page.locator(sel).first
                if btn.is_visible(timeout=1500):
                    btn.click()
                    page.wait_for_timeout(400)
                    break
            except Exception:
                continue
        html = page.content()
        ctx.close()

    job = parse_posting_html(html, job_id)
    if not job["description"]:
        raise SystemExit(
            "browser fetch returned no description. You are probably signed out of "
            "this Chromium profile — run with --headless false, log in once, then retry."
        )
    return job


def fetch_from_file(job_id: str, path: Path, title: str, company: str) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace")
    if "<" in raw and ">" in raw and re.search(r"<(html|div|body|p)\b", raw, re.I):
        job = parse_posting_html(raw, job_id)
    else:
        job = {
            "job_id": job_id, "url": VIEW_URL.format(job_id=job_id),
            "title": "", "company": "", "location": "", "criteria": {},
            "description": raw.strip(),
        }
    job["title"] = title or job["title"]
    job["company"] = company or job["company"]
    return job


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("job", help="LinkedIn job id or job URL")
    ap.add_argument("--via", choices=["guest", "browser"], default="guest")
    ap.add_argument("--from-file", type=Path, help="read the posting from this file instead of the network")
    ap.add_argument("--title", default="", help="override/supply the job title")
    ap.add_argument("--company", default="", help="override/supply the company name")
    ap.add_argument("--workdir", default="applications")
    ap.add_argument("--browser-profile", default="~/.config/chromium/Claude-JobApply")
    ap.add_argument("--headless", action="store_true")
    ap.add_argument("--chromium-path", default=None,
                    help="chrome binary to use instead of Playwright's own download")
    args = ap.parse_args()

    job_id = extract_job_id(args.job)

    if args.from_file:
        job = fetch_from_file(job_id, args.from_file, args.title, args.company)
        job["source"] = f"file:{args.from_file.name}"
    elif args.via == "browser":
        job = fetch_via_browser(job_id, args.browser_profile, args.headless, args.chromium_path)
        job["source"] = "linkedin:browser"
    else:
        job = fetch_via_guest(job_id)
        job["source"] = "linkedin:guest"

    if args.title:
        job["title"] = args.title
    if args.company:
        job["company"] = args.company
    job["fetched_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")

    if not job["description"]:
        raise SystemExit("no description captured — refusing to write an empty job.json")

    out_dir = Path(args.workdir) / job_id
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "job.json"
    out_file.write_text(json.dumps(job, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out_dir / "job_description.txt").write_text(job["description"] + "\n", encoding="utf-8")

    words = len(job["description"].split())
    print(f"{job['title'] or '(no title)'} @ {job['company'] or '(no company)'}")
    print(f"  location : {job['location'] or '-'}")
    print(f"  source   : {job['source']}  ({words} words)")
    print(f"  written  : {out_file}")
    if not job["title"] or not job["company"]:
        print("  note: title/company missing — pass --title/--company so the cover letter is addressed correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
