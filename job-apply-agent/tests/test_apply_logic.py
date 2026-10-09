#!/usr/bin/env python3
"""Tests for the parts of apply.py that decide what to click and what to type.

The browser half cannot be tested against LinkedIn, so it is tested against
tests/mock_easy_apply.html -- a form with the same shape as the Easy Apply
modal, including a final step whose only control is "Submit application".
The point of that last step is to prove the guard refuses to press it.

Run:  python3 tests/test_apply_logic.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import apply as A  # noqa: E402
from _browser import chromium_executable  # noqa: E402

PASSED, FAILED = [], []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASSED if condition else FAILED).append(name)
    print(f"  {'PASS' if condition else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not condition else ""))


# --------------------------------------------------------------------------- #
def test_submit_guard() -> None:
    print("\nsubmit guard")
    must_refuse = [
        "Submit application", "submit", "Send application", "Apply now", "Finish",
        "Confirm and submit", "I agree to the terms", "I certify that the above is true",
        "Agree and continue", "Accept and apply", "Save and submit",
    ]
    for label in must_refuse:
        try:
            A.assert_clickable(label)
            check(f"refuses {label!r}", False, "it was allowed through")
        except A.SubmitBlocked:
            check(f"refuses {label!r}", True)

    for label in ["Easy Apply", "Next", "Continue to next step", "Review your application",
                  "Choose file", "Upload resume"]:
        try:
            A.assert_clickable(label)
            check(f"allows {label!r}", True)
        except A.SubmitBlocked as exc:
            check(f"allows {label!r}", False, str(exc))

    for label in ["Dismiss", "Follow Initech", "Save this job", "", "Report this job"]:
        try:
            A.assert_clickable(label)
            check(f"refuses unlisted {label!r}", False, "it was allowed through")
        except A.SubmitBlocked:
            check(f"refuses unlisted {label!r}", True)


def test_answer_matching() -> None:
    print("\nanswer matching")
    answers = {
        "work_authorization": "Authorized to work in the US without sponsorship",
        "requires_sponsorship": "No",
        "willing_to_relocate": "Yes",
        "notice_period": "4 weeks",
        "desired_salary": "",
        "how_did_you_hear": "LinkedIn",
        "years_experience": {"default": "8", "c++": "8", "kubernetes": "4"},
    }
    basics = {"phone": "+1 555 010 0000", "email": "you@example.com"}

    cases = [
        ("How many years of C++ experience do you have?", "8", "years_experience[c++]"),
        ("How many years of Kubernetes experience do you have?", "4", "years_experience[kubernetes]"),
        ("How many years of experience do you have overall?", "8", "years_experience[default]"),
        ("Are you legally authorized to work in the United States?",
         "Authorized to work in the US without sponsorship", None),
        ("Will you now or in the future require sponsorship?", "No", None),
        ("Are you willing to relocate?", "Yes", None),
        ("What is your notice period?", "4 weeks", None),
        ("Mobile phone number", "+1 555 010 0000", "basics.phone"),
        ("Email address", "you@example.com", "basics.email"),
        ("How did you hear about this job?", "LinkedIn", None),
        # Deliberately unanswered:
        ("Desired salary", None, "empty answer is not a guess"),
        ("Why do you want to work here?", None, "free text stays yours"),
        ("Please type your full name as a signature", None, "attestation"),
        ("What is your favourite colour?", None, "no matcher"),
        ("", None, "no label"),
    ]
    for label, expected, note in cases:
        value, reason = A.answer_for(label, answers, basics)
        check(f"{label[:52]!r} -> {expected!r}", value == expected, f"got {value!r} ({reason})")


def test_against_mock_form() -> None:
    print("\nmock Easy Apply form (chromium)")
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("  SKIP  playwright not installed")
        return

    answers = {
        "work_authorization": "Authorized to work in the US without sponsorship",
        "requires_sponsorship": "No",
        "willing_to_relocate": "Yes",
        "notice_period": "4 weeks",
        "desired_salary": "",
        "how_did_you_hear": "LinkedIn",
        "years_experience": {"default": "8", "c++": "8", "kubernetes": "4"},
    }
    basics = {"phone": "+1 555 010 0000", "email": "you@example.com"}

    resume = ROOT / "tests" / "_fixture_resume.pdf"
    resume.write_bytes(b"%PDF-1.4\n% fixture\n")

    log: list[dict] = []
    unanswered: list[dict] = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chromium_executable())
        page = browser.new_page()
        page.goto((ROOT / "tests" / "mock_easy_apply.html").as_uri())

        easy = page.locator("button.jobs-apply-button").first
        A.assert_clickable(easy.get_attribute("aria-label"))
        easy.click()
        modal = page.locator("div[role='dialog']").first
        modal.wait_for(state="visible", timeout=5000)

        uploaded = A.attach_resume(modal, resume, log)
        check("resume attached to the resume input", uploaded)
        check("cover-letter input left alone",
              page.locator("#f-cover").evaluate("el => el.files.length") == 0)
        check("resume input received the file",
              page.locator("#f-resume").evaluate("el => el.files.length") == 1)

        filled = A.fill_visible_fields(modal, answers, basics, log, unanswered)
        values = {sel: page.locator(sel).input_value()
                  for sel in ("#f-phone", "#f-cppyears", "#f-k8syears", "#f-genyears",
                              "#f-notice", "#f-salary", "#f-why", "#f-favcolour",
                              "#f-auth", "#f-hear", "#f-stack")}

        check("phone filled", values["#f-phone"] == "+1 555 010 0000", values["#f-phone"])
        check("C++ years = 8", values["#f-cppyears"] == "8", values["#f-cppyears"])
        check("Kubernetes years = 4", values["#f-k8syears"] == "4", values["#f-k8syears"])
        check("generic years = 8 (default)", values["#f-genyears"] == "8", values["#f-genyears"])
        check("notice period filled", values["#f-notice"] == "4 weeks", values["#f-notice"])
        check("empty answer leaves salary blank", values["#f-salary"] == "", values["#f-salary"])
        check("free-text 'why' left blank", values["#f-why"] == "", values["#f-why"])
        check("unmatched question left blank", values["#f-favcolour"] == "", values["#f-favcolour"])
        check("authorization select chosen", values["#f-auth"] == "y", values["#f-auth"])
        check("'how did you hear' select chosen", values["#f-hear"] == "li", values["#f-hear"])
        check("unmatched select left unchosen", values["#f-stack"] == "", values["#f-stack"])
        check("sponsorship radio = No", page.locator("#f-sp-n").is_checked())
        check("sponsorship radio Yes untouched", not page.locator("#f-sp-y").is_checked())
        check("relocate radio = Yes", page.locator("#f-rel-y").is_checked())
        check("prefilled email not overwritten",
              page.locator("#f-email").input_value() == "you@example.com")
        check("something was filled", filled >= 8, f"filled={filled}")

        labels = {u["label"] for u in unanswered}
        check("salary reported as unanswered",
              any("salary" in l.lower() for l in labels), str(labels))
        check("'why do you want' reported as unanswered",
              any("why do you want" in l.lower() for l in labels), str(labels))
        check("favourite colour reported as unanswered",
              any("favourite" in l.lower() for l in labels), str(labels))
        check("preferred stack reported as unanswered",
              any("stack" in l.lower() for l in labels), str(labels))

        clicked = A.click_advance(modal, page, log)
        check("advanced via Next", clicked is not None and "next" in clicked.lower(), str(clicked))
        check("reached the review step", page.locator("#step2").is_visible())

        # The final step offers nothing but Submit.
        clicked_again = A.click_advance(modal, page, log)
        check("refused to advance past the review step", clicked_again is None, str(clicked_again))
        check("form was NOT submitted",
              page.locator("body").get_attribute("data-submitted") is None)

        browser.close()
    resume.unlink(missing_ok=True)


if __name__ == "__main__":
    test_submit_guard()
    test_answer_matching()
    test_against_mock_form()
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("failed: " + ", ".join(FAILED))
    sys.exit(1 if FAILED else 0)
