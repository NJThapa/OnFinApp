using OnFinApp.Api.Common;
using OnFinApp.Api.Prices;
using OnFinApp.Api.Watchlist;

namespace OnFinApp.Api.Instruments;

public sealed class InstrumentService(
    IInstrumentRepository instruments, IWatchlistRepository watchlist, PriceService prices, ILogger<InstrumentService> log)
{
    /// Tickers are stored in upper case; lookups are case-insensitive.
    public static string Normalize(string symbol) => symbol.Trim().ToUpperInvariant();

    /// Throws <see cref="InstrumentNotFoundException"/> when no instrument has this ticker.
    public async Task<Instrument> GetAsync(string symbol, CancellationToken cancel)
    {
        var normalized = Normalize(symbol);
        return await instruments.FindAsync(normalized, cancel) ?? throw new InstrumentNotFoundException(normalized);
    }

    public async Task<InstrumentPageResponse> SearchAsync(string? query, string? cursor, int limit, CancellationToken cancel)
    {
        var after = string.IsNullOrEmpty(cursor) ? InstrumentCursor.Start : InstrumentCursor.Decode(cursor);

        // One extra row tells whether there is a next page.
        var matches = await instruments.SearchAsync(Normalize(query ?? ""), after, limit + 1, cancel);
        var page = matches.Take(limit).ToList();
        var next = matches.Count > limit ? new InstrumentCursor(page[^1].Rank, page[^1].Instrument.Symbol).Encode() : null;
        log.LogDebug("Instrument search returned {Count} items, more: {More}", page.Count, next is not null);
        return new InstrumentPageResponse(page.Select(m => InstrumentResponse.From(m.Instrument)).ToList(), next);
    }

    public async Task<InstrumentDetailResponse> GetDetailAsync(string symbol, Guid userId, CancellationToken cancel)
    {
        var instrument = await GetAsync(symbol, cancel);
        var following = await watchlist.IsFollowingAsync(userId, instrument.Id, cancel);
        var quote = await prices.GetQuoteAsync(instrument.Ref, cancel);
        return new InstrumentDetailResponse(
            instrument.Symbol, instrument.Name, instrument.Type, instrument.Exchange, quote, following);
    }

    public async Task<PriceSeriesResponse> GetPricesAsync(string symbol, string range, CancellationToken cancel)
    {
        var instrument = await GetAsync(symbol, cancel);
        return new PriceSeriesResponse(instrument.Symbol, range, await prices.GetCandlesAsync(instrument.Ref, range, cancel));
    }
}
