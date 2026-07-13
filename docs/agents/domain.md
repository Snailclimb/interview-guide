# Domain Docs

This repository uses a single domain context.

## Before exploring or implementing

- Read the root `CONTEXT.md` for the domain glossary.
- Read the ADRs in `docs/adr/` that affect the area being changed.
- Use the glossary's preferred terms in tickets, tests, APIs, and implementation names.
- If a proposed change conflicts with an ADR, surface the conflict explicitly instead of
  silently overriding the decision.

If a referenced domain document is absent from a checkout, continue with the available
repository context rather than inventing replacement terminology.

## Layout

```text
/
├── CONTEXT.md
└── docs/
    └── adr/
```

`CONTEXT.md` defines the ubiquitous language. `docs/adr/` records durable architecture
decisions and their consequences.
