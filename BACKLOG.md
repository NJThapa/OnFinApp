# OnFinApp backlog — draft for review

Draft of 12 November 2026. Nothing here exists in GitHub yet. Once you approve it, each story below becomes one
GitHub issue and each epic becomes one milestone.

- Epics 0 and 1 have full stories.
- Epics 2 to 7 have a list of intended stories only. They are written in full when their epic is next, so the
  provider trial can still change them.
- One feature is split into an API story and a UI story. An example of the split is at the end.

## Labels

| Label | Meaning |
|---|---|
| `type:api` | Behaviour behind the contract, including AI generation |
| `type:ui` | One screen or flow in one UI |
| `type:research` | A question to answer before building |
| `type:chore` | CI, tooling, documentation |
| `ui:app` | The reader app (iOS, Android, web) |
| `ui:admin` | The admin console (web) |
| `size:S`, `size:M`, `size:L` | Rough size; no estimates |
| `legal` | Touches legal or licensing limits (SPEC.md section 14) |

## Definitions of done

These go at the foot of every issue of that type. They are the same text as SPEC.md section 13.

**API story**

- [ ] The contract is updated first and its version raised
- [ ] Contract tests cover every acceptance criterion and pass against every API implementation
- [ ] Unit tests cover the rules in every implementation
- [ ] Schema changes are a new migration file
- [ ] Errors use the problem format; logs carry the correlation ID and no tokens, emails or names
- [ ] Shared files (prompts, schemas, fixtures) are read by every implementation
- [ ] README and SPEC.md are updated
- [ ] CI is green

An AI story is an API story plus:

- [ ] The mock and its shared examples are updated, and every implementation still returns the same answer
- [ ] The change is run on the trial's ticker set with the real vendor; quality and cost per run are recorded

**UI story**

- [ ] It uses the API only through the client generated from the contract
- [ ] Loading, empty and error states are handled
- [ ] It works on every platform this UI targets (reader app: iOS, Android, web; admin console: web)
- [ ] Component tests cover the acceptance criteria
- [ ] Typecheck and lint pass
- [ ] It uses the UI's theme (reader app: Paper)
- [ ] No sample data remains on the screen
- [ ] CI is green

**Research story**

- [ ] A written finding, with its sources and the date they were checked
- [ ] A recommendation
- [ ] The decision is recorded in SPEC.md section 2
- [ ] Follow-up stories are created or updated
- [ ] Trial code stays out of the product

**Chore**

- [ ] It works from a clean clone by following the README
- [ ] Documentation is updated
- [ ] CI is green

---

# Epic 0 — Setup

Scope: backlog in GitHub Issues, CI on GitHub Actions, UI test harness, documentation catch-up.

### E0-1 · Set up the backlog in GitHub Issues

`type:chore` `size:S`

**Story.** As the owner, I want the backlog in GitHub Issues with one milestone per epic and a label for each
story type, so that I can see what is next and tell API work from UI work.

**Acceptance criteria**

- [ ] The labels in this document exist.
- [ ] Milestones exist for epics 0 to 7, each with its scope from SPEC.md section 13 and its list of intended
      stories.
- [ ] The stories of epics 0 and 1 exist as issues, each with one type label, one size label and its milestone.
- [ ] Four issue templates (API, UI, research, chore) are in the repository, each ending with its definition
      of done.
- [ ] The README says where the backlog is and how a story is written.

**Out of scope.** A project board.

**Note.** The product owner does this once the repository grants write access.

### E0-2 · CI runs every unit test suite and the app's static checks

`type:chore` `size:M`

**Story.** As the developer, I want every push and pull request to run the unit tests of each API
implementation and the app's typecheck and lint, so that a break in either implementation is caught before it
lands.

**Acceptance criteria**

- [ ] Every push to `main` and every pull request starts a CI run on GitHub Actions.
- [ ] The run executes the unit tests of each API implementation, and the app's typecheck and lint, as
      separate jobs, so one failure does not hide another.
- [ ] A failing test or check fails the run, and the run's summary names the job that failed.
- [ ] Dependencies are cached between runs.
- [ ] The README shows the CI status and how to run the same checks locally.

**Out of scope.** Contract tests (E0-3), app component tests (E0-4), building mobile binaries, deployment.

**Note.** The app has a `typecheck` script but no lint script yet; adding one is part of this story.

### E0-3 · CI runs the contract tests against every API implementation

`type:chore` `size:M`

**Story.** As the developer, I want CI to run the shared contract tests against each API implementation, so
that the implementations are shown to behave the same after every change.

**Acceptance criteria**

- [ ] CI starts the database, the migrations, the identity provider and each API implementation with the mock
      providers, and runs the contract suite once per implementation.
- [ ] Each implementation is tested against a fresh database, so the result does not depend on which ran
      first. (Today a stored analysis from one run changes what the next run sees.)
- [ ] The run is green only when the suite passes against every implementation, and the summary shows the
      result per implementation.
- [ ] The telemetry stack is not started in CI.
- [ ] When a run fails, the logs of the APIs are kept with the run.
- [ ] A typical run finishes within 10 minutes, which keeps CI well inside the free monthly allowance if the
      repository is private.

**Out of scope.** Tests against real providers.

**Depends on.** E0-2.

### E0-4 · The reader app has component tests for its main screens

`type:chore` `ui:app` `size:M`

**Story.** As the developer, I want component tests for the reader app's main screens, so that UI stories can
meet their definition of done and a broken screen is caught in CI.

**Acceptance criteria**

- [ ] One command runs the app's component tests without a device or simulator.
- [ ] API calls are replaced at the boundary of the generated client, so the tests need no running API.
- [ ] Tests cover the sign-in screen (signed out; sign-in in progress).
- [ ] Tests cover the watchlist (loading; empty; items with price and rating; error).
- [ ] Tests cover the stock page (header and chart ranges; analysis ready, generating and failed).
- [ ] Tests cover the profile (name, email, sign out).
- [ ] A test confirms the News screen shows its sample-data notice. It is replaced in epic 3.
- [ ] The tests run in CI as their own job, and the README says how to run them.

**Out of scope.** End-to-end tests on devices; visual comparison tests.

**Depends on.** E0-2.

### E0-5 · Documentation matches the code

`type:chore` `size:S`

**Story.** As the developer, I want the documents in the repository to match what is built, so that I, and any
AI session reading the repository, work from correct information.

**Acceptance criteria**

- [ ] SPEC.md version 2 is on `main`.
- [ ] Every file path named in `diagrams/login-flow.md` exists. Today it names files that moved in
      milestone 2.
- [ ] The README refers to epics, not to the old milestone numbers.

### E0-6 · Decide where the web app keeps sign-in tokens

`type:research` `size:S`

**Story.** As the owner, I want a decision on how the web version of the app stores sign-in tokens, so that the
specification and the code agree before the app is public.

**Background.** Version 1 of the specification said tokens are kept in memory on web. The app keeps them in
`localStorage`, so a reader stays signed in after a reload. Any script running on the page can read
`localStorage`. Memory-only storage cannot be read that way, but it signs the reader out on every reload
unless the identity provider's session is reused.

**Acceptance criteria**

- [ ] The finding compares `localStorage`, memory only, and memory with silent sign-in through the identity
      provider's session, on security and on what the reader experiences.
- [ ] The owner picks one, and SPEC.md sections 10 and 15 are updated.
- [ ] If the choice differs from what is built, a UI story is created.

---

# Epic 1 — Provider trial

Scope: choose the LLM vendor first, then check the price provider. The epic ends with a vendor, a monthly
budget and the agent design.

### E1-1 · Shortlist LLM vendors from published information

`type:research` `size:M` `legal`

**Story.** As the owner, I want a comparison of LLM vendors on capability, price and terms, so that only
vendors that can serve this product, at a cost I accept, go into the hands-on trial.

**Acceptance criteria**

- [ ] At least three vendors are compared.
- [ ] For each vendor the comparison covers capability:
  - web search that returns the sources it used, so citations can be checked;
  - schema-constrained JSON output;
  - tool use that supports multi-step agents with our own tools;
  - whether it can be used from every API implementation.
- [ ] For each vendor the comparison covers cost: price per token in and out, per web search, any other
      charge, and the rate limits on the cheapest tier.
- [ ] For each vendor the comparison covers terms: whether AI-written financial commentary for consumers is
      allowed without review by a person, what disclosure is required, and what the vendor keeps of our
      prompts and outputs.
- [ ] Each vendor has an estimated cost per analysis, with the assumptions written down (tokens in and out,
      number of searches).
- [ ] Every fact has a link to its source and the date it was checked.
- [ ] The finding recommends two vendors for the hands-on trial.

**Out of scope.** Making API calls (E1-3).

### E1-2 · Define the ticker set and the score sheet for the trial

`type:research` `size:S`

**Story.** As the owner, I want a fixed set of tickers and a score sheet before any vendor is tried, so that
vendors and agent depths are compared on the same terms.

**Acceptance criteria**

- [ ] About ten tickers are chosen, with the reason for each. The set includes large well-covered stocks, a
      small stock with little coverage, two ETFs, a stock with major news in the last week, and a share-class
      ticker such as `BRK.B`.
- [ ] The score sheet rates one analysis on these points:
  - every source opens and is relevant;
  - every key figure matches its source and its date;
  - no claim lacks a source;
  - the rating follows from the points made;
  - thin evidence leads to Hold with Low confidence, as the prompt requires;
  - an ETF analysis covers holdings, cost and trend;
  - the text is plain English at the length asked for.
- [ ] The sheet also records, per run: valid against the schema or not, tokens in and out, number of
      searches, cost and duration.
- [ ] Scoring is done by a second model using the sheet. The owner reads the scores of three analyses and
      confirms they are believable, or the sheet is adjusted.

**Out of scope.** Running the vendors (E1-3).

### E1-3 · Hands-on trial of the shortlisted LLM vendors

`type:research` `size:L`

**Story.** As the owner, I want each shortlisted vendor run on the ticker set, as a single call and as an
agent, so that I can choose a vendor and an agent depth from measured quality and cost.

**Acceptance criteria**

- [ ] The owner sets a spending limit for the trial before it starts, and the trial stops when it is reached.
- [ ] For each vendor and each ticker, the current analysis prompt and schema are run with web search in two
      ways: as one call, and as an agent that plans, searches, drafts and then verifies its claims against
      its sources.
- [ ] Every run is scored with the sheet from E1-2, and its tokens, searches, cost and duration are recorded.
- [ ] Each ticker is run twice in each way with no news in between, and the share of runs where the rating
      differs is reported.
- [ ] One batch of market news is generated per vendor, and its cost and the number of usable items are
      recorded.
- [ ] The finding states the recommended vendor, the recommended agent depth, and the cost per analysis.
- [ ] The finding states the monthly cost of refreshing 10, 50 and 200 followed instruments each trading day,
      and how often market news can be generated within the same budget.
- [ ] API keys come from the environment and are never committed. Trial code is kept apart from the product.

**Out of scope.** Adding the vendor to the APIs (epic 2).

**Depends on.** E1-1, E1-2.

### E1-4 · Check the price provider's free tier and licence

`type:research` `size:M` `legal`

**Story.** As the owner, I want to know what the first-choice price provider's free tier really delivers and
permits, so that the price chart is built on a provider that works now and can be licensed for the public
later.

**Acceptance criteria**

- [ ] With a free key, real calls confirm or rule out: the list of US stocks and ETFs; the latest quote; price
      history for the 1D, 1W, 1M, 1Y and 5Y chart ranges; the rate limits; share-class tickers such as
      `BRK.B`.
- [ ] The terms are read for: whether prices and charts may be shown to the public on the free tier; which
      paid plan allows it and what it costs; whether quotes are real-time or delayed; what attribution is
      required.
- [ ] If history or public display is missing, two other providers are checked in the same way.
- [ ] The finding recommends a provider for private use now and says what changes for public release, with
      links and the date checked.

**Out of scope.** Adding the provider to the APIs (epic 2).

### E1-5 · Decide the agent design

`type:research` `size:M`

**Story.** As the architect, I want the agent design decided from the trial's results, so that epic 2 can be
written as stories and built the same way in every API implementation.

**Acceptance criteria**

- [ ] The decision records the steps an agent takes and the tools it may use.
- [ ] It records the limits on one run (steps, tokens, time, cost) and how depth is set.
- [ ] It records what is stored for each run and how a run appears in the existing traces.
- [ ] It states whether agents are built in every API implementation, and how the implementations are kept
      comparable (shared prompts, shared examples, the same mock behaviour).
- [ ] It states how the three checks work: source verification, rating stability and performance tracking.
- [ ] SPEC.md section 6 is updated and open question 4 is closed.
- [ ] The stories of epic 2 are written.

**Depends on.** E1-3.

### E1-6 · Set the budget and the limits

`type:research` `size:S`

**Story.** As the owner, I want a monthly budget and the limits that keep spend inside it, so that real
providers can be switched on without surprises.

**Acceptance criteria**

- [ ] From the trial's numbers the owner sets the monthly ceiling for the LLM and market data.
- [ ] The owner sets the daily generation cap, how often market news is generated, and the default rating
      horizon, and confirms or changes the refresh limits.
- [ ] The values are recorded in SPEC.md sections 2 and 6 as the defaults for epic 2.
- [ ] Open questions 3 and 8 are closed.

**Depends on.** E1-3, E1-4.

---

# Epics 2 to 7 — intended stories

One line each. They become full stories when their epic is next.

## Epic 2 — Real providers and agent-based analysis

| Type | Story |
|---|---|
| API | Real LLM vendor behind the existing interface |
| API | Real price provider behind the existing interface |
| API | Agent-based generation with a depth setting |
| API | Source verification before an analysis is stored |
| API | A rating changes only when new information is cited |
| API | A rating states the horizon it applies to |
| UI (app) | The rating card and rating history show the horizon |
| API | Every generation records its cost in money and its prompt version |
| API | Daily generation cap and monthly budget; stored data is served with its age when a cap is reached |
| UI (app) | The stock page says when it shows older data because a cap was reached |
| API | Ratings are scored against the market at the end of their horizon |

## Epic 3 — Feed

| Type | Story |
|---|---|
| API | Market news is generated, sourced, deduplicated and stored |
| API | Stock news is generated with the analysis |
| API | Feed with Market and My stocks tabs and mixed card types |
| API | Rating-change cards |
| API | Spotlight cards, and how instruments are chosen for them |
| UI (app) | Feed screen with both tabs, replacing the sample data |
| UI (app) | Rating-change and spotlight cards |
| API | Bookmarks |
| UI (app) | Bookmark action and Bookmarks screen |
| API | News for one instrument |
| UI (app) | News tab on the stock page |

## Epic 4 — Admin console

| Type | Story |
|---|---|
| Research | Decide the console's stack, login and access to data |
| API | AI spend by day, model, kind of generation and API implementation |
| API | Generation jobs list, with retry of a failed job |
| API | Settings stored in the database, read by every API implementation, with change history |
| UI (admin) | Operator sign-in |
| UI (admin) | Spend screen |
| UI (admin) | Jobs screen |
| UI (admin) | Settings screen |
| UI (admin) | Rating performance screen |

## Epic 5 — Scheduling and notifications

| Type | Story |
|---|---|
| API | Scheduled refresh of followed instruments |
| API | Scheduled market news |
| API | A rating change creates notifications for followers |
| API | Important news creates notifications for followers |
| API | Notification list and mark as read |
| API | Notification settings |
| UI (app) | Notification list |
| UI (app) | Notification settings on the Profile tab |

## Epic 6 — Comparison and polish

| Type | Story |
|---|---|
| API | Compare two instruments, with an AI-written verdict |
| UI (app) | Compare screen |
| UI (app) | Switch between API implementations in developer builds |
| UI (app) | Review of error, empty and offline states, and accessibility |

## Epic 7 — Public release

| Type | Story |
|---|---|
| Research | Legal review of the rating wording and the countries offered |
| Research | Market-data and news licences for public display |
| Chore | Privacy policy and terms of use |
| API + UI (app) | Account deletion |
| Chore | Open sign-up at the identity provider |
| Chore | Microsoft Entra External ID as the identity provider |
| Chore | Azure deployment and pipeline |
| Chore | Store accounts and release builds |
| API + UI (app) | Push notifications to devices |
| API + UI (admin) | Switch off a rating or feed card |

---

# Example: one feature split into an API story and a UI story

This pair shows how your rule works in practice: two issues, each with its own definition of done, and no
stack named in either. The wording is final only after the provider trial.

### A rating states the horizon it applies to

`type:api` `size:M` · Epic 2

**Story.** As a reader, I want every rating to state the period it applies to, so that I know whether Buy
means this month or this year.

**Acceptance criteria**

- [ ] The service has a rating horizon setting in whole months, from 1 to 12. A value outside that range is
      refused.
- [ ] The horizon is given to the model with the request, and the rating it returns applies to that horizon.
- [ ] Every new rating stores the horizon it was generated for.
- [ ] The analysis and every entry of the rating history return the horizon in months.
- [ ] A rating stored before this change returns no horizon, not a guessed one.
- [ ] Changing the setting leaves stored ratings as they are and applies from the next generation.
- [ ] The mock states the horizon in its rationale, identically in every implementation.

**Contract.** `Analysis` and `RatingEntry` gain `horizonMonths`.

**Done when.** API definition of done, with the two AI conditions.

### The rating card and rating history show the horizon

`type:ui` `ui:app` `size:S` · Epic 2

**Story.** As a reader, I want to see the horizon next to each rating, so that I read the rating correctly at
a glance.

**Acceptance criteria**

- [ ] The rating card shows the horizon in words next to the rating, for example "Buy · 12-month view".
- [ ] Each row of the rating history shows its own horizon, so ratings made under different settings are not
      confused.
- [ ] A rating without a horizon shows no horizon text.
- [ ] The rating label on the watchlist is unchanged.

**Depends on.** The API story above.

**Done when.** UI definition of done.
