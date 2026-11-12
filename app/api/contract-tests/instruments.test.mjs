// Contract tests for instruments, prices and the watchlist (milestone 2).
// They assume the mock price provider (the default), whose instrument list is app/api/shared/mock/instruments.json.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, test } from 'node:test';

import { API, assertProblem, assertUnauthorizedProblem, fetchToken, get, send } from './helpers.mjs';

const FIXTURES = JSON.parse(readFileSync(new URL('../shared/mock/instruments.json', import.meta.url), 'utf8'));
const ALL_SYMBOLS = FIXTURES.map((f) => f.symbol).sort();
const ISO_UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;
const sorted = (keys) => [...keys].sort();

function assertInstrument(item) {
  assert.deepEqual(sorted(Object.keys(item)), ['exchange', 'name', 'symbol', 'type']);
  assert.ok(['STOCK', 'ETF'].includes(item.type));
}

function assertQuote(quote) {
  assert.deepEqual(sorted(Object.keys(quote)), ['asOf', 'change', 'changePercent', 'previousClose', 'price']);
  for (const key of ['price', 'change', 'changePercent', 'previousClose']) assert.equal(typeof quote[key], 'number');
  assert.ok(quote.price > 0 && quote.previousClose > 0);
  assert.ok(Math.abs(quote.price - quote.previousClose - quote.change) < 0.011);
  assert.ok(Math.abs((quote.change / quote.previousClose) * 100 - quote.changePercent) < 0.011);
  assert.match(quote.asOf, ISO_UTC);
}

describe(`instruments @ ${API}`, () => {
  let token;
  const json = async (path) => {
    const res = await get(path, token);
    assert.equal(res.status, 200, `GET ${path}`);
    assert.match(res.headers.get('content-type') ?? '', /^application\/json/);
    return res.json();
  };
  const watchlistSymbols = async () => (await json('/api/v1/watchlist')).items.map((i) => i.symbol);
  const clearWatchlist = async () => {
    for (const symbol of await watchlistSymbols()) {
      assert.equal((await send('DELETE', `/api/v1/watchlist/${symbol}`, token)).status, 204);
    }
  };

  before(async () => {
    token = await fetchToken();
    await clearWatchlist();
  });
  after(clearWatchlist);

  describe('GET /api/v1/instruments', () => {
    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/instruments'));
    });

    test('without a query pages through every instrument in ticker order', async () => {
      const symbols = [];
      let cursor = null;
      let pages = 0;
      do {
        const page = await json(`/api/v1/instruments?limit=25${cursor ? `&cursor=${cursor}` : ''}`);
        assert.deepEqual(sorted(Object.keys(page)), ['items', 'nextCursor']);
        page.items.forEach(assertInstrument);
        symbols.push(...page.items.map((i) => i.symbol));
        cursor = page.nextCursor;
        pages++;
      } while (cursor !== null && pages < 10);
      assert.deepEqual(symbols, ALL_SYMBOLS);
      assert.equal(pages, Math.ceil(ALL_SYMBOLS.length / 25));
    });

    test('defaults to 20 items per page', async () => {
      const page = await json('/api/v1/instruments');
      assert.equal(page.items.length, 20);
      assert.equal(typeof page.nextCursor, 'string');
    });

    test('finds a ticker regardless of case', async () => {
      const page = await json('/api/v1/instruments?query=aapl');
      assert.deepEqual(page.items, [{ symbol: 'AAPL', name: 'Apple Inc.', type: 'STOCK', exchange: 'NASDAQ' }]);
      assert.equal(page.nextCursor, null);
    });

    test('finds by part of the name', async () => {
      const page = await json('/api/v1/instruments?query=vanguard');
      assert.deepEqual(page.items.map((i) => i.symbol), ['BND', 'VEA', 'VOO', 'VTI']);
    });

    test('orders exact ticker, then ticker prefix, then name matches', async () => {
      const page = await json('/api/v1/instruments?query=v&limit=50');
      const symbols = page.items.map((i) => i.symbol);
      assert.deepEqual(symbols.slice(0, 5), ['V', 'VEA', 'VOO', 'VTI', 'VZ']);
      const expected = FIXTURES.filter((f) => f.symbol.startsWith('V') || f.name.toUpperCase().includes('V')).length;
      assert.equal(symbols.length, expected);
      assert.deepEqual(symbols.slice(5), sorted(symbols.slice(5)));
    });

    test('pages through ranked results without gaps or repeats', async () => {
      const whole = (await json('/api/v1/instruments?query=v&limit=50')).items.map((i) => i.symbol);
      const paged = [];
      let cursor = null;
      do {
        const page = await json(`/api/v1/instruments?query=v&limit=3${cursor ? `&cursor=${cursor}` : ''}`);
        paged.push(...page.items.map((i) => i.symbol));
        cursor = page.nextCursor;
      } while (cursor !== null && paged.length < 100);
      assert.deepEqual(paged, whole);
    });

    test('treats % and _ in the query as plain characters', async () => {
      assert.deepEqual((await json('/api/v1/instruments?query=%25')).items, []);
      assert.deepEqual((await json('/api/v1/instruments?query=_')).items, []);
    });

    test('returns an empty page when nothing matches', async () => {
      assert.deepEqual(await json('/api/v1/instruments?query=zzzz'), { items: [], nextCursor: null });
    });

    test('rejects invalid parameters with 400 problem+json', async () => {
      for (const query of ['limit=0', 'limit=51', 'limit=abc', 'limit=-1', 'cursor=not-a-cursor', `query=${'a'.repeat(51)}`]) {
        await assertProblem(await get(`/api/v1/instruments?${query}`, token), 400, 'Bad Request');
      }
    });
  });

  describe('GET /api/v1/instruments/{symbol}', () => {
    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/instruments/AAPL'));
    });

    test('returns the instrument with a quote', async () => {
      const body = await json('/api/v1/instruments/AAPL');
      assert.deepEqual(sorted(Object.keys(body)), ['exchange', 'following', 'name', 'quote', 'symbol', 'type']);
      assert.equal(body.symbol, 'AAPL');
      assert.equal(body.name, 'Apple Inc.');
      assert.equal(body.type, 'STOCK');
      assert.equal(body.exchange, 'NASDAQ');
      assert.equal(body.following, false);
      assertQuote(body.quote);
    });

    test('accepts lower case and tickers with a dot', async () => {
      assert.equal((await json('/api/v1/instruments/spy')).symbol, 'SPY');
      assert.equal((await json('/api/v1/instruments/brk.b')).symbol, 'BRK.B');
    });

    test('returns 404 problem+json for an unknown ticker', async () => {
      await assertProblem(await get('/api/v1/instruments/NOPE', token), 404, 'Not Found');
    });
  });

  describe('GET /api/v1/instruments/{symbol}/prices', () => {
    // Expected number of points from the mock provider; daily ranges depend on the calendar.
    const counts = { '1D': [79, 79], '1W': [35, 35], '1M': [20, 23], '1Y': [259, 263], '5Y': [259, 262] };

    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/instruments/AAPL/prices'));
    });

    for (const [range, [min, max]] of Object.entries(counts)) {
      test(`${range} returns closing prices, oldest first`, async () => {
        const body = await json(`/api/v1/instruments/MSFT/prices?range=${range}`);
        assert.deepEqual(sorted(Object.keys(body)), ['points', 'range', 'symbol']);
        assert.equal(body.symbol, 'MSFT');
        assert.equal(body.range, range);
        assert.ok(body.points.length >= min && body.points.length <= max, `${body.points.length} points`);
        let previous = 0;
        for (const point of body.points) {
          assert.deepEqual(sorted(Object.keys(point)), ['close', 'time']);
          assert.match(point.time, ISO_UTC);
          assert.ok(Date.parse(point.time) > previous, 'times ascend');
          assert.ok(point.close > 0);
          previous = Date.parse(point.time);
        }
        assert.ok(previous <= Date.now() + 24 * 3600 * 1000);
      });
    }

    test('defaults to 1M', async () => {
      assert.equal((await json('/api/v1/instruments/MSFT/prices')).range, '1M');
    });

    test('the latest daily close matches the quote', async () => {
      const { quote } = await json('/api/v1/instruments/KO');
      const { points } = await json('/api/v1/instruments/KO/prices?range=1M');
      assert.equal(points.at(-1).close, quote.price);
      assert.equal(points.at(-2).close, quote.previousClose);
    });

    test('rejects an unknown range with 400 problem+json', async () => {
      await assertProblem(await get('/api/v1/instruments/MSFT/prices?range=2D', token), 400, 'Bad Request');
    });

    test('returns 404 problem+json for an unknown ticker', async () => {
      await assertProblem(await get('/api/v1/instruments/NOPE/prices', token), 404, 'Not Found');
    });
  });

  describe('watchlist', () => {
    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/watchlist'));
      await assertUnauthorizedProblem(await send('PUT', '/api/v1/watchlist/AAPL'));
      await assertUnauthorizedProblem(await send('DELETE', '/api/v1/watchlist/AAPL'));
    });

    test('starts empty and reports the limit', async () => {
      assert.deepEqual(await json('/api/v1/watchlist'), { items: [], limit: 50 });
    });

    test('follow, list in the order followed, unfollow', async () => {
      for (const symbol of ['MSFT', 'aapl', 'BRK.B']) {
        const res = await send('PUT', `/api/v1/watchlist/${symbol}`, token);
        assert.equal(res.status, 204);
        assert.equal(await res.text(), '');
      }
      const { items } = await json('/api/v1/watchlist');
      assert.deepEqual(items.map((i) => i.symbol), ['MSFT', 'AAPL', 'BRK.B']);
      for (const item of items) {
        assert.deepEqual(sorted(Object.keys(item)), ['exchange', 'name', 'quote', 'rating', 'symbol', 'type']);
        assertQuote(item.quote);
        assert.equal(item.rating, null);
      }
      assert.equal((await json('/api/v1/instruments/AAPL')).following, true);

      assert.equal((await send('DELETE', '/api/v1/watchlist/AAPL', token)).status, 204);
      assert.deepEqual(await watchlistSymbols(), ['MSFT', 'BRK.B']);
      assert.equal((await json('/api/v1/instruments/AAPL')).following, false);
    });

    test('follow and unfollow are idempotent', async () => {
      await clearWatchlist();
      assert.equal((await send('PUT', '/api/v1/watchlist/SPY', token)).status, 204);
      assert.equal((await send('PUT', '/api/v1/watchlist/SPY', token)).status, 204);
      assert.deepEqual(await watchlistSymbols(), ['SPY']);
      assert.equal((await send('DELETE', '/api/v1/watchlist/SPY', token)).status, 204);
      assert.equal((await send('DELETE', '/api/v1/watchlist/SPY', token)).status, 204);
      assert.deepEqual(await watchlistSymbols(), []);
    });

    test('returns 404 problem+json for an unknown ticker', async () => {
      await assertProblem(await send('PUT', '/api/v1/watchlist/NOPE', token), 404, 'Not Found');
      await assertProblem(await send('DELETE', '/api/v1/watchlist/NOPE', token), 404, 'Not Found');
    });

    test('refuses the 51st instrument with 409 problem+json', async () => {
      await clearWatchlist();
      const first50 = ALL_SYMBOLS.slice(0, 50);
      for (const symbol of first50) {
        assert.equal((await send('PUT', `/api/v1/watchlist/${symbol}`, token)).status, 204);
      }
      await assertProblem(await send('PUT', `/api/v1/watchlist/${ALL_SYMBOLS[50]}`, token), 409, 'Conflict');
      // Following one that is already followed still succeeds at the limit.
      assert.equal((await send('PUT', `/api/v1/watchlist/${first50[0]}`, token)).status, 204);

      const { items } = await json('/api/v1/watchlist');
      assert.equal(items.length, 50);
      items.forEach((item) => assertQuote(item.quote));
    });
  });

  describe('CORS', () => {
    test('preflight for PUT from the web app origin is allowed', async () => {
      const res = await fetch(API + '/api/v1/watchlist/AAPL', {
        method: 'OPTIONS',
        headers: {
          origin: 'http://localhost:8090',
          'access-control-request-method': 'PUT',
          'access-control-request-headers': 'authorization',
        },
      });
      assert.ok(res.ok, `expected 2xx, got ${res.status}`);
      assert.equal(res.headers.get('access-control-allow-origin'), 'http://localhost:8090');
      assert.match(res.headers.get('access-control-allow-methods') ?? '', /PUT/);
    });
  });
});
