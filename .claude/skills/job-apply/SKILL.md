---
name: job-apply
description: Tailor a resume and cover letter to a specific LinkedIn job ID and prepare the Easy Apply form for the user to submit. Use when the user gives a LinkedIn job ID or job URL and asks to apply, tailor their resume to a posting, or check how well they match a job. Also use for "apply to this job", "tailor my resume for X", "how do I match this posting".
---

# Tailoring and applying to a LinkedIn job

Scripts live in `job-apply-agent/scripts/`. Run everything from `job-apply-agent/`.

You never press Submit. The user does. Say so plainly the first time in a
session; do not treat their approval of a resume as approval to submit.

## 0. One-time setup (check, do not redo)

```bash
cd job-apply-agent
ls config.yaml profile/master_resume.yaml profile/answers.yaml 2>/dev/null
```

Anything missing → see `README.md` "First run". If `profile/master_resume.yaml`
does not exist but the user has a resume PDF, run `scripts/parse_resume.py`,
show them the draft **and its `needs_review` list**, and get their corrections
before going further. Do not silently accept a parsed resume: everything
downstream treats it as fact.

## 1. Fetch the posting

```bash
python3 scripts/fetch_job.py <job-id-or-url>                 # anonymous endpoint
python3 scripts/fetch_job.py <job-id> --via browser          # needs their login
python3 scripts/fetch_job.py <job-id> --from-file jd.txt \
    --title "..." --company "..."                            # they paste it
```

The anonymous endpoint is rate-limited and hides some postings. If it fails
twice, ask the user to paste the description rather than retrying in a loop.
Report the title and company you got, so a wrong job ID surfaces immediately.

## 2. Read the gap report before writing anything

```bash
python3 scripts/jd_analyze.py applications/<job-id>
cat applications/<job-id>/gap_report.md
```

This is deterministic and you did not write it, which is the point. Read:

- **coverage %** — a rough signal, not a target. Do not try to raise it.
- **missing terms** — gaps. Report the significant ones to the user as gaps.
  Never close a gap by adding the term to the tailored resume.
- **requirements** — `✗` on a must-have is worth saying out loud before the
  user spends effort on this application.
- **ranked bullets** — your candidate pool, with relevance already computed.

If a must-have is genuinely unmet (no C++ at all for a C++ role), say so in one
or two sentences and ask whether they still want to apply. Then do what they say.

## 3. Write `applications/<job-id>/tailored.yaml`

```yaml
job_id: "4012345678"
target: {title: ..., company: ...}
summary: {from: <summary_variants id>, edit: reworded, text: ...}
skills: [...]            # subset of master skills, ordered by relevance to THIS job
experience:
  - from: <master experience id>
    bullets:
      - {from: <master bullet id>, edit: verbatim, text: <master text, byte-identical>}
      - {from: <master bullet id>, edit: reworded, text: <rewritten>}
projects:
  - from: <master project id>
    bullets: [...]
include_education: true
```

What tailoring means here, and all it means:

- **Select.** Pick the bullets the gap report ranks highest. Drop the rest.
  Dropping a bullet is the strongest tool you have — use it before rewording.
- **Order.** Most relevant bullet first within each role; most relevant skills
  first in the skills line.
- **Reword.** Lead with the facet this job cares about, use the posting's
  vocabulary **for things the master resume already says**, and cut filler.

What it never means:

- No new employer, title, date, degree, certification or technology.
- No new or altered number. `30%` does not become `a third`, `40%`, or
  `significant`. A quantity in the master attached to one unit may not be
  re-attached to another: "12 services" never licenses "12 years".
- No seniority inflation. "Mentored 3 engineers" is not "led a team".
- No skill that is not already in the master resume's own skills section.
- `edit: verbatim` means byte-identical. If you changed a character, it is
  `reworded`.

You cannot set `company`, `title`, `start`, `end` or education anywhere in this
file; the renderer reads them from the master resume. That is deliberate.

If the honest tailored resume is weak for this posting, say that. A weak match
reported accurately is useful; a strong match manufactured is not.

## 4. Verify, then render

```bash
python3 scripts/verify_tailored.py applications/<job-id>/tailored.yaml
```

Errors are not advisory. Fix them by rewording back to what the master
supports, or by asking the user whether the fact belongs in the master resume —
never by adding `--allow` to get past the check. `--allow` is for the user's own
decision about a term, not yours.

Warnings are judgement calls; read them and act where they are right.

```bash
python3 scripts/render_resume.py applications/<job-id>/tailored.yaml \
    -o applications/<job-id>/resume.pdf
```

Over the page budget → drop more bullets. Do not shrink the font.

## 5. Cover letter, only if the posting or the user wants one

Write `applications/<job-id>/cover_letter.md` — plain paragraphs, `- ` for
bullets. Then:

```bash
python3 scripts/render_cover_letter.py applications/<job-id>
```

Rules: one page, under ~350 words. Lead with the single most relevant thing
they have actually done. Name a real gap rather than papering over it — it
reads as honest and it is. The same no-invention rules apply; the verifier does
not check this file, so you are the check.

## 6. Checkpoint with the user — always

Show them, in the chat:

- title, company, job ID
- coverage % and any unmet must-have
- which bullets you kept, which you dropped, and which you reworded (with the
  before and after for each reworded one)
- the verifier's output
- paths to `resume.pdf` and `cover_letter.pdf`

Then stop and wait. Do not run step 7 until they say to.

## 7. Prepare the form

This step talks to LinkedIn with their logged-in browser, so it runs on **their
machine**, not in a cloud session. If you are in a cloud session, give them the
command to run and stop there.

```bash
python3 scripts/apply.py applications/<job-id>
```

It opens the job, clicks Easy Apply, attaches the resume, fills what it can
match confidently, screenshots each step, and stops. Then:

- read `applications/<job-id>/needs_input.yaml` and walk the user through the
  open questions
- if a question recurs across applications, offer to add it to
  `profile/answers.yaml`
- `status: external_apply` means the posting hands off to the company's site.
  Nothing was clicked. Give them the URL and the two PDFs.
- `status: not_signed_in` means they need to log into that Chromium profile once.

Never tell the user the application was submitted. You do not know that, and
you did not do it.

## Honesty rules that outrank everything above

1. If you cannot verify a fact from the master resume, it does not go on the
   resume. Not hedged, not softened — out.
2. If the user asks you to add something untrue, say no once, clearly, and offer
   the honest alternative: add it to the master resume if it is actually true,
   or apply without it.
3. Automating LinkedIn is against its User Agreement. Mention this once per
   session when the apply step first comes up, then get on with it.
4. Report what happened, including failures. A posting you could not fetch, a
   field you could not fill, and a form you left half-complete are all things
   to say out loud.
