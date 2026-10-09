#!/usr/bin/env python3
"""Compare a fetched job description against the master resume.

Produces two artefacts in the application directory:

  gap.json       machine-readable: keywords, coverage, per-bullet relevance
  gap_report.md  the thing you read before approving a tailored resume

Nothing here calls a model. It is deliberately dumb and deterministic so the
numbers in the report mean the same thing every run, and so the agent's
tailoring choices can be checked against something it did not author.

Usage:
  jd_analyze.py applications/4012345678 --master profile/master_resume.yaml
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# Multi-word and punctuation-bearing terms that naive tokenising destroys.
LEXICON = [
    "c++", "c#", ".net", "node.js", "next.js", "react native", "objective-c", "f#",
    "golang", "go", "rust", "python", "java", "kotlin", "swift", "scala", "ruby",
    "typescript", "javascript", "php", "perl", "r", "matlab", "sql", "nosql", "bash",
    "shell scripting", "assembly", "verilog", "vhdl", "haskell", "elixir", "erlang",
    "machine learning", "deep learning", "nlp", "natural language processing",
    "computer vision", "llm", "large language models", "pytorch", "tensorflow",
    "scikit-learn", "pandas", "numpy", "spark", "hadoop", "kafka", "flink", "airflow",
    "dbt", "snowflake", "databricks", "bigquery", "redshift",
    "kubernetes", "k8s", "docker", "terraform", "ansible", "helm", "pulumi",
    "aws", "gcp", "google cloud", "azure", "ec2", "s3", "lambda", "eks", "gke",
    "ci/cd", "jenkins", "github actions", "gitlab ci", "circleci", "argocd",
    "prometheus", "grafana", "datadog", "opentelemetry", "splunk", "elk",
    "postgresql", "postgres", "mysql", "mongodb", "redis", "cassandra", "dynamodb",
    "elasticsearch", "sqlite", "oracle", "clickhouse", "etcd",
    "grpc", "rest", "restful", "graphql", "protocol buffers", "protobuf", "thrift",
    "websockets", "http/2", "tcp/ip", "openapi", "soap",
    "microservices", "distributed systems", "event-driven", "service mesh",
    "multithreading", "multi-threading", "concurrency", "parallelism", "lock-free",
    "pthreads", "async", "asyncio", "coroutines", "memory management", "rtos",
    "embedded", "linux kernel", "device drivers", "firmware", "low latency",
    "high availability", "fault tolerance", "scalability", "performance tuning",
    "profiling", "benchmarking", "capacity planning", "load testing",
    "react", "angular", "vue", "svelte", "redux", "tailwind", "css", "html",
    "django", "flask", "fastapi", "spring", "spring boot", "express", "rails",
    "unit testing", "integration testing", "tdd", "bdd", "pytest", "googletest",
    "gtest", "junit", "selenium", "playwright", "cypress", "jest",
    "git", "code review", "agile", "scrum", "kanban", "jira", "design docs",
    "on-call", "incident response", "sre", "devops", "observability", "slo",
    "oauth", "saml", "jwt", "tls", "encryption", "security", "penetration testing",
    "threat modeling", "soc 2", "gdpr", "hipaa", "pci",
    "mentoring", "mentorship", "technical leadership", "stakeholder management",
    "cross-functional", "roadmap", "hiring", "architecture", "system design",
    "data structures", "algorithms", "api design", "refactoring", "migration",
]
LEXICON = sorted(set(LEXICON), key=len, reverse=True)

# A non-bullet line only counts as a requirement if it carries one of these.
STRONG_CUES = re.compile(
    r"\b(?:experience (?:with|in|of)|proficien(?:t|cy)|expertise in|familiar(?:ity)? with|"
    r"knowledge of|background in|years? of|must have|require[sd]?|minimum of|"
    r"you (?:should|must) have|we (?:are looking for|expect))\b", re.I,
)
NICE_TO_HAVE = re.compile(r"\b(?:nice to have|preferred|bonus|a plus|desirable|"
                          r"good to have|would be a plus|optional)\b", re.I)
HARD_REQ = re.compile(r"\b(?:must|require[sd]?|minimum|mandatory|essential|non-negotiable)\b", re.I)
# Headings that switch the priority applied to the lines beneath them.
HEAD_NICE = re.compile(r"\b(?:nice to have|preferred|bonus|pluses|good to have|"
                       r"desirable|additionally)\b", re.I)
HEAD_REQ = re.compile(r"\b(?:requirements?|qualifications?|must have|what you.{0,3}ll need|"
                      r"minimum|who you are|about you|basic qualifications)\b", re.I)
HEAD_SKIP = re.compile(r"\b(?:benefits?|perks|compensation|what we offer|we offer|"
                       r"equal opportunity|eeo\b|diversity|about (?:us|the company)|"
                       r"our (?:mission|values)|how to apply|interview process)\b", re.I)

STOPWORDS = {
    "the", "and", "for", "with", "you", "our", "are", "will", "that", "this", "have",
    "from", "your", "all", "can", "who", "but", "not", "any", "has", "its", "their",
    "work", "team", "teams", "role", "job", "company", "we", "us", "a", "an", "to", "in",
    "of", "on", "at", "as", "by", "or", "is", "be", "it", "we're", "you'll", "including",
}
YEARS_RE = re.compile(r"(\d+)\+?\s*(?:-\s*\d+\s*)?years?", re.I)


def normalise(text: str) -> str:
    text = text.lower()
    text = text.replace("’", "'").replace("–", "-").replace("—", "-")
    return re.sub(r"\s+", " ", text)


def find_terms(text: str) -> dict[str, int]:
    """Count lexicon hits plus salient unknown capitalised/acronym tokens."""
    norm = normalise(text)
    counts: dict[str, int] = {}

    for term in LEXICON:
        # Word-ish boundaries that survive +, #, ., / inside the term.
        pattern = r"(?<![\w+#.])" + re.escape(term) + r"(?![\w+#]|\.\w)"
        n = len(re.findall(pattern, norm))
        if n:
            counts[term] = n

    # Acronyms and product names the lexicon does not know about.
    for token in re.findall(r"\b[A-Z][A-Za-z0-9+#.]{1,19}\b|\b[A-Z]{2,6}\b", text):
        low = token.lower()
        if low in STOPWORDS or low in counts or len(low) < 2:
            continue
        if token.isupper() or re.search(r"[A-Z].*[A-Z]", token):  # ACRONYM or CamelCase
            counts.setdefault(low, text.count(token))
    return counts


def resume_corpus(master: dict) -> tuple[str, list[dict]]:
    """Flat searchable text, plus every bullet with its provenance."""
    chunks: list[str] = []
    bullets: list[dict] = []

    for variant in master.get("summary_variants") or []:
        chunks.append(variant.get("text", ""))
    for group in master.get("skills") or []:
        chunks.append(group.get("group", ""))
        chunks += [str(i) for i in group.get("items", [])]
    for entry in master.get("experience") or []:
        chunks += [str(entry.get(k, "")) for k in ("company", "title", "location")]
        for bullet in entry.get("bullets") or []:
            chunks.append(bullet.get("text", ""))
            chunks += [str(t) for t in bullet.get("tags") or []]
            bullets.append({
                "id": bullet.get("id", ""),
                "text": bullet.get("text", ""),
                "tags": bullet.get("tags") or [],
                "parent": entry.get("id", ""),
                "parent_label": f"{entry.get('title','')} @ {entry.get('company','')}",
                "kind": "experience",
            })
    for project in master.get("projects") or []:
        chunks.append(project.get("name", ""))
        for bullet in project.get("bullets") or []:
            chunks.append(bullet.get("text", ""))
            chunks += [str(t) for t in bullet.get("tags") or []]
            bullets.append({
                "id": bullet.get("id", ""),
                "text": bullet.get("text", ""),
                "tags": bullet.get("tags") or [],
                "parent": project.get("id", ""),
                "parent_label": project.get("name", ""),
                "kind": "project",
            })
    for edu in master.get("education") or []:
        chunks += [str(edu.get(k, "")) for k in ("school", "degree")]
    chunks += [str(c) for c in master.get("certifications") or []]

    return normalise(" \n ".join(c for c in chunks if c)), bullets


def requirement_lines(description: str) -> list[dict]:
    """Pull requirement-ish lines out of a posting, honouring section headings.

    A bullet under "Nice to have" is a nice-to-have even when its own words do
    not say so, and nothing under "Benefits" or an EEO statement is a
    requirement at all.
    """
    reqs: list[dict] = []
    section = "stated"

    for raw in description.splitlines():
        line = raw.strip()
        is_bullet = line.startswith(("-", "\u2022", "\u00b7", "*", "\u2023", "\u25cf", "\u25cb"))
        line = line.lstrip("-\u2022\u00b7*\u2023\u25cf\u25cb ").strip()
        if not line:
            continue

        # Short, bullet-free lines are headings: they set the context.
        if not is_bullet and len(line.split()) <= 7 and not line.endswith("."):
            if HEAD_SKIP.search(line):
                section = "skip"
            elif HEAD_NICE.search(line):
                section = "nice-to-have"
            elif HEAD_REQ.search(line):
                section = "stated"
            elif re.search(r"\b(?:responsibilit|what you.{0,3}ll do|the role|day to day)\b", line, re.I):
                section = "stated"
            continue

        if section == "skip":
            continue
        min_words = 2 if is_bullet else 5
        if len(line.split()) < min_words:
            continue
        if not is_bullet and not STRONG_CUES.search(line):
            continue

        if HARD_REQ.search(line):
            priority = "must-have"
        elif NICE_TO_HAVE.search(line):
            priority = "nice-to-have"
        else:
            priority = section

        years = YEARS_RE.search(line)
        reqs.append({
            "text": line,
            "priority": priority,
            "years": int(years.group(1)) if years else None,
            "terms": sorted(find_terms(line)),
        })
    return reqs


def score_bullet(bullet: dict, jd_terms: set[str]) -> tuple[int, list[str]]:
    hay = normalise(bullet["text"] + " " + " ".join(str(t) for t in bullet["tags"]))
    hits = [t for t in jd_terms if re.search(r"(?<![\w+#.])" + re.escape(t) + r"(?![\w+#])", hay)]
    # A metric in the bullet is worth something on its own: it is evidence.
    has_metric = bool(re.search(r"\d+\s*(?:%|x\b|ms\b|s\b|k\b|m\b|million|billion)", hay))
    return len(hits) * 2 + (1 if has_metric else 0), sorted(hits)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("appdir", type=Path, help="application directory containing job.json")
    ap.add_argument("--master", type=Path, default=Path("profile/master_resume.yaml"))
    ap.add_argument("--top", type=int, default=12, help="bullets to list in the report")
    args = ap.parse_args()

    import yaml

    job_file = args.appdir / "job.json"
    if not job_file.exists():
        raise SystemExit(f"{job_file} not found — run fetch_job.py first")
    job = json.loads(job_file.read_text(encoding="utf-8"))
    master = yaml.safe_load(args.master.read_text(encoding="utf-8")) or {}
    if "needs_review" in master:
        raise SystemExit(
            f"{args.master} still has a `needs_review` block. Verify the extracted "
            "resume and delete that key before tailoring against it."
        )

    description = job.get("description", "")
    jd_counts = find_terms(description)
    corpus, bullets = resume_corpus(master)

    covered, missing = {}, {}
    for term, count in jd_counts.items():
        pattern = r"(?<![\w+#.])" + re.escape(term) + r"(?![\w+#]|\.\w)"
        (covered if re.search(pattern, corpus) else missing)[term] = count

    reqs = requirement_lines(description)
    for req in reqs:
        req["covered_terms"] = [t for t in req["terms"] if t in covered]
        req["missing_terms"] = [t for t in req["terms"] if t in missing]

    jd_terms = set(jd_counts)
    ranked = []
    for bullet in bullets:
        score, hits = score_bullet(bullet, jd_terms)
        ranked.append({**bullet, "score": score, "matched_terms": hits})
    ranked.sort(key=lambda b: (-b["score"], b["id"]))

    total = len(jd_counts) or 1
    coverage = round(100 * len(covered) / total)

    gap = {
        "job_id": job.get("job_id"),
        "title": job.get("title"),
        "company": job.get("company"),
        "coverage_pct": coverage,
        "covered_terms": dict(sorted(covered.items(), key=lambda kv: -kv[1])),
        "missing_terms": dict(sorted(missing.items(), key=lambda kv: -kv[1])),
        "requirements": reqs,
        "ranked_bullets": ranked,
    }
    (args.appdir / "gap.json").write_text(json.dumps(gap, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # ---- human-readable report -------------------------------------------------
    lines = [
        f"# Gap report — {job.get('title') or '?'} @ {job.get('company') or '?'}",
        "",
        f"- Job ID: `{job.get('job_id')}`  ·  {job.get('url','')}",
        f"- Keyword coverage: **{coverage}%** ({len(covered)} of {len(jd_counts)} JD terms appear in your master resume)",
        "",
        "## Terms the posting uses that your resume already supports",
        "",
        ("  " + ", ".join(f"`{t}`" for t in list(covered)[:40])) if covered else "  (none)",
        "",
        "## Terms the posting uses that your resume does NOT support",
        "",
        "These are gaps, not a to-do list. Add a term **only** if it is already true of you —",
        "put it in the master resume, not the tailored copy.",
        "",
    ]
    lines += ([f"- `{t}` (appears {n}×)" for t, n in list(gap["missing_terms"].items())[:30]]
              or ["  (none)"])

    must = [r for r in reqs if r["priority"] == "must-have"]
    nice = [r for r in reqs if r["priority"] == "nice-to-have"]
    other = [r for r in reqs if r["priority"] == "stated"]
    lines += ["", "## Requirements as stated", "",
              "`✓` fully evidenced · `~` partly · `✗` not evidenced · "
              "`·` no keyword signal, judge it yourself", ""]
    for label, group in (("Must-have", must), ("Stated", other), ("Nice-to-have", nice)):
        if not group:
            continue
        lines.append(f"### {label} ({len(group)})")
        lines.append("")
        for req in group:
            if not req["terms"]:
                mark = "·"   # no keyword signal either way — judge it yourself
            elif not req["missing_terms"]:
                mark = "✓"
            elif req["covered_terms"]:
                mark = "~"
            else:
                mark = "✗"
            yrs = f" [{req['years']}y]" if req["years"] else ""
            lines.append(f"- {mark}{yrs} {req['text']}")
            if req["missing_terms"]:
                lines.append(f"    - not evidenced: {', '.join('`%s`' % t for t in req['missing_terms'])}")
        lines.append("")

    lines += [f"## Your {args.top} most relevant bullets for this posting", ""]
    for bullet in ranked[: args.top]:
        if bullet["score"] <= 0:
            continue
        lines.append(f"- **{bullet['id']}** (score {bullet['score']}, {bullet['parent_label']})")
        lines.append(f"    {bullet['text']}")
        if bullet["matched_terms"]:
            lines.append(f"    - matches: {', '.join('`%s`' % t for t in bullet['matched_terms'])}")
    unused = [b for b in ranked if b["score"] <= 0]
    if unused:
        lines += ["", f"_{len(unused)} bullets matched nothing in this posting: "
                      + ", ".join(b["id"] for b in unused[:15]) + "_"]

    (args.appdir / "gap_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"coverage      : {coverage}%  ({len(covered)}/{len(jd_counts)} terms)")
    print(f"requirements  : {len(must)} must-have, {len(other)} stated, {len(nice)} nice-to-have")
    print(f"top bullet    : {ranked[0]['id'] if ranked else '-'} (score {ranked[0]['score'] if ranked else 0})")
    print(f"written       : {args.appdir/'gap.json'}, {args.appdir/'gap_report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
