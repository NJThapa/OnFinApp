# OnFinApp backlog

The backlog is in [GitHub Issues](https://github.com/NJThapa/OnFinApp/issues), with one
[milestone](https://github.com/NJThapa/OnFinApp/milestones) per epic. This file holds what is not an issue yet:
the stories intended for later epics, and an example of how one feature is split into an API story and a UI
story.

How stories are written, the story types and their definitions of done are in [SPEC.md](SPEC.md), section 13.
New stories start from the issue templates in `.github/ISSUE_TEMPLATE`.

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

## Epics 0 and 1 — issues

| Story | Issue |
|---|---|
| E0-1 · Set up the backlog in GitHub Issues | [#1](https://github.com/NJThapa/OnFinApp/issues/1) |
| E0-2 · CI runs every unit test suite and the app's static checks | [#2](https://github.com/NJThapa/OnFinApp/issues/2) |
| E0-3 · CI runs the contract tests against every API implementation | [#3](https://github.com/NJThapa/OnFinApp/issues/3) |
| E0-4 · The reader app has component tests for its main screens | [#4](https://github.com/NJThapa/OnFinApp/issues/4) |
| E0-5 · Documentation matches the code | [#5](https://github.com/NJThapa/OnFinApp/issues/5) |
| E0-6 · Decide where the web app keeps sign-in tokens | [#6](https://github.com/NJThapa/OnFinApp/issues/6) |
| E1-1 · Shortlist LLM vendors from published information | [#7](https://github.com/NJThapa/OnFinApp/issues/7) |
| E1-2 · Define the ticker set and the score sheet for the trial | [#8](https://github.com/NJThapa/OnFinApp/issues/8) |
| E1-3 · Hands-on trial of the shortlisted LLM vendors | [#9](https://github.com/NJThapa/OnFinApp/issues/9) |
| E1-4 · Check the price provider's free tier and licence | [#10](https://github.com/NJThapa/OnFinApp/issues/10) |
| E1-5 · Decide the agent design | [#11](https://github.com/NJThapa/OnFinApp/issues/11) |
| E1-6 · Set the budget and the limits | [#12](https://github.com/NJThapa/OnFinApp/issues/12) |

## Epics 2 to 7 — intended stories

One line each. They become full stories, as issues, when their epic is next.

### Epic 2 — Real providers and agent-based analysis

| Type | Story |
|---|---|
| API | Real LLM vendor behind the existing interface |
| API | Real price provider behind the existing interface |
| API | Analysis, rating, rating history and refresh are available only for instruments the reader follows; nothing is generated for others |
| UI (app) | The stock page of an instrument that is not followed shows price, chart and news, with a prompt to follow |
| API | Agent-based generation with a depth setting |
| API | Source verification before an analysis is stored |
| API | A rating changes only when new information is cited |
| API | A rating states the horizon it applies to |
| UI (app) | The rating card and rating history show the horizon |
| API | Every generation records its cost in money and its prompt version |
| API | Daily generation cap and monthly budget; stored data is served with its age when a cap is reached |
| UI (app) | The stock page says when it shows older data because a cap was reached |
| API | Ratings are scored against the market at the end of their horizon |

### Epic 3 — Feed

| Type | Story |
|---|---|
| API | Market news is generated, sourced, deduplicated and stored |
| API | Stock news is generated with the analysis |
| API | Feed with Market and My stocks tabs and mixed card types |
| API | Rating-change cards, on My stocks only |
| API | Spotlight cards without a rating, and how instruments are chosen for them |
| UI (app) | Feed screen with both tabs, replacing the sample data |
| UI (app) | Rating-change cards on My stocks; spotlight cards on Market, with no rating shown |
| API | Bookmarks |
| UI (app) | Bookmark action and Bookmarks screen |
| API | News for one instrument |
| UI (app) | News tab on the stock page |

### Epic 4 — Admin console

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

### Epic 5 — Scheduling and notifications

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

### Epic 6 — Comparison and polish

| Type | Story |
|---|---|
| API | Compare two followed instruments, with an AI-written verdict |
| UI (app) | Compare screen |
| UI (app) | Switch between API implementations in developer builds |
| UI (app) | Review of error, empty and offline states, and accessibility |

### Epic 7 — Public release

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

## Example: one feature split into an API story and a UI story

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
