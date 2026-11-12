# Mock price provider

Used by both APIs when `PRICE_PROVIDER=mock` (the default). `instruments.json` is the instrument list; its
`basePrice` values are invented and are not real prices.

Both APIs generate prices from the same formulas, so they return the same numbers.

Definitions, for a symbol with base price `base` and a day `d` counted in days since 1970-01-01.
"Today" is the current calendar day in New York (`America/New_York`), the same day boundary the price cache uses:

- `h`: start at 7; for each character `c` of the symbol, `h = (h * 31 + c) mod 100003`.
- `phase = h mod 365`.
- `noise(d) = (((h * 131 + d * 7919) mod 2001) - 1000) / 1000`.
- `close(d) = round2(base * (1 + 0.15 * sin(2π(d + phase) / 365) + 0.04 * sin(2π(d + phase) / 29) + 0.01 * noise(d)))`.
- Trading days are Monday to Friday. The last trading day is today, or the previous Friday at a weekend.

Quote: `price = close(last trading day)`, `previousClose = close(trading day before it)`,
`change = round2(price - previousClose)`, `changePercent = round2(change / previousClose * 100)`.

Chart points:

| Range | Days | Points per day | Time of each point (UTC) |
|---|---|---|---|
| 1D | last trading day | 79 | 14:30, then every 5 minutes |
| 1W | last 5 trading days | 7 | 14:30, then every 60 minutes |
| 1M | trading days in the last 30 days | 1 | 21:00 |
| 1Y | trading days in the last 365 days | 1 | 21:00 |
| 5Y | Fridays in the last 1825 days | 1 | 21:00 |

A daily point is `close(d)`. Point `k` of `n` within a day, with `prev = close(trading day before d)`:

`round2(prev + (close(d) - prev) * k / (n - 1) + base * 0.004 * sin(0.9k + phase) * sin(πk / (n - 1)))`
