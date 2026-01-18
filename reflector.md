# CampusPlug — Execution Reflector (Agent Guardrails)

Purpose: keep implementation aligned to `planning.md` by forcing an explicit “end-of-phase” check.

Rules
- Update this file at the end of every implementation phase in `planning.md` (Phase 1, Phase 2, ...).
- For each phase, compare what was actually built vs the phase’s **Deliverables** and **Testing criteria**.
- If anything deviates from `planning.md`, record:
  - what changed
  - why it changed
  - whether `planning.md` was updated to match (required)
  - any follow-up tasks needed

How to use
- Append one new “Phase Reflection” section after completing a phase.
- Keep entries short and factual. Prefer links to files and commands.

---

## Phase Reflections

### Phase X — <name> (YYYY-MM-DD)

**Scope (from planning.md)**
- Goal:
- Deliverables targeted:
- Testing criteria targeted:

**Work completed (what changed)**
- Code:
  - Files changed/added:
  - Key classes/symbols added/modified:
- Database:
  - Flyway migrations added/changed:
  - Notes (PostGIS, constraints, indexes):
- API surface:
  - Endpoints added/changed:
  - Breaking changes (if any):
- Config/Infra:
  - Env vars added/changed:
  - Docker/Render changes:

**Verification performed**
- Local:
  - Commands run:
  - Results:
- Render/prod:
  - Health check:
  - Logs sanity:

**Checklist vs planning.md**
- Deliverables met: YES/NO (explain briefly)
- Testing criteria met: YES/NO (explain briefly)
- Deviations introduced: NONE / <list>

**Deviations (if any)**
- What deviated:
- Why:
- planning.md updated to reflect this: YES/NO
- Follow-ups required:

**Risks / next actions**
- Risks:
- Next:

---

## Rolling “Off-Track” Signals

Mark these as you notice them (helps catch drift early):
- [ ] Added endpoints not specified in `planning.md` without updating the plan
- [ ] Changed auth/session behavior (JWT, revocation, domains) without updating the plan
- [ ] Added schema changes without Flyway migration
- [ ] Added non-feature-based packages (violates `com.campusplug.api.*` feature slicing)
- [ ] Broke Render boot/health and didn’t restore before proceeding
- [ ] Introduced new external services or paid dependencies without updating the plan
