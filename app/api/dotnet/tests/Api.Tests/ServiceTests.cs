using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using OnFinApp.Api.Common;
using OnFinApp.Api.Instruments;
using OnFinApp.Api.Prices;
using OnFinApp.Api.Watchlist;

namespace OnFinApp.Api.Tests;

public class InstrumentCursorTests
{
    [Fact]
    public void Round_trips()
    {
        var cursor = new InstrumentCursor(1, "BRK.B");

        Assert.Equal(cursor, InstrumentCursor.Decode(cursor.Encode()));
    }

    [Fact]
    public void Encodes_as_base64url_of_rank_colon_symbol()
    {
        // Both APIs must produce the same cursor: base64url("2:AAPL") without padding.
        Assert.Equal("MjpBQVBM", new InstrumentCursor(2, "AAPL").Encode());
    }

    [Theory]
    [InlineData("not-a-cursor")]
    [InlineData("")]
    [InlineData("!!!")]
    [InlineData("QUFQTA")] // "AAPL": no rank
    [InlineData("MzpBQVBM")] // "3:AAPL": rank out of range
    [InlineData("Mjo")] // "2:": no symbol
    public void Rejects_anything_else(string cursor)
    {
        Assert.Throws<InvalidCursorException>(() => InstrumentCursor.Decode(cursor));
    }
}

public class PriceServiceTests
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly Quote Stored = new(5, 0, 0, 5, new DateTime(2026, 11, 10, 17, 0, 0, DateTimeKind.Utc));

    private readonly FakePriceCache cache = new();
    private readonly FakePriceProvider provider = new();
    private readonly InstrumentRef apple = Sample.Apple.Ref;

    private PriceService Service() => new(cache, provider, NullLogger<PriceService>.Instance);

    private void Store(string key, object payload, bool fresh) =>
        cache.Entries[(apple.Id, key)] = new CachedPayload(JsonSerializer.Serialize(payload, Json), fresh);

    [Fact]
    public async Task Fresh_quote_comes_from_the_cache_without_calling_the_provider()
    {
        Store(IPriceCacheRepository.QuoteKey, Stored, fresh: true);

        Assert.Equal(Stored, await Service().GetQuoteAsync(apple, default));
        Assert.Equal(0, provider.Calls);
    }

    [Fact]
    public async Task Missing_quote_is_fetched_and_cached()
    {
        Assert.Equal(FakePriceProvider.Quote, await Service().GetQuoteAsync(apple, default));
        Assert.Equal([(apple.Id, IPriceCacheRepository.QuoteKey)], cache.Writes);
    }

    [Fact]
    public async Task Stale_quote_is_refreshed()
    {
        Store(IPriceCacheRepository.QuoteKey, Stored, fresh: false);

        Assert.Equal(FakePriceProvider.Quote, await Service().GetQuoteAsync(apple, default));
    }

    [Fact]
    public async Task Stale_quote_is_served_when_the_provider_is_down()
    {
        Store(IPriceCacheRepository.QuoteKey, Stored, fresh: false);
        provider.Down = true;

        Assert.Equal(Stored, await Service().GetQuoteAsync(apple, default));
    }

    [Fact]
    public async Task No_quote_when_the_provider_is_down_and_nothing_is_cached()
    {
        provider.Down = true;

        Assert.Null(await Service().GetQuoteAsync(apple, default));
    }

    [Fact]
    public async Task Quotes_for_several_instruments_skip_the_ones_that_fail()
    {
        Store(IPriceCacheRepository.QuoteKey, Stored, fresh: true);
        provider.Down = true;

        var quotes = await Service().GetQuotesAsync([apple, Sample.Microsoft.Ref], default);

        Assert.Equal([apple.Id], quotes.Keys);
    }

    [Fact]
    public async Task Fresh_candles_come_from_the_cache()
    {
        Store(PriceRanges.OneMonth, FakePriceProvider.Points, fresh: true);

        Assert.Equal(FakePriceProvider.Points, await Service().GetCandlesAsync(apple, PriceRanges.OneMonth, default));
        Assert.Equal(0, provider.Calls);
    }

    [Fact]
    public async Task Missing_candles_are_fetched_and_cached_under_their_range()
    {
        await Service().GetCandlesAsync(apple, PriceRanges.OneYear, default);

        Assert.Equal([(apple.Id, PriceRanges.OneYear)], cache.Writes);
    }

    [Fact]
    public async Task Stale_candles_are_served_when_the_provider_is_down()
    {
        Store(PriceRanges.OneDay, FakePriceProvider.Points, fresh: false);
        provider.Down = true;

        Assert.Equal(FakePriceProvider.Points, await Service().GetCandlesAsync(apple, PriceRanges.OneDay, default));
    }

    [Fact]
    public async Task Candles_are_unavailable_when_the_provider_is_down_and_nothing_is_cached()
    {
        provider.Down = true;

        await Assert.ThrowsAsync<PricesUnavailableException>(() => Service().GetCandlesAsync(apple, PriceRanges.OneDay, default));
    }
}

public class InstrumentServiceTests
{
    private static readonly Guid UserId = Guid.NewGuid();

    private readonly FakeInstrumentRepository instruments = new(Sample.Apple, Sample.Microsoft, Sample.Spy);
    private readonly FakeWatchlistRepository watchlist = new(Sample.Apple, Sample.Microsoft, Sample.Spy);
    private readonly FakePriceProvider provider = new();

    private InstrumentService Service() =>
        new(instruments, watchlist, new PriceService(new FakePriceCache(), provider, NullLogger<PriceService>.Instance),
            NullLogger<InstrumentService>.Instance);

    [Fact]
    public async Task Search_returns_a_cursor_when_there_are_more_results()
    {
        var page = await Service().SearchAsync(" aa ", cursor: null, limit: 2, default);

        Assert.Equal(["AAPL", "MSFT"], page.Items.Select(i => i.Symbol));
        Assert.Equal(new InstrumentCursor(2, "MSFT").Encode(), page.NextCursor);
        // The query is normalised, the search starts at the beginning, and one extra row is requested.
        Assert.Equal(("AA", InstrumentCursor.Start, 3), instruments.LastSearch);
    }

    [Fact]
    public async Task Search_has_no_cursor_on_the_last_page()
    {
        var page = await Service().SearchAsync(null, cursor: null, limit: 3, default);

        Assert.Equal(3, page.Items.Count);
        Assert.Null(page.NextCursor);
    }

    [Fact]
    public async Task Search_continues_after_the_cursor()
    {
        var cursor = new InstrumentCursor(1, "AAPL");

        await Service().SearchAsync("a", cursor.Encode(), limit: 5, default);

        Assert.Equal(cursor, instruments.LastSearch?.After);
    }

    [Fact]
    public async Task Search_rejects_a_bad_cursor()
    {
        await Assert.ThrowsAsync<InvalidCursorException>(() => Service().SearchAsync("a", "nope", 5, default));
    }

    [Fact]
    public async Task Detail_finds_the_ticker_in_any_case_and_reports_following()
    {
        watchlist.Rows.Add((UserId, Sample.Apple));

        var detail = await Service().GetDetailAsync(" aapl ", UserId, default);

        Assert.Equal(new InstrumentDetailResponse("AAPL", "Apple Inc.", "STOCK", "NASDAQ", FakePriceProvider.Quote, Following: true), detail);
    }

    [Fact]
    public async Task Detail_has_no_quote_when_prices_are_unavailable()
    {
        provider.Down = true;

        var detail = await Service().GetDetailAsync("MSFT", UserId, default);

        Assert.Null(detail.Quote);
        Assert.False(detail.Following);
    }

    [Fact]
    public async Task Unknown_ticker_is_not_found()
    {
        var error = await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().GetDetailAsync("nope", UserId, default));

        Assert.Equal(404, error.Status);
        Assert.Equal("No instrument with ticker NOPE.", error.Message);
    }

    [Fact]
    public async Task Prices_carry_the_symbol_and_range()
    {
        var series = await Service().GetPricesAsync("spy", PriceRanges.OneYear, default);

        Assert.Equal(("SPY", "1Y"), (series.Symbol, series.Range));
        Assert.Equal(FakePriceProvider.Points, series.Points);
    }
}

public class WatchlistServiceTests
{
    private static readonly Guid UserId = Guid.NewGuid();

    private readonly FakeWatchlistRepository watchlist = new(Sample.Apple, Sample.Microsoft, Sample.Spy);
    private readonly FakePriceProvider provider = new();

    private WatchlistService Service(int limit = 50) => new(
        watchlist,
        new FakeInstrumentRepository(Sample.Apple, Sample.Microsoft, Sample.Spy),
        new PriceService(new FakePriceCache(), provider, NullLogger<PriceService>.Instance),
        Options.Create(new WatchlistOptions { Limit = limit }),
        NullLogger<WatchlistService>.Instance);

    [Fact]
    public async Task Lists_followed_instruments_with_quotes_and_the_limit()
    {
        var service = Service(limit: 7);
        await service.FollowAsync(UserId, "msft", default);
        await service.FollowAsync(UserId, "AAPL", default);

        var result = await service.GetAsync(UserId, default);

        Assert.Equal(7, result.Limit);
        Assert.Equal(["MSFT", "AAPL"], result.Items.Select(i => i.Symbol));
        Assert.All(result.Items, item =>
        {
            Assert.Equal(FakePriceProvider.Quote, item.Quote);
            Assert.Null(item.Rating);
        });
    }

    [Fact]
    public async Task Items_have_no_quote_when_prices_are_unavailable()
    {
        provider.Down = true;
        await Service().FollowAsync(UserId, "AAPL", default);

        var result = await Service().GetAsync(UserId, default);

        Assert.Null(Assert.Single(result.Items).Quote);
    }

    [Fact]
    public async Task Following_twice_keeps_one_row()
    {
        await Service().FollowAsync(UserId, "AAPL", default);
        await Service().FollowAsync(UserId, "AAPL", default);

        Assert.Single(watchlist.Rows);
    }

    [Fact]
    public async Task Following_beyond_the_limit_is_refused()
    {
        var service = Service(limit: 2);
        await service.FollowAsync(UserId, "AAPL", default);
        await service.FollowAsync(UserId, "MSFT", default);

        var error = await Assert.ThrowsAsync<WatchlistLimitException>(() => service.FollowAsync(UserId, "SPY", default));

        Assert.Equal(409, error.Status);
        Assert.Equal("You can follow up to 2 instruments.", error.Message);
        Assert.Equal(2, watchlist.Rows.Count);
    }

    [Fact]
    public async Task Following_an_instrument_already_followed_succeeds_at_the_limit()
    {
        var service = Service(limit: 1);
        await service.FollowAsync(UserId, "AAPL", default);

        await service.FollowAsync(UserId, "AAPL", default);
    }

    [Fact]
    public async Task The_limit_is_per_user()
    {
        var service = Service(limit: 1);
        await service.FollowAsync(UserId, "AAPL", default);

        await service.FollowAsync(Guid.NewGuid(), "MSFT", default);

        Assert.Equal(2, watchlist.Rows.Count);
    }

    [Fact]
    public async Task Unfollow_removes_the_row_and_is_idempotent()
    {
        var service = Service();
        await service.FollowAsync(UserId, "AAPL", default);

        await service.UnfollowAsync(UserId, "aapl", default);
        await service.UnfollowAsync(UserId, "aapl", default);

        Assert.Empty(watchlist.Rows);
    }

    [Fact]
    public async Task Unknown_ticker_is_not_found()
    {
        await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().FollowAsync(UserId, "NOPE", default));
        await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().UnfollowAsync(UserId, "NOPE", default));
    }
}
