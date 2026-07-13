# Issue tracker: GitHub

Issues and implementation tickets for this repository live in the
`Belfast-byte/interview-guide` GitHub Issues tracker.

Because this checkout also has the upstream `Snailclimb/interview-guide` remote, every
GitHub CLI command must explicitly pass `-R Belfast-byte/interview-guide`. Do not rely on
automatic remote inference.

## Conventions

- Create an issue with `gh issue create -R Belfast-byte/interview-guide`.
- Read an issue and its discussion with
  `gh issue view <number> -R Belfast-byte/interview-guide --comments`.
- List issues with `gh issue list -R Belfast-byte/interview-guide` and request JSON fields
  when automation needs labels, bodies, or comments.
- Comment, label, or close an issue with the corresponding `gh issue` command and the same
  explicit repository selector.
- Publish tickets in dependency order so blocking relationships can reference real issue
  numbers.
- Prefer GitHub native issue dependencies. If they are unavailable, keep a `Blocked by`
  section in the issue body with explicit issue references.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## When a skill says "publish to the issue tracker"

Create a GitHub issue in `Belfast-byte/interview-guide` and apply the configured
`ready-for-agent` label when the ticket is agent-ready.

## When a skill says "fetch the relevant ticket"

Read the full issue body, labels, and comments from `Belfast-byte/interview-guide`.
