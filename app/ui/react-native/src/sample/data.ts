// SAMPLE DATA ONLY. The news here is an invented placeholder so the News screen can be built before its
// endpoints exist. The shape follows SPEC.md and will be replaced by generated API types.

export type NewsItem = {
  id: string;
  headline: string;
  summary: string;
  sourceName: string;
  url: string;
  publishedAt: string;
  scope: 'MARKET' | 'STOCK';
  tickers: string[];
};


const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const item = (
  n: number,
  scope: NewsItem['scope'],
  minutes: number,
  tickers: string[],
  headline: string,
  summary: string,
): NewsItem => ({
  id: `sample-${n}`,
  headline,
  summary,
  sourceName: 'Sample Source',
  url: `https://example.com/sample-news/${n}`,
  publishedAt: minutesAgo(minutes),
  scope,
  tickers,
});

export const news: NewsItem[] = [
  item(1, 'MARKET', 12, ['SPY', 'QQQ'], 'Sample headline: major indexes open higher',
    'Placeholder summary describing a broad market move. Real items will carry two or three sentences generated from cited sources.'),
  item(2, 'STOCK', 34, ['AAPL'], 'Sample headline: Apple product update',
    'Placeholder summary for a company-specific story. It appears on the My stocks tab for anyone following AAPL.'),
  item(3, 'MARKET', 58, [], 'Sample headline: central bank commentary in focus',
    'Placeholder summary for a market-wide story with no related tickers, to show how an item without chips looks.'),
  item(4, 'STOCK', 95, ['NVDA', 'MSFT'], 'Sample headline: chip demand and cloud spending',
    'Placeholder summary for a story related to two instruments. Each ticker chip will later open that stock page.'),
  item(5, 'STOCK', 140, ['TSLA'], 'Sample headline: Tesla delivery figures',
    'Placeholder summary for a company-specific story about an instrument that is not followed by default.'),
  item(6, 'MARKET', 215, ['VTI'], 'Sample headline: sector rotation continues',
    'Placeholder summary describing money moving between sectors, with one related fund.'),
  item(7, 'STOCK', 320, ['MSFT'], 'Sample headline: Microsoft enterprise announcement',
    'Placeholder summary for a second story about a followed instrument, to give the My stocks tab more than one row.'),
  item(8, 'STOCK', 610, ['SPY'], 'Sample headline: fund flows into index ETFs',
    'Placeholder summary for an ETF-related story. ETF analysis covers holdings focus, cost and trend.'),
  item(9, 'MARKET', 1500, [], 'Sample headline: the week ahead',
    'Placeholder summary for an older story, to show how items from the previous day are dated.'),
];
