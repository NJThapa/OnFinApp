// Contract tests: one suite, run against either API.
//   API_BASE_URL=http://localhost:8080 npm test   (Java)
//   API_BASE_URL=http://localhost:8081 npm test   (.NET)
// Needs the local stack running (deployment/local/docker-compose.yml). No dependencies: Node 20+.
import assert from 'node:assert/strict';
import { before, describe, test } from 'node:test';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const TOKEN_URL =
  process.env.OIDC_TOKEN_URL ?? 'http://localhost:8082/realms/onfinapp/protocol/openid-connect/token';
const CLIENT_ID = process.env.OIDC_TEST_CLIENT_ID ?? 'onfinapp-tests';
const USERNAME = process.env.TEST_USERNAME ?? 'testuser';
const PASSWORD = process.env.TEST_PASSWORD ?? 'testpass';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

async function fetchToken() {
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: CLIENT_ID,
      username: USERNAME,
      password: PASSWORD,
      scope: 'openid',
    }),
  });
  const text = await res.text();
  assert.equal(res.status, 200, `token request failed: ${text}`);
  return JSON.parse(text).access_token;
}

const get = (path, token) =>
  fetch(API + path, { headers: token ? { authorization: `Bearer ${token}` } : {} });

async function assertUnauthorizedProblem(res) {
  assert.equal(res.status, 401);
  assert.match(res.headers.get('content-type') ?? '', /^application\/problem\+json/);
  assert.match(res.headers.get('www-authenticate') ?? '', /^Bearer/);
  assert.deepEqual(await res.json(), {
    type: 'about:blank',
    title: 'Unauthorized',
    status: 401,
    detail: 'A valid bearer token is required.',
  });
}

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
