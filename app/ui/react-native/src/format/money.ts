export const formatPrice = (value: number) =>
  `$${value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** "▲ +1.12%", "▼ -0.48%" or "0.00%". The arrow carries the direction, so it never depends on colour alone. */
export function formatChangePercent(percent: number): string {
  const arrow = percent > 0 ? '▲ +' : percent < 0 ? '▼ ' : '';
  return `${arrow}${percent.toFixed(2)}%`;
}

/** "+1.13" or "-0.48". */
export const formatChange = (change: number) => `${change > 0 ? '+' : ''}${change.toFixed(2)}`;

export type Direction = 'gain' | 'loss' | 'flat';

export const direction = (change: number): Direction => (change > 0 ? 'gain' : change < 0 ? 'loss' : 'flat');
