// Contract tests: one suite, run against either API.
//   API_BASE_URL=http://localhost:8080 npm test   (Java)
//   API_BASE_URL=http://localhost:8081 npm test   (.NET)
// Needs the local stack running (deployment/local/docker-compose.yml). No dependencies: Node 20+.
import assert from 'node:assert/strict';
import { before, describe, test } from 'node:test';

import { API, assertUnauthorizedProblem, fetchToken, get, UUID } from './helpers.mjs';

describe(`contract @ ${API}`, () => {
  let token;
  before(async () => {
    token = await fetchToken();
  });

  describe('GET /api/health', () => {
    test('is public and reports the database as up', async () => {
      const res = await get('/api/health');
      assert.equal(res.status, 200);
      const body = await res.json();
      assert.deepEqual(Object.keys(body).sort(), ['db', 'stack', 'status']);
      assert.ok(['java', 'dotnet'].includes(body.stack));
      assert.equal(body.status, 'ok');
      assert.equal(body.db, 'up');
    });
  });

  describe('GET /api/v1/me', () => {
    test('without a token returns 401 problem+json', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/me'));
    });

    test('with a malformed token returns 401 problem+json', async () => {
      await assertUnauthorizedProblem(await get('/api/v1/me', 'not-a-jwt'));
    });

    test('with a tampered token returns 401 problem+json', async () => {
      const [header, payload, signature] = token.split('.');
      const flipped = (signature[0] === 'A' ? 'B' : 'A') + signature.slice(1);
      await assertUnauthorizedProblem(await get('/api/v1/me', `${header}.${payload}.${flipped}`));
    });

    test('with a valid token returns the user', async () => {
      const res = await get('/api/v1/me', token);
      assert.equal(res.status, 200);
      assert.match(res.headers.get('content-type') ?? '', /^application\/json/);
      const body = await res.json();
      assert.deepEqual(Object.keys(body).sort(), ['email', 'id', 'name']);
      assert.match(body.id, UUID);
      assert.equal(body.email, 'testuser@onfinapp.local');
      assert.equal(body.name, 'Test User');
    });

    test('returns the same user id on every call', async () => {
      const first = await (await get('/api/v1/me', token)).json();
      const second = await (await get('/api/v1/me', token)).json();
      assert.equal(first.id, second.id);
    });
  });

  describe('token validation', () => {
    test('a token issued for another audience returns 401 problem+json', async () => {
      // Same realm, same user, valid signature, but from a client whose tokens are not meant for this API.
      const other = await fetchToken('admin-cli');
      await assertUnauthorizedProblem(await get('/api/v1/me', other));
    });

    test('a token with an altered payload returns 401 problem+json', async () => {
      const [header, payload, signature] = token.split('.');
      const claims = JSON.parse(Buffer.from(payload, 'base64url').toString());
      const forged = Buffer.from(JSON.stringify({ ...claims, sub: 'someone-else' })).toString('base64url');
      await assertUnauthorizedProblem(await get('/api/v1/me', `${header}.${forged}.${signature}`));
    });

    test('an unsigned token returns 401 problem+json', async () => {
      const payload = token.split('.')[1];
      const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
      await assertUnauthorizedProblem(await get('/api/v1/me', `${header}.${payload}.`));
    });
  });

  describe('X-Correlation-ID', () => {
    const UUID_ANY = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
    const withId = (path, id, auth = token) =>
      fetch(API + path, { headers: { ...(auth ? { authorization: `Bearer ${auth}` } : {}), 'x-correlation-id': id } });

    test('is echoed on success', async () => {
      const res = await withId('/api/v1/me', 'contract-test.42_A');
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('x-correlation-id'), 'contract-test.42_A');
    });

    test('is echoed on errors, including 401 and 404', async () => {
      assert.equal((await withId('/api/v1/me', 'no-token-1', null)).headers.get('x-correlation-id'), 'no-token-1');
      assert.equal((await withId('/api/v1/instruments/NOPE', 'not-found-1')).headers.get('x-correlation-id'), 'not-found-1');
    });

    test('is generated when the request has none', async () => {
      assert.match((await get('/api/v1/me', token)).headers.get('x-correlation-id') ?? '', UUID_ANY);
      assert.match((await get('/api/health')).headers.get('x-correlation-id') ?? '', UUID_ANY);
    });

    test('is replaced when it is not a plain token of at most 64 characters', async () => {
      for (const bad of ['has space', '{"a":1}', 'a'.repeat(65)]) {
        assert.match((await withId('/api/v1/me', bad)).headers.get('x-correlation-id') ?? '', UUID_ANY, bad);
      }
    });

    test('can be sent and read by the web app (CORS)', async () => {
      const preflight = await fetch(API + '/api/v1/me', {
        method: 'OPTIONS',
        headers: {
          origin: 'http://localhost:8090',
          'access-control-request-method': 'GET',
          'access-control-request-headers': 'authorization,x-correlation-id',
        },
      });
      assert.ok(preflight.ok, `expected 2xx, got ${preflight.status}`);
      assert.match(preflight.headers.get('access-control-allow-headers') ?? '', /x-correlation-id/i);

      const res = await fetch(API + '/api/v1/me', {
        headers: { authorization: `Bearer ${token}`, origin: 'http://localhost:8090' },
      });
      assert.match(res.headers.get('access-control-expose-headers') ?? '', /x-correlation-id/i);
    });
  });

  describe('CORS', () => {
    const preflight = (origin) =>
      fetch(API + '/api/v1/me', {
        method: 'OPTIONS',
        headers: {
          origin,
          'access-control-request-method': 'GET',
          'access-control-request-headers': 'authorization',
        },
      });

    test('preflight from the web app origin is allowed without a token', async () => {
      const res = await preflight('http://localhost:8090');
      assert.ok(res.ok, `expected 2xx, got ${res.status}`);
      assert.equal(res.headers.get('access-control-allow-origin'), 'http://localhost:8090');
      assert.match(res.headers.get('access-control-allow-headers') ?? '', /authorization/i);
    });

    test('preflight from an unknown origin is not allowed', async () => {
      const res = await preflight('http://evil.example');
      assert.equal(res.headers.get('access-control-allow-origin'), null);
    });

    test('responses to the web app origin carry the allow-origin header', async () => {
      const res = await fetch(API + '/api/v1/me', {
        headers: { authorization: `Bearer ${token}`, origin: 'http://localhost:8090' },
      });
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('access-control-allow-origin'), 'http://localhost:8090');
    });
  });
});
