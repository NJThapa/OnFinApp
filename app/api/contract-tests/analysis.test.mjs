// Contract tests for analyses and ratings (milestone 3).
// They assume the mock LLM (the default), whose answers are specified in app/api/shared/llm/README.md.
// Analyses are shared by both APIs and kept for 24 hours, so a run may find them already generated.
import assert from 'node:assert/strict';
import { before, describe, test } from 'node:test';

import { API, assertProblem, assertUnauthorizedProblem, fetchToken, get, send, waitForAnalysis } from './helpers.mjs';

// Whole seconds, so both APIs write times the same way.
const ISO_SECONDS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const DAY_MS = 24 * 60 * 60 * 1000;
const sorted = (keys) => [...keys].sort();

function assertAnalysis(analysis, symbol) {
  assert.deepEqual(sorted(Object.keys(analysis)), [
    'bearPoints', 'bullPoints', 'confidence', 'expiresAt', 'generatedAt', 'generatedBy', 'keyFigures',
    'rating', 'rationale', 'risks', 'sources', 'summary',
  ]);
  assert.ok(analysis.summary.includes(`(${symbol})`));
  for (const list of ['bullPoints', 'bearPoints', 'risks']) {
    assert.ok(analysis[list].length > 0, list);
    for (const entry of analysis[list]) assert.ok(typeof entry === 'string' && entry.length > 0);
  }
  assert.equal(analysis.keyFigures.length, 3);
  for (const figure of analysis.keyFigures) {
    assert.deepEqual(sorted(Object.keys(figure)), ['asOf', 'label', 'value']);
    assert.match(figure.asOf, /^\d{4}-\d{2}-\d{2}$/);
    assert.match(figure.value, /^-?\d+\.\d+%?$/);
  }
  assert.deepEqual(analysis.sources, [
    { title: analysis.sources[0].title, url: `https://example.com/mock/${symbol}/investor-relations` },
    { title: `${symbol} latest quarterly report (mock)`, url: `https://example.com/mock/${symbol}/quarterly-report` },
    { title: `Market commentary on ${symbol} (mock)`, url: `https://example.com/mock/${symbol}/market-commentary` },
  ]);
  assert.ok(['BUY', 'HOLD', 'SELL'].includes(analysis.rating));
  assert.ok(['LOW', 'MEDIUM', 'HIGH'].includes(analysis.confidence));
  const ending = { BUY: 'point up.', HOLD: 'are mixed.', SELL: 'point down.' }[analysis.rating];
  assert.equal(analysis.rationale, `Mock rationale: the invented figures for ${symbol} ${ending}`);
  assert.ok(['java', 'dotnet'].includes(analysis.generatedBy));
  assert.match(analysis.generatedAt, ISO_SECONDS);
  assert.match(analysis.expiresAt, ISO_SECONDS);
  assert.equal(Date.parse(analysis.expiresAt) - Date.parse(analysis.generatedAt), DAY_MS);
}

describe(`analysis @ ${API}`, () => {
  let token;
  before(async () => {
    token = await fetchToken();
  });

  describe('GET /api/v1/instruments/{symbol}/analysis', () => {
    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/instruments/AAPL/analysis'));
    });

    test('unknown ticker is 404', async () => {
      await assertProblem(await get('/api/v1/instruments/NOPE/analysis', token), 404, 'Not Found');
    });

    test('answers 202 while generating and 200 with the analysis once it is ready', async () => {
      const first = await get('/api/v1/instruments/AAPL/analysis', token);
      assert.match(first.headers.get('content-type') ?? '', /^application\/json/);
      const body = await first.json();
      assert.deepEqual(sorted(Object.keys(body)), ['analysis', 'status', 'symbol']);
      assert.equal(body.symbol, 'AAPL');
      // 202 on the first request after the analysis expired, 200 while it is valid.
      assert.ok(
        (first.status === 202 && body.status === 'GENERATING') || (first.status === 200 && body.status === 'READY'),
        `${first.status} ${body.status}`,
      );
      if (body.status === 'READY') assertAnalysis(body.analysis, 'AAPL');

      const ready = await waitForAnalysis('AAPL', token);
      assert.equal(ready.status, 'READY');
      assertAnalysis(ready.analysis, 'AAPL');
      assert.ok(ready.analysis.summary.startsWith('Mock analysis of Apple Inc. (AAPL).'));
      assert.equal(ready.analysis.keyFigures[0].label, 'Revenue growth (YoY)');
    });

    test('a valid analysis is served unchanged', async () => {
      const first = await waitForAnalysis('AAPL', token);
      const second = await waitForAnalysis('AAPL', token);
      assert.deepEqual(second, first);
    });

    test('ticker is case-insensitive and may contain a dot', async () => {
      const { symbol, status, analysis } = await waitForAnalysis('brk.b', token);
      assert.equal(symbol, 'BRK.B');
      assert.equal(status, 'READY');
      assertAnalysis(analysis, 'BRK.B');
    });

    test('an ETF is analysed as a fund', async () => {
      const { status, analysis } = await waitForAnalysis('SPY', token);
      assert.equal(status, 'READY');
      assertAnalysis(analysis, 'SPY');
      assert.ok(analysis.summary.startsWith('Mock analysis of the fund SPDR S&P 500 ETF Trust (SPY).'));
      assert.deepEqual(analysis.keyFigures.map((f) => f.label), ['Expense ratio', 'Top 10 holdings weight', '1-year return']);
    });

    test('requests for the same instrument at the same moment produce one analysis', async () => {
      const bodies = await Promise.all(Array.from({ length: 6 }, () => waitForAnalysis('QQQ', token)));
      for (const body of bodies) assert.deepEqual(body, bodies[0]);
    });
  });

  describe('POST /api/v1/instruments/{symbol}/analysis/refresh', () => {
    test('requires a token', async () => {
      await assertUnauthorizedProblem(await send('POST', '/api/v1/instruments/AAPL/analysis/refresh'));
    });

    test('unknown ticker is 404', async () => {
      await assertProblem(await send('POST', '/api/v1/instruments/NOPE/analysis/refresh', token), 404, 'Not Found');
    });

    test('is ignored while the analysis is younger than the minimum age, or joins a running generation', async () => {
      const current = await waitForAnalysis('MSFT', token);
      const res = await send('POST', '/api/v1/instruments/MSFT/analysis/refresh', token);
      const body = await res.json();
      const ageMs = Date.now() - Date.parse(current.analysis.generatedAt);
      if (ageMs < 55 * 60 * 1000) {
        // Younger than the default minimum age of one hour: nothing is queued.
        assert.equal(res.status, 200);
        assert.deepEqual(body, current);
      } else {
        // Old enough to refresh: queued (202), unless this user is at the limit (429).
        assert.ok([202, 429].includes(res.status), `${res.status}`);
        if (res.status === 202) {
          assert.equal(body.status, 'GENERATING');
          assert.deepEqual(body.analysis, current.analysis); // the current analysis stays visible meanwhile
          const next = await waitForAnalysis('MSFT', token);
          assert.equal(next.status, 'READY');
          assert.ok(Date.parse(next.analysis.generatedAt) > Date.parse(current.analysis.generatedAt));
        }
      }
    });

    test('GET is not a refresh', async () => {
      await assertProblem(await get('/api/v1/instruments/AAPL/analysis/refresh', token), 405, 'Method Not Allowed');
    });
  });

  describe('GET /api/v1/instruments/{symbol}/ratings', () => {
    const ratings = async (query = '') => {
      const res = await get(`/api/v1/instruments/AAPL/ratings${query}`, token);
      assert.equal(res.status, 200);
      return res.json();
    };

    test('requires a token', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/instruments/AAPL/ratings'));
    });

    test('unknown ticker is 404', async () => {
      await assertProblem(await get('/api/v1/instruments/NOPE/ratings', token), 404, 'Not Found');
    });

    test('lists ratings newest first; the newest is the current analysis', async () => {
      const { analysis } = await waitForAnalysis('AAPL', token);
      const page = await ratings();
      assert.deepEqual(sorted(Object.keys(page)), ['items', 'nextCursor']);
      assert.ok(page.items.length >= 1);
      assert.deepEqual(page.items[0], {
        rating: analysis.rating,
        confidence: analysis.confidence,
        rationale: analysis.rationale,
        createdAt: analysis.generatedAt,
      });
      const times = page.items.map((i) => Date.parse(i.createdAt));
      assert.deepEqual(times, [...times].sort((a, b) => b - a));
    });

    test('pages with limit and cursor', async () => {
      const all = (await ratings('?limit=50')).items;
      const walked = [];
      let cursor = null;
      do {
        const page = await ratings(`?limit=1${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
        assert.ok(page.items.length <= 1);
        walked.push(...page.items);
        cursor = page.nextCursor;
      } while (cursor && walked.length < 50);
      assert.deepEqual(walked, all.slice(0, walked.length));
      assert.equal(walked.length, Math.min(all.length, 50));
    });

    test('invalid limit or cursor is 400', async () => {
      for (const query of ['?limit=0', '?limit=51', '?limit=abc', '?cursor=nonsense!', '?cursor=QUFQTA']) {
        await assertProblem(await get(`/api/v1/instruments/AAPL/ratings${query}`, token), 400, 'Bad Request');
      }
    });
  });
});
