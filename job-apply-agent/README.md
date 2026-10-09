# job-apply-agent

Tailors a resume to a specific LinkedIn job ID, checks that the tailored copy
says nothing the master resume does not, renders ATS-safe PDFs, and fills the
Easy Apply form up to — but never including — the Submit button.

Driven by the `job-apply` skill (`.claude/skills/job-apply/SKILL.md` at the
repo root), which is what makes it an agent rather than a pile of scripts. The
scripts here are the deterministic parts: fetching, measuring, rendering and
checking. The judgement — which bullets fit this posting, how to reword them —
is the model's, fenced in by the checker.

## What it will not do

- **Submit.** `apply.py` checks every control against a deny-list before
  clicking it and refuses anything submit-shaped. The form is handed to you
  filled and unsent. This is the point of the tool, not a limitation of it.
- **Invent.** `verify_tailored.py` fails the build if the tailored resume
  introduces a number, metric, technology, employer, title or date that is not
  in `profile/master_resume.yaml`. A model asked to make a resume "fit" will
  round 30% up to 40% and add a framework you have never run; this is the part
  that catches it.
- **Pretend it applied.** Status is reported as what actually happened.

## Know this before you use it

Automating LinkedIn is against its
[User Agreement](https://www.linkedin.com/legal/user-agreement) (§8.2 prohibits
scraping and automated access). The design response is that the only
LinkedIn-touching steps run in a browser you are watching, with your own
session, and stop before the irreversible action. Easy Apply is also
CAPTCHA-gated in places — when that happens, finish by hand.

Run the LinkedIn steps on your own machine. A cloud container has no LinkedIn
session and should not be given one.

## Install

```bash
cd job-apply-agent
python3 -m pip install -r requirements.txt
python3 -m playwright install chromium     # only for --via browser and apply.py
cp config.example.yaml config.yaml
cp profile/answers.example.yaml profile/answers.yaml   # then edit
```

`config.yaml`, `profile/master_resume.yaml`, `profile/answers.yaml` and
everything under `applications/` are git-ignored: they are your personal data.

On a machine with a pinned or pre-provisioned Chromium, skip the download and
point at the existing binary with `--chromium-path` or
`PLAYWRIGHT_CHROMIUM_EXECUTABLE`.

## First run: build the master resume

Everything hangs off one file, `profile/master_resume.yaml`. It is the only
place facts live, and it should contain **more** than fits on a page — the agent
picks per job.

From an existing PDF:

```bash
python3 scripts/parse_resume.py ~/Downloads/resume.pdf \
    -o profile/master_resume.draft.yaml
```

Read the draft and its `needs_review` list, fix what the heuristics got wrong,
delete the `needs_review` key, save as `profile/master_resume.yaml`. Every later
step refuses to run while `needs_review` is present, because a parsing mistake
here becomes a false claim on your resume.

Starting from scratch instead: copy `profile/master_resume.example.yaml` and
fill it in.

Then add bullet `tags` (`[c++, performance, concurrency]`). They feed relevance
scoring and coverage, and they are worth the ten minutes.

## Per application

```bash
JOB=4012345678

python3 scripts/fetch_job.py $JOB                  # or --via browser / --from-file jd.txt
python3 scripts/jd_analyze.py applications/$JOB    # read gap_report.md
# ... agent writes applications/$JOB/tailored.yaml ...
python3 scripts/verify_tailored.py applications/$JOB/tailored.yaml
python3 scripts/render_resume.py applications/$JOB/tailored.yaml -o applications/$JOB/resume.pdf
python3 scripts/render_cover_letter.py applications/$JOB     # optional
python3 scripts/apply.py applications/$JOB                   # your machine only
```

Or just tell Claude: *"apply to LinkedIn job 4012345678"*.

Afterwards, `applications/$JOB/` holds the job description, the gap report, the
tailoring decisions, both PDFs, a screenshot of every form step, the action log
and any unanswered questions — enough to reconstruct what was sent and why.

## Scripts

| script | does | network |
|---|---|---|
| `fetch_job.py` | posting → `job.json` + `job_description.txt` | LinkedIn, or none with `--from-file` |
| `parse_resume.py` | resume PDF → draft master YAML | none |
| `jd_analyze.py` | posting vs master → `gap.json`, `gap_report.md` | none |
| `verify_tailored.py` | rejects unsupported claims | none |
| `render_resume.py` | tailored YAML → ATS-safe PDF + `.txt` | none |
| `render_cover_letter.py` | `cover_letter.md` → PDF | none |
| `apply.py` | fills Easy Apply, stops before Submit | LinkedIn |

## Tests

```bash
python3 tests/test_pipeline.py      # offline half: render → parse → analyse → verify
python3 tests/test_apply_logic.py   # click guard, answer matching, mock Easy Apply form
```

`test_pipeline.py` renders a resume, parses it back out of the PDF and compares
the result to the input — which is how the bullet-glyph bug below was found.
`test_apply_logic.py` drives `tests/mock_easy_apply.html`, a form shaped like
the Easy Apply modal whose last step offers nothing but "Submit application",
and asserts it is never pressed.

## Design notes

**ATS-safe means plain.** One column, one built-in font, real text, standard
headings, no tables or graphics. A resume submitted through LinkedIn is parsed
before a person reads it.

**Bullets are hyphens for a reason.** Rendered with reportlab's built-in
Helvetica, `•` (U+2022) extracts as `(cid:127)` and `▪` as `n` — so a parser
reads mojibake at the start of every bullet. Hyphen, en dash, middot and
asterisk survive. `--bullet` overrides it and warns if you pick a bad one.

**The master resume is the only source of facts.** The tailored file carries no
employer, title or date fields at all, so a tailoring pass cannot alter them —
the renderer reads them from the master. Claims are checked as (quantity, unit)
pairs rather than bare numbers, so "12 services" in the master does not license
"12 years" in the tailored copy.

## Limits

- `fetch_job.py --via guest` and `apply.py` are untested against live LinkedIn
  (the development environment's network policy blocks `www.linkedin.com`).
  Selectors are written against LinkedIn's current markup with fallbacks, but
  LinkedIn changes its DOM often; when a selector goes stale, the screenshots in
  `applications/<job>/screenshots/` show you where it stopped.
- `parse_resume.py` is heuristic. It handles conventional single-column resumes;
  two-column and heavily designed layouts need hand correction. It tells you
  what it is unsure about rather than guessing quietly.
- The verifier checks claims, not judgement. It cannot tell that a technically
  true bullet is misleading in context. That is still your job.
- LinkedIn only. Greenhouse, Lever and Workday would each need their own fetch
  and form adapter; the offline half (parse, analyse, verify, render) is
  platform-independent and would be reused as-is.
