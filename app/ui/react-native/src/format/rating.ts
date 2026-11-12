import type { Confidence, Rating } from '../api/client';

export const RATING_LABEL: Record<Rating, string> = { BUY: 'Buy', HOLD: 'Hold', SELL: 'Sell' };

export const CONFIDENCE_LABEL: Record<Confidence, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

/** e.g. "12 Nov 2026, 18:02" in the device's language and time zone. */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** e.g. "12 Nov 2026". A date without a time (YYYY-MM-DD) is shown as written, not shifted by time zone. */
export function formatDate(iso: string): string {
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(iso);
  return new Date(dateOnly ? `${iso}T00:00:00` : iso).toLocaleDateString(undefined, { dateStyle: 'medium' });
}
