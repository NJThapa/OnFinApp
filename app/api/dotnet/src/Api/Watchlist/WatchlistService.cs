using Microsoft.Extensions.Options;
using OnFinApp.Api.Common;
using OnFinApp.Api.Instruments;
using OnFinApp.Api.Prices;

namespace OnFinApp.Api.Watchlist;

/// Bound from WATCHLIST_LIMIT.
public sealed class WatchlistOptions
{
    /// Maximum number of instruments one user can follow.
    public int Limit { get; set; } = 50;
}

/// Rating is null until analyses exist (milestone 3).
public sealed record WatchlistItemResponse(string Symbol, string Name, string Type, string Exchange, Quote? Quote, string? Rating);

public sealed record WatchlistResponse(IReadOnlyList<WatchlistItemResponse> Items, int Limit);

public sealed class WatchlistService(
    IWatchlistRepository watchlist,
    IInstrumentRepository instruments,
    PriceService prices,
    IOptions<WatchlistOptions> options,
    ILogger<WatchlistService> log)
{
    private int Limit => options.Value.Limit;

    public async Task<WatchlistResponse> GetAsync(Guid userId, CancellationToken cancel)
    {
        var followed = await watchlist.ListAsync(userId, cancel);
        var quotes = await prices.GetQuotesAsync(followed.Select(i => i.Ref).ToList(), cancel);
        var items = followed
            .Select(i => new WatchlistItemResponse(i.Symbol, i.Name, i.Type, i.Exchange, quotes.GetValueOrDefault(i.Id), Rating: null))
            .ToList();
        return new WatchlistResponse(items, Limit);
    }

    /// Idempotent. Throws <see cref="WatchlistLimitException"/> when the user is at the limit.
    public async Task FollowAsync(Guid userId, string symbol, CancellationToken cancel)
    {
        var instrument = await RequireAsync(symbol, cancel);
        var outcome = await watchlist.FollowAsync(userId, instrument.Id, Limit, cancel);
        if (outcome == FollowOutcome.LimitReached)
        {
            log.LogInformation("User {UserId} is at the watchlist limit of {Limit}; {Symbol} not followed", userId, Limit, instrument.Symbol);
            throw new WatchlistLimitException(Limit);
        }
        if (outcome == FollowOutcome.Followed) log.LogInformation("User {UserId} followed {Symbol}", userId, instrument.Symbol);
    }

    /// Idempotent.
    public async Task UnfollowAsync(Guid userId, string symbol, CancellationToken cancel)
    {
        var instrument = await RequireAsync(symbol, cancel);
        await watchlist.UnfollowAsync(userId, instrument.Id, cancel);
        log.LogInformation("User {UserId} unfollowed {Symbol}", userId, instrument.Symbol);
    }

    private async Task<Instrument> RequireAsync(string symbol, CancellationToken cancel)
    {
        var normalized = InstrumentService.Normalize(symbol);
        return await instruments.FindAsync(normalized, cancel) ?? throw new InstrumentNotFoundException(normalized);
    }
}
