# LLM prompts, schemas and the mock LLM

Both APIs load the files in this folder, so differences in output come from the stack, not the prompt.

| File | Purpose |
|---|---|
| `analysis.prompt.md` | Prompt for the analysis and rating of one instrument. `{{symbol}}`, `{{name}}`, `{{type}}` and `{{exchange}}` are replaced before it is sent. |
| `analysis.schema.json` | JSON schema the answer must match. It is sent to the LLM for schema-constrained output. |
| `mock-examples/` | What the mock LLM answers for a fixed day; the unit tests of both APIs compare against these files. |

## What the APIs check

Whatever the LLM returns, an answer is rejected (and the job retried once) unless:

- `summary` and `rationale` are not blank;
- `bullPoints`, `bearPoints` and `risks` each have at least one entry, none blank;
- every key figure has a label, a value and an `asOf` date written as `YYYY-MM-DD`;
- `rating` is `BUY`, `HOLD` or `SELL` and `confidence` is `LOW`, `MEDIUM` or `HIGH`;
- there is at least one source, and every source URL starts with `http://` or `https://` and is one the
  search tool returned. A rating without sources is never stored.

## Mock LLM

Used by both APIs when `LLM_PROVIDER=mock` (the default). It makes no network calls and its text says that it
is a mock. Both APIs build the answer from the same rules, so they return the same answer.

Definitions, for an instrument with ticker `symbol`, name `name` and type `type`:

- `h`: start at 7; for each character `c` of the symbol, `h = (h * 31 + c) mod 100003` (the same hash as the
  mock price provider).
- `d`: the current calendar day in New York, in days since 1970-01-01; `date` is that day as `YYYY-MM-DD`.
- `/` below is integer division.

Rating: `(h + d) mod 3` → 0 `BUY`, 1 `HOLD`, 2 `SELL`. It changes from day to day, so the rating history shows
changes. Confidence: `(h / 7) mod 3` → 0 `LOW`, 1 `MEDIUM`, 2 `HIGH`.

Rationale: `Mock rationale: the invented figures for {symbol} point up.` for `BUY`, `... are mixed.` for `HOLD`,
`... point down.` for `SELL`.

Numbers are written from whole tenths `t` as `[-]{|t| / 10}.{|t| mod 10}`, and from whole hundredths `c` as
`{c / 100}.{c mod 100, two digits}`.

| | STOCK | ETF |
|---|---|---|
| Summary | `Mock analysis of {name} ({symbol}). This text is generated from fixtures by the mock LLM; it is not real research.` | `Mock analysis of the fund {name} ({symbol}). This text is generated from fixtures by the mock LLM; it is not real research.` |
| Bull points | `Mock bull point: {name} keeps growing revenue in its core business.` · `Mock bull point: margins leave room to keep investing.` · `Mock bull point: the balance sheet is strong.` | `Mock bull point: {symbol} spreads its holdings widely.` · `Mock bull point: the expense ratio is low for its category.` |
| Bear points | `Mock bear point: {symbol} trades at a high valuation.` · `Mock bear point: growth is slowing in the largest segment.` | `Mock bear point: the largest holdings make up a big share of {symbol}.` · `Mock bear point: the recent trend has been flat.` |
| Risks | `Mock risk: tougher competition.` · `Mock risk: regulatory changes.` | `Mock risk: a broad market decline.` · `Mock risk: concentration in a few sectors.` |
| Key figure 1 | `Revenue growth (YoY)`: tenths `h mod 400 - 100`, then `%` | `Expense ratio`: hundredths `3 + h mod 70`, then `%` |
| Key figure 2 | `Operating margin`: tenths `50 + h mod 350`, then `%` | `Top 10 holdings weight`: tenths `150 + h mod 450`, then `%` |
| Key figure 3 | `P/E ratio`: tenths `80 + h mod 420` | `1-year return`: tenths `h mod 400 - 100`, then `%` |

Every key figure is as of `date`.

Sources, both returned by the mock search tool and cited by the answer, in this order:

| Title | URL |
|---|---|
| `{name} investor relations (mock)` | `https://example.com/mock/{symbol}/investor-relations` |
| `{symbol} latest quarterly report (mock)` | `https://example.com/mock/{symbol}/quarterly-report` |
| `Market commentary on {symbol} (mock)` | `https://example.com/mock/{symbol}/market-commentary` |

Usage figures: input tokens = prompt length in characters `/ 4`; output tokens = `400 + h mod 200`. Model: `mock`.

Two settings help with testing:

- `LLM_MOCK_DELAY_MS` (default 1500): how long the mock waits before answering, so the "generating" state is
  visible.
- `LLM_MOCK_FAIL_SYMBOLS` (default empty): comma-separated tickers for which the mock fails, to exercise the
  retry and the failed state.
