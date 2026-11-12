using OnFinApp.Api.Prices;

namespace OnFinApp.Api.Tests;

/// Expected values were computed independently from the formulas in app/api/shared/mock/README.md.
/// The Java API's MockPriceProviderTest asserts the same numbers.
public class MockPriceProviderTests
{
    private static readonly MockInstrument[] Fixtures =
    [
        new("AAPL", "Apple Inc.", "STOCK", "NASDAQ", 100),
        new("MSFT", "Microsoft Corporation", "STOCK", "NASDAQ", 200),
        new("BRK.B", "Berkshire Hathaway Inc. Class B", "STOCK", "NYSE", 420),
    ];

    private static readonly DateTimeOffset Wednesday = new(2026, 11, 11, 17, 0, 0, TimeSpan.Zero);

    private static MockPriceProvider At(DateTimeOffset now) => new(Fixtures, new FixedClock(now));

    private static DateTime Utc(int year, int month, int day, int hour, int minute = 0) =>
        new(year, month, day, hour, minute, 0, DateTimeKind.Utc);

    [Fact]
    public async Task Quote_on_a_trading_day()
    {
        var quote = await At(Wednesday).GetQuoteAsync("AAPL", default);

        Assert.Equal(new Quote(111.83, -0.65, -0.58, 112.48, Utc(2026, 11, 11, 17)), quote);
    }

    [Fact]
    public async Task Quote_for_a_ticker_with_a_dot()
    {
        var quote = await At(Wednesday).GetQuoteAsync("BRK.B", default);

        Assert.Equal((387.91, -0.61, -0.16, 388.52), (quote.Price, quote.Change, quote.ChangePercent, quote.PreviousClose));
    }

    [Fact]
    public async Task Quote_at_the_weekend_is_fridays()
    {
        var saturday = await At(new DateTimeOffset(2026, 11, 14, 17, 0, 0, TimeSpan.Zero)).GetQuoteAsync("AAPL", default);

        Assert.Equal((110.99, -0.33, -0.3, 111.32), (saturday.Price, saturday.Change, saturday.ChangePercent, saturday.PreviousClose));
    }

    [Fact]
    public async Task The_day_changes_at_midnight_in_New_York_not_UTC()
    {
        // 01:00 UTC on the 12th is still the evening of the 11th in New York.
        var quote = await At(new DateTimeOffset(2026, 11, 12, 1, 0, 0, TimeSpan.Zero)).GetQuoteAsync("AAPL", default);

        Assert.Equal(111.83, quote.Price);
    }

    [Fact]
    public async Task Quote_truncates_as_of_to_whole_seconds()
    {
        var quote = await At(Wednesday.AddMilliseconds(789)).GetQuoteAsync("AAPL", default);

        Assert.Equal(Utc(2026, 11, 11, 17), quote.AsOf);
        Assert.Equal(DateTimeKind.Utc, quote.AsOf.Kind);
    }

    [Fact]
    public async Task One_day_has_79_five_minute_points_from_previous_close_to_close()
    {
        var points = await At(Wednesday).GetCandlesAsync("MSFT", PriceRanges.OneDay, default);

        Assert.Equal(79, points.Count);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 14, 30), 226.31), points[0]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 14, 35), 226.31), points[1]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 17, 50), 224.45), points[40]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 21, 0), 224.21), points[78]);
    }

    [Fact]
    public async Task One_week_has_seven_hourly_points_for_each_of_five_trading_days()
    {
        var points = await At(Wednesday).GetCandlesAsync("MSFT", PriceRanges.OneWeek, default);

        Assert.Equal(35, points.Count);
        Assert.Equal(Utc(2026, 11, 5, 14, 30), points[0].Time);
        Assert.Equal(new PricePoint(Utc(2026, 11, 6, 14, 30), 235.73), points[7]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 20, 30), 224.21), points[34]);
    }

    [Fact]
    public async Task One_month_has_a_close_for_each_trading_day()
    {
        var points = await At(Wednesday).GetCandlesAsync("MSFT", PriceRanges.OneMonth, default);

        Assert.Equal(23, points.Count);
        Assert.Equal(new PricePoint(Utc(2026, 10, 12, 21), 230.59), points[0]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 11, 21), 224.21), points[^1]);
        Assert.DoesNotContain(points, p => p.Time.DayOfWeek is DayOfWeek.Saturday or DayOfWeek.Sunday);
    }

    [Fact]
    public async Task One_year_and_five_years()
    {
        var year = await At(Wednesday).GetCandlesAsync("MSFT", PriceRanges.OneYear, default);
        var fiveYears = await At(Wednesday).GetCandlesAsync("MSFT", PriceRanges.FiveYears, default);

        Assert.Equal(262, year.Count);
        Assert.Equal(261, fiveYears.Count);
        Assert.All(fiveYears, p => Assert.Equal(DayOfWeek.Friday, p.Time.DayOfWeek));
        Assert.Equal(new PricePoint(Utc(2021, 11, 12, 21), 219.35), fiveYears[0]);
        Assert.Equal(new PricePoint(Utc(2026, 11, 6, 21), 234.24), fiveYears[^1]);
    }

    [Fact]
    public async Task Latest_daily_close_equals_the_quote()
    {
        var provider = At(Wednesday);
        var quote = await provider.GetQuoteAsync("MSFT", default);
        var month = await provider.GetCandlesAsync("MSFT", PriceRanges.OneMonth, default);

        Assert.Equal(quote.Price, month[^1].Close);
        Assert.Equal(quote.PreviousClose, month[^2].Close);
    }

    [Fact]
    public async Task Unknown_symbol_is_a_provider_failure()
    {
        await Assert.ThrowsAsync<PriceProviderException>(() => At(Wednesday).GetQuoteAsync("NOPE", default));
    }

    [Fact]
    public async Task Lists_the_fixture_instruments()
    {
        var list = await At(Wednesday).ListInstrumentsAsync(default);

        Assert.Equal(["AAPL", "MSFT", "BRK.B"], list.Select(i => i.Symbol));
        Assert.Equal(new ProviderInstrument("AAPL", "Apple Inc.", "STOCK", "NASDAQ"), list[0]);
    }
}
