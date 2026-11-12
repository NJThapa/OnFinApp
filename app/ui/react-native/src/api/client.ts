import { config } from '../config';
import type { components } from './schema';

export type User = components['schemas']['User'];
export type Health = components['schemas']['Health'];
export type Instrument = components['schemas']['Instrument'];
export type InstrumentPage = components['schemas']['InstrumentPage'];
export type InstrumentDetail = components['schemas']['InstrumentDetail'];
export type Quote = components['schemas']['Quote'];
export type PriceRange = components['schemas']['PriceRange'];
export type PriceSeries = components['schemas']['PriceSeries'];
export type PricePoint = components['schemas']['PricePoint'];
export type Rating = components['schemas']['Rating'];
export type Watchlist = components['schemas']['Watchlist'];
export type WatchlistItem = components['schemas']['WatchlistItem'];
export type Analysis = components['schemas']['Analysis'];
export type AnalysisStatus = components['schemas']['AnalysisStatus'];
export type Confidence = components['schemas']['Confidence'];
export type RatingEntry = components['schemas']['RatingEntry'];
export type RatingPage = components['schemas']['RatingPage'];

/** Request and response header that ties a call to the API's log lines. */
export const CORRELATION_HEADER = 'X-Correlation-ID';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    /** Quote this when reporting a problem: the API logs every line of the request under it. */
    public readonly correlationId: string | null = null,
  ) {
    super(message);
  }
}

/** A fetch that attaches the bearer token and handles refresh; supplied by the auth layer. */
export type AuthorizedFetch = (path: string, init?: RequestInit) => Promise<Response>;

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const problem = await res.json().catch(() => null);
    throw new ApiError(
      res.status,
      problem?.detail ?? problem?.title ?? `Request failed (${res.status})`,
      res.headers.get(CORRELATION_HEADER),
    );
  }
  return res.json() as Promise<T>;
}

export async function getMe(authorizedFetch: AuthorizedFetch): Promise<User> {
  return readJson<User>(await authorizedFetch('/api/v1/me'));
}

async function expectNoContent(res: Response): Promise<void> {
  if (!res.ok) await readJson(res); // throws the ApiError
}

const symbolPath = (symbol: string) => encodeURIComponent(symbol);

export async function searchInstruments(
  authorizedFetch: AuthorizedFetch,
  query: string,
  cursor?: string | null,
): Promise<InstrumentPage> {
  const params = new URLSearchParams({ query });
  if (cursor) params.set('cursor', cursor);
  return readJson<InstrumentPage>(await authorizedFetch(`/api/v1/instruments?${params}`));
}

export async function getInstrument(authorizedFetch: AuthorizedFetch, symbol: string): Promise<InstrumentDetail> {
  return readJson<InstrumentDetail>(await authorizedFetch(`/api/v1/instruments/${symbolPath(symbol)}`));
}

export async function getPrices(
  authorizedFetch: AuthorizedFetch,
  symbol: string,
  range: PriceRange,
): Promise<PriceSeries> {
  return readJson<PriceSeries>(await authorizedFetch(`/api/v1/instruments/${symbolPath(symbol)}/prices?range=${range}`));
}

/** The stored analysis. While `status` is GENERATING, ask again until it changes. */
export async function getAnalysis(authorizedFetch: AuthorizedFetch, symbol: string): Promise<AnalysisStatus> {
  return readJson<AnalysisStatus>(await authorizedFetch(`/api/v1/instruments/${symbolPath(symbol)}/analysis`));
}

/**
 * Asks for a new analysis. `status` is GENERATING when one was queued, READY when the request was ignored
 * because the current analysis is recent. Throws an ApiError with status 429 at the user's refresh limit.
 */
export async function refreshAnalysis(authorizedFetch: AuthorizedFetch, symbol: string): Promise<AnalysisStatus> {
  return readJson<AnalysisStatus>(
    await authorizedFetch(`/api/v1/instruments/${symbolPath(symbol)}/analysis/refresh`, { method: 'POST' }),
  );
}

/** Rating history, newest first. */
export async function getRatings(
  authorizedFetch: AuthorizedFetch,
  symbol: string,
  cursor?: string | null,
): Promise<RatingPage> {
  const params = new URLSearchParams({ limit: '10' });
  if (cursor) params.set('cursor', cursor);
  return readJson<RatingPage>(await authorizedFetch(`/api/v1/instruments/${symbolPath(symbol)}/ratings?${params}`));
}

export async function getWatchlist(authorizedFetch: AuthorizedFetch): Promise<Watchlist> {
  return readJson<Watchlist>(await authorizedFetch('/api/v1/watchlist'));
}

export async function followInstrument(authorizedFetch: AuthorizedFetch, symbol: string): Promise<void> {
  await expectNoContent(await authorizedFetch(`/api/v1/watchlist/${symbolPath(symbol)}`, { method: 'PUT' }));
}

export async function unfollowInstrument(authorizedFetch: AuthorizedFetch, symbol: string): Promise<void> {
  await expectNoContent(await authorizedFetch(`/api/v1/watchlist/${symbolPath(symbol)}`, { method: 'DELETE' }));
}

export async function getHealth(): Promise<Health> {
  return readJson<Health>(await fetch(`${config.apiBaseUrl}/api/health`));
}
