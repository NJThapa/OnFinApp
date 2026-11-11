import { config } from '../config';
import type { components } from './schema';

export type User = components['schemas']['User'];
export type Health = components['schemas']['Health'];

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** A fetch that attaches the bearer token and handles refresh; supplied by the auth layer. */
export type AuthorizedFetch = (path: string, init?: RequestInit) => Promise<Response>;

async function readJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const problem = await res.json().catch(() => null);
    throw new ApiError(res.status, problem?.detail ?? problem?.title ?? `Request failed (${res.status})`);
  }
  return res.json() as Promise<T>;
}

export async function getMe(authorizedFetch: AuthorizedFetch): Promise<User> {
  return readJson<User>(await authorizedFetch('/api/v1/me'));
}

export async function getHealth(): Promise<Health> {
  return readJson<Health>(await fetch(`${config.apiBaseUrl}/api/health`));
}
