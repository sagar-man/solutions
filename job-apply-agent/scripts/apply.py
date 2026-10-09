#!/usr/bin/env python3
"""Prepare a LinkedIn Easy Apply form and then STOP, leaving Submit to you.

Run this on your own machine, in a visible browser, signed in to the Chromium
profile named in config.yaml. It will:

  1. open the job
  2. click Easy Apply (and nothing else that mutates anything)
  3. attach the tailored resume PDF
  4. fill only the fields it can match to profile/answers.yaml with confidence
  5. screenshot every step into <appdir>/screenshots/
  6. write anything it could not answer to <appdir>/needs_input.yaml
  7. hand the browser to you with the form filled and un-submitted

It never clicks Submit. That is enforced by a deny-list checked before every
single click, not by the order of operations -- see SUBMIT_DENY and
assert_clickable(). If the posting is not Easy Apply, it reports the external
URL and stops without touching it.

Usage:
  apply.py applications/4012345678 --resume applications/4012345678/resume.pdf
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _browser import chromium_executable  # noqa: E402

# Any control whose accessible name matches these is never clicked, ever.
SUBMIT_DENY = re.compile(
    r"\b(submit|send application|apply now|finish|confirm|agree and|"
    r"i certify|i agree|accept and|post\b|save and submit)\b", re.I,
)
# The only controls this script is allowed to click to advance a form.
ADVANCE_ALLOW = re.compile(r"\b(easy apply|next|continue|review|choose|upload|add)\b", re.I)

YES_NO = re.compile(r"^\s*(yes|no)\s*$", re.I)


class SubmitBlocked(RuntimeError):
    pass


def assert_clickable(label: str) -> str:
    """Single choke point: raise rather than click anything submit-shaped."""
    name = (label or "").strip()
    if SUBMIT_DENY.search(name):
        raise SubmitBlocked(f"refusing to click {name!r} — submission is yours to make")
    if not ADVANCE_ALLOW.search(name):
        raise SubmitBlocked(f"refusing to click {name!r} — not on the allowed-action list")
    return name


# --------------------------------------------------------------------------- #
# answer matching
# --------------------------------------------------------------------------- #

MATCHERS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"require.*(sponsor|visa)|need.*sponsor", re.I), "requires_sponsorship"),
    (re.compile(r"(legally )?authori[sz]ed|work authori[sz]ation|right to work", re.I), "work_authorization"),
    (re.compile(r"relocat", re.I), "willing_to_relocate"),
    (re.compile(r"remote|hybrid|on-?site preference", re.I), "remote_preference"),
    (re.compile(r"notice period", re.I), "notice_period"),
    (re.compile(r"start date|when (can|could) you start|available to start|earliest", re.I), "earliest_start_date"),
    (re.compile(r"salary|compensation|expected pay|desired pay|rate", re.I), "desired_salary"),
    (re.compile(r"mobile|phone", re.I), "_phone"),
    (re.compile(r"country code", re.I), "phone_country_code"),
    (re.compile(r"website|portfolio|personal site|github", re.I), "website"),
    (re.compile(r"how did you (hear|find)|referr?al source", re.I), "how_did_you_hear"),
    (re.compile(r"\bgender\b", re.I), "gender"),
    (re.compile(r"race|ethnic", re.I), "race_ethnicity"),
    (re.compile(r"veteran", re.I), "veteran_status"),
    (re.compile(r"disabilit", re.I), "disability_status"),
    (re.compile(r"\bemail\b", re.I), "_email"),
]
YEARS_Q = re.compile(r"how many years|years of (?:work |professional )?experience|years'? experience", re.I)
# Questions no script should answer on your behalf.
NEVER_AUTOFILL = re.compile(
    r"signature|initial|i certify|cover letter|why do you want|why are you interested|"
    r"tell us about|describe a time|what interests you|additional information|anything else", re.I,
)


def answer_for(label: str, answers: dict, basics: dict) -> tuple[str | None, str]:
    """Return (value, reason). value None means: leave it for the human."""
    label = " ".join((label or "").split())
    if not label:
        return None, "no label found"
    if NEVER_AUTOFILL.search(label):
        return None, "free-text or attestation — not auto-filled by design"

    if YEARS_Q.search(label):
        years = answers.get("years_experience") or {}
        if isinstance(years, dict):
            for tech, value in years.items():
                if tech == "default":
                    continue
                if re.search(r"(?<![\w+#])" + re.escape(tech) + r"(?![\w+#])", label, re.I):
                    return str(value), f"years_experience[{tech}]"
            if "default" in years:
                return str(years["default"]), "years_experience[default]"
        elif years:
            return str(years), "years_experience"
        return None, "years question with no matching entry in answers.yaml"

    for pattern, key in MATCHERS:
        if pattern.search(label):
            if key == "_phone":
                return str(basics.get("phone") or "") or None, "basics.phone"
            if key == "_email":
                return str(basics.get("email") or "") or None, "basics.email"
            value = answers.get(key)
            if value in (None, ""):
                return None, f"answers.yaml:{key} is empty"
            return str(value), f"answers.yaml:{key}"
    return None, "no matcher for this label"


# --------------------------------------------------------------------------- #
# page helpers
# --------------------------------------------------------------------------- #

FIELD_LABEL_JS = """el => {
     const id = el.getAttribute('id');
     if (id) {
       const l = document.querySelector(`label[for="${CSS.escape(id)}"]`);
       if (l && l.innerText.trim()) return l.innerText;
     }
     const wrap = el.closest('label');
     if (wrap && wrap.innerText.trim()) return wrap.innerText;
     const grp = el.closest('fieldset');
     const lg = grp && grp.querySelector('legend');
     if (lg && lg.innerText.trim()) return lg.innerText;
     const box = el.closest('div[data-test-form-element], div.fb-dash-form-element, div');
     const sp = box && box.querySelector('label, span.fb-dash-form-element__label, legend');
     return sp ? sp.innerText : '';
   }"""

# For a radio or checkbox the per-option label is "Yes" -- useless as a question.
# The question lives on the fieldset legend or the wrapping form element.
GROUP_LABEL_JS = """el => {
     const fs = el.closest('fieldset');
     const lg = fs && fs.querySelector('legend');
     if (lg && lg.innerText.trim()) return lg.innerText;
     const box = el.closest('div[data-test-form-element], div.fb-dash-form-element, div[role="group"]');
     if (box) {
       const q = box.querySelector('legend') ||
                 box.querySelector('span.fb-dash-form-element__label') ||
                 box.querySelector('label');
       if (q && q.innerText.trim()) return q.innerText;
     }
     return '';
   }"""

OPTION_LABEL_JS = """el => {
     const id = el.getAttribute('id');
     if (id) {
       const l = document.querySelector(`label[for="${CSS.escape(id)}"]`);
       if (l && l.innerText.trim()) return l.innerText;
     }
     const wrap = el.closest('label');
     if (wrap && wrap.innerText.trim()) return wrap.innerText;
     return el.getAttribute('value') || '';
   }"""


def _text(field, getters) -> str:
    """Try each label strategy in turn, returning the first non-empty result.

    A detached node or a missing attribute is normal and moves on to the next
    strategy. A SyntaxError from the injected JS is a bug in this file, not a
    page quirk, so it is re-raised rather than silently yielding "no label" --
    which is exactly how the quoting bug in GROUP_LABEL_JS once hid itself.
    """
    for getter in getters:
        try:
            text = (getter() or "").strip()
        except Exception as exc:  # noqa: BLE001
            if "SyntaxError" in str(exc) or "ReferenceError" in str(exc):
                raise RuntimeError(f"bug in the injected label script: {exc}") from exc
            continue
        if text:
            return " ".join(text.split())
    return ""


def label_for(field) -> str:
    """Best-effort accessible name for an input inside the Easy Apply modal."""
    return _text(field, [
        lambda: field.get_attribute("aria-label"),
        lambda: field.evaluate(FIELD_LABEL_JS),
        lambda: field.get_attribute("name"),
        lambda: field.get_attribute("placeholder"),
    ])


def group_label(field) -> str:
    """The question a radio/checkbox group is asking."""
    return _text(field, [
        lambda: field.evaluate(GROUP_LABEL_JS),
        lambda: field.get_attribute("aria-label"),
        lambda: field.get_attribute("name"),
    ])


def option_label(field) -> str:
    """The text of one option within a group."""
    return _text(field, [
        lambda: field.evaluate(OPTION_LABEL_JS),
        lambda: field.get_attribute("aria-label"),
        lambda: field.get_attribute("value"),
    ])


def shot(page, appdir: Path, step: int, tag: str, log: list) -> None:
    shots = appdir / "screenshots"
    shots.mkdir(parents=True, exist_ok=True)
    path = shots / f"{step:02d}-{re.sub(r'[^a-z0-9]+', '-', tag.lower()).strip('-')}.png"
    try:
        page.screenshot(path=str(path), full_page=False)
        log.append({"action": "screenshot", "file": str(path)})
        print(f"  [{step:02d}] screenshot -> {path.name}")
    except Exception as exc:  # noqa: BLE001
        print(f"  screenshot failed: {exc}", file=sys.stderr)


def fill_visible_fields(modal, answers: dict, basics: dict, log: list, unanswered: list) -> int:
    filled = 0

    # text / number / date / email inputs and textareas
    for field in modal.locator(
        "input[type='text'], input[type='email'], input[type='tel'], input[type='number'], "
        "input[type='date'], input:not([type]), textarea"
    ).all():
        try:
            if not field.is_visible() or not field.is_editable():
                continue
            label = label_for(field)
            existing = (field.input_value() or "").strip()
            value, reason = answer_for(label, answers, basics)
            if existing:
                log.append({"action": "skip", "label": label, "reason": f"prefilled with {existing!r}"})
                continue
            if value is None:
                unanswered.append({"label": label, "type": "text", "reason": reason})
                continue
            field.fill(value)
            filled += 1
            log.append({"action": "fill", "label": label, "value": value, "source": reason})
            print(f"       filled   {label[:62]!r} = {value!r}  ({reason})")
        except Exception as exc:  # noqa: BLE001
            unanswered.append({"label": label_for(field), "type": "text", "reason": f"error: {exc}"})

    # selects
    for field in modal.locator("select").all():
        try:
            if not field.is_visible():
                continue
            label = label_for(field)
            options = [
                (o.get("value", ""), " ".join((o.get("text") or "").split()))
                for o in field.evaluate(
                    "el => Array.from(el.options).map(o => ({value: o.value, text: o.text}))"
                )
            ]
            current = field.input_value()
            if current and current not in ("", "Select an option", "0"):
                log.append({"action": "skip", "label": label, "reason": "already selected"})
                continue
            value, reason = answer_for(label, answers, basics)
            if value is None:
                unanswered.append({"label": label, "type": "select",
                                   "options": [t for _, t in options], "reason": reason})
                continue
            match = next((v for v, t in options if t.lower() == value.lower()), None)
            if match is None:
                match = next((v for v, t in options
                              if t and (t.lower() in value.lower() or value.lower() in t.lower())), None)
            if match is None:
                unanswered.append({"label": label, "type": "select",
                                   "options": [t for _, t in options],
                                   "reason": f"answer {value!r} matches no option"})
                continue
            field.select_option(match)
            filled += 1
            log.append({"action": "select", "label": label, "value": match, "source": reason})
            print(f"       selected {label[:62]!r} = {match!r}  ({reason})")
        except Exception as exc:  # noqa: BLE001
            unanswered.append({"label": label_for(field), "type": "select", "reason": f"error: {exc}"})

    # radio groups (overwhelmingly Yes/No on LinkedIn)
    seen_groups: set[str] = set()
    for field in modal.locator("input[type='radio']").all():
        label = ""
        try:
            group = field.get_attribute("name") or ""
            if group and group in seen_groups:
                continue
            if group:
                seen_groups.add(group)
            siblings = (modal.locator(f"input[type='radio'][name='{group}']").all()
                        if group else [field])
            if any(radio.is_checked() for radio in siblings):
                continue

            label = group_label(field)
            value, reason = answer_for(label, answers, basics)
            options = [option_label(radio) for radio in siblings]
            if value is None:
                unanswered.append({"label": label, "type": "radio",
                                   "options": options, "reason": reason})
                continue

            target = next((radio for radio, text in zip(siblings, options)
                           if text.strip().lower() == value.strip().lower()), None)
            if target is None and YES_NO.match(value.strip()):
                target = next((radio for radio, text in zip(siblings, options)
                               if YES_NO.match(text.strip())
                               and text.strip().lower() == value.strip().lower()), None)
            if target is None:
                unanswered.append({"label": label, "type": "radio", "options": options,
                                   "reason": f"answer {value!r} matches no option"})
                continue

            target.check(force=True)
            filled += 1
            log.append({"action": "radio", "label": label, "value": value, "source": reason})
            print(f"       checked  {label[:62]!r} = {value!r}  ({reason})")
        except Exception as exc:  # noqa: BLE001
            unanswered.append({"label": label, "type": "radio", "reason": f"error: {exc}"})

    return filled


def attach_resume(modal, resume: Path, log: list) -> bool:
    for field in modal.locator("input[type='file']").all():
        try:
            accept = (field.get_attribute("accept") or "").lower()
            name = (field.get_attribute("id") or "") + (field.get_attribute("name") or "")
            if "cover" in name.lower():
                continue
            if accept and not any(ext in accept for ext in ("pdf", "doc", "*")):
                continue
            field.set_input_files(str(resume))
            log.append({"action": "upload", "file": str(resume)})
            print(f"       attached {resume.name}")
            return True
        except Exception as exc:  # noqa: BLE001
            print(f"       resume upload failed: {exc}", file=sys.stderr)
    return False


def click_advance(modal, page, log: list) -> str | None:
    """Click Next/Continue/Review if present. Returns the label clicked."""
    for selector in ("button[aria-label*='next' i]", "button[aria-label*='continue' i]",
                     "button[aria-label*='review' i]", "footer button", "button"):
        for button in modal.locator(selector).all():
            try:
                if not button.is_visible() or not button.is_enabled():
                    continue
                name = " ".join(((button.get_attribute("aria-label") or button.inner_text()) or "").split())
                if not name or SUBMIT_DENY.search(name) or not ADVANCE_ALLOW.search(name):
                    continue
                assert_clickable(name)
                button.click()
                page.wait_for_timeout(1200)
                log.append({"action": "click", "label": name})
                print(f"       clicked  {name!r}")
                return name
            except SubmitBlocked:
                continue
            except Exception:  # noqa: BLE001
                continue
    return None


# --------------------------------------------------------------------------- #

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("appdir", type=Path)
    ap.add_argument("--resume", type=Path, help="default: <appdir>/resume.pdf")
    ap.add_argument("--master", type=Path, default=Path("profile/master_resume.yaml"))
    ap.add_argument("--answers", type=Path, default=Path("profile/answers.yaml"))
    ap.add_argument("--browser-profile", default="~/.config/chromium/Claude-JobApply")
    ap.add_argument("--max-steps", type=int, default=8)
    ap.add_argument("--chromium-path", default=None,
                    help="chrome binary to use instead of Playwright's own download")
    ap.add_argument("--headless", action="store_true",
                    help="not recommended: you are meant to watch and press Submit")
    ap.add_argument("--close-when-done", action="store_true",
                    help="close the browser instead of handing it to you (nothing is submitted either way)")
    args = ap.parse_args()

    import yaml

    job_file = args.appdir / "job.json"
    if not job_file.exists():
        raise SystemExit(f"{job_file} not found — run fetch_job.py first")
    job = json.loads(job_file.read_text(encoding="utf-8"))
    job_id = job.get("job_id") or args.appdir.name

    resume = args.resume or (args.appdir / "resume.pdf")
    if not resume.exists():
        raise SystemExit(f"{resume} not found — run render_resume.py first")

    master = yaml.safe_load(args.master.read_text(encoding="utf-8")) or {}
    basics = master.get("basics") or {}
    answers = (yaml.safe_load(args.answers.read_text(encoding="utf-8"))
               if args.answers.exists() else {}) or {}
    if not answers:
        print(f"note: {args.answers} missing or empty — only resume upload will be automatic.")

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("playwright not installed. pip install playwright && playwright install chromium")

    chromium_path = args.chromium_path
    profile = Path(args.browser_profile).expanduser()
    profile.mkdir(parents=True, exist_ok=True)

    log: list[dict] = [{"started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                        "job_id": job_id, "resume": str(resume)}]
    unanswered: list[dict] = []
    status = "unknown"
    step = 0

    print(f"\nJob {job_id}: {job.get('title','?')} @ {job.get('company','?')}")
    print(f"Profile: {profile}\n")

    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(
            str(profile), headless=args.headless,
            viewport={"width": 1440, "height": 950},
            executable_path=chromium_executable(chromium_path),
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        try:
            page.goto(job.get("url") or f"https://www.linkedin.com/jobs/view/{job_id}/",
                      wait_until="domcontentloaded", timeout=60_000)
            page.wait_for_timeout(2500)
            step += 1
            shot(page, args.appdir, step, "job-page", log)

            if "/login" in page.url or "/authwall" in page.url or page.locator("input#session_key").count():
                status = "not_signed_in"
                raise SystemExit(
                    "this Chromium profile is signed out of LinkedIn.\n"
                    "Leave the window open, sign in by hand, close it, then re-run."
                )

            easy = None
            for selector in ("button.jobs-apply-button", "button[aria-label*='Easy Apply' i]",
                             "button:has-text('Easy Apply')"):
                candidate = page.locator(selector).first
                if candidate.count() and candidate.is_visible():
                    easy = candidate
                    break

            if easy is None:
                external = ""
                for selector in ("a[aria-label*='Apply' i]", "button:has-text('Apply')"):
                    node = page.locator(selector).first
                    if node.count():
                        external = node.get_attribute("href") or ""
                        break
                status = "external_apply"
                print("\nThis posting is not Easy Apply — it hands off to the company's own site.")
                print(f"  {external or job.get('url')}")
                print("  Nothing was clicked. Apply there yourself with:")
                print(f"    {resume}")
                if (args.appdir / 'cover_letter.pdf').exists():
                    print(f"    {args.appdir / 'cover_letter.pdf'}")
            else:
                name = " ".join(((easy.get_attribute("aria-label") or easy.inner_text()) or "").split())
                assert_clickable(name)
                easy.click()
                log.append({"action": "click", "label": name})
                page.wait_for_timeout(2000)
                print(f"       clicked  {name!r}")

                modal = page.locator("div.jobs-easy-apply-modal, div[role='dialog']").first
                modal.wait_for(state="visible", timeout=20_000)

                filled_total = 0
                for _ in range(args.max_steps):
                    step += 1
                    shot(page, args.appdir, step, f"form-step-{step}", log)
                    attach_resume(modal, resume, log)
                    filled_total += fill_visible_fields(modal, answers, basics, log, unanswered)

                    # Stop the moment the next control would be Submit.
                    footer_text = ""
                    try:
                        footer_text = modal.locator("footer, div[class*='footer']").first.inner_text()
                    except Exception:  # noqa: BLE001
                        pass
                    if SUBMIT_DENY.search(footer_text or "") and not ADVANCE_ALLOW.search(footer_text or ""):
                        status = "ready_for_your_submit"
                        print("\n       reached the final step — Submit is the only control left.")
                        break

                    if click_advance(modal, page, log) is None:
                        status = "ready_for_your_submit"
                        print("\n       no further Next/Review control — form is as complete as I can make it.")
                        break
                else:
                    status = "step_limit_reached"

                step += 1
                shot(page, args.appdir, step, "final-state", log)
                print(f"\n       fields filled: {filled_total}, left for you: {len(unanswered)}")

        except SubmitBlocked as exc:
            status = "blocked_submit_guard"
            print(f"\nGUARD: {exc}")
        except SystemExit as exc:
            print(f"\n{exc}")
        except Exception as exc:  # noqa: BLE001
            status = f"error: {type(exc).__name__}"
            print(f"\nerror: {type(exc).__name__}: {exc}", file=sys.stderr)
            step += 1
            shot(page, args.appdir, step, "error", log)
        finally:
            log.append({"status": status,
                        "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds")})
            (args.appdir / "apply_log.json").write_text(
                json.dumps(log, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            if unanswered:
                (args.appdir / "needs_input.yaml").write_text(
                    "# Questions this script would not answer for you.\n"
                    "# Fill them in the open browser window, or add the answer to\n"
                    "# profile/answers.yaml and re-run.\n\n"
                    + yaml.safe_dump({"job_id": job_id, "unanswered": unanswered},
                                     sort_keys=False, allow_unicode=True),
                    encoding="utf-8")
                print(f"       wrote {args.appdir/'needs_input.yaml'} ({len(unanswered)} open question(s))")

            if status == "ready_for_your_submit" and not args.close_when_done and not args.headless:
                print("\n" + "=" * 68)
                print("The form is filled and NOT submitted. Review it in the browser.")
                print("Press Submit yourself if it looks right.")
                print("Press Enter here when you are done to close the browser.")
                print("=" * 68)
                try:
                    input()
                except (EOFError, KeyboardInterrupt):
                    print("\n(no tty — leaving the browser open; close it yourself)")
                    return 0
            ctx.close()

    print(f"\nstatus : {status}")
    print(f"log    : {args.appdir/'apply_log.json'}")
    return 0 if status in ("ready_for_your_submit", "external_apply") else 1


if __name__ == "__main__":
    sys.exit(main())
