// Shared by every contract test file.
import assert from 'node:assert/strict';

export const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const TOKEN_URL =
  process.env.OIDC_TOKEN_URL ?? 'http://localhost:8082/realms/onfinapp/protocol/openid-connect/token';
const CLIENT_ID = process.env.OIDC_TEST_CLIENT_ID ?? 'onfinapp-tests';
const USERNAME = process.env.TEST_USERNAME ?? 'testuser';
const PASSWORD = process.env.TEST_PASSWORD ?? 'testpass';

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export async function fetchToken(clientId = CLIENT_ID) {
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: clientId,
      username: USERNAME,
      password: PASSWORD,
      scope: 'openid',
    }),
  });
  const text = await res.text();
  assert.equal(res.status, 200, `token request failed: ${text}`);
  return JSON.parse(text).access_token;
}

export const get = (path, token) =>
  fetch(API + path, { headers: token ? { authorization: `Bearer ${token}` } : {} });

export async function assertUnauthorizedProblem(res) {
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

export const send = (method, path, token) =>
  fetch(API + path, { method, headers: token ? { authorization: `Bearer ${token}` } : {} });

export async function assertProblem(res, status, title) {
  assert.equal(res.status, status);
  assert.match(res.headers.get('content-type') ?? '', /^application\/problem\+json/);
  const body = await res.json();
  assert.equal(body.type, 'about:blank');
  assert.equal(body.title, title);
  assert.equal(body.status, status);
  assert.equal(typeof body.detail, 'string');
  assert.equal(body.instance, new URL(res.url).pathname);
}

/**
 * Asks for the analysis of an instrument until it is no longer being generated, and returns the last body.
 * The mock LLM answers within a few seconds.
 */
export async function waitForAnalysis(symbol, token, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const res = await get(`/api/v1/instruments/${symbol}/analysis`, token);
    const body = await res.json();
    assert.equal(res.status, body.status === 'GENERATING' ? 202 : 200, `status code for ${body.status}`);
    if (body.status !== 'GENERATING') return body;
    assert.ok(Date.now() < deadline, `the ${symbol} analysis was still generating after ${timeoutMs} ms`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}
