using System.Text.Json;

namespace OnFinApp.Api.Prices;

/// One entry of shared/mock/instruments.json.
public sealed record MockInstrument(string Symbol, string Name, string Type, string Exchange, double BasePrice);

/// Deterministic invented prices; no network. The formulas are specified in shared/mock/README.md
/// and implemented identically in the Java API.
public sealed class MockPriceProvider : IPriceProvider
{
    private const long SecondsPerDay = 86400;
    private static readonly TimeZoneInfo NewYork = TimeZoneInfo.FindSystemTimeZoneById("America/New_York");
    private static readonly int EpochDayNumber = new DateOnly(1970, 1, 1).DayNumber;

    private readonly Dictionary<string, MockInstrument> fixtures;
    private readonly TimeProvider clock;

    public MockPriceProvider(IEnumerable<MockInstrument> instruments, TimeProvider clock)
    {
        fixtures = instruments.ToDictionary(i => i.Symbol);
        this.clock = clock;
    }

    public static MockPriceProvider FromSharedFolder(string sharedDir, TimeProvider clock)
    {
        var file = Path.Combine(sharedDir, "mock", "instruments.json");
        var instruments = JsonSerializer.Deserialize<List<MockInstrument>>(
            File.ReadAllText(file), new JsonSerializerOptions(JsonSerializerDefaults.Web));
        return new MockPriceProvider(instruments ?? [], clock);
    }

    public Task<IReadOnlyList<ProviderInstrument>> ListInstrumentsAsync(CancellationToken cancel) =>
        Task.FromResult<IReadOnlyList<ProviderInstrument>>(
            fixtures.Values.Select(f => new ProviderInstrument(f.Symbol, f.Name, f.Type, f.Exchange)).ToList());

    public Task<Quote> GetQuoteAsync(string symbol, CancellationToken cancel)
    {
        var series = SeriesFor(symbol);
        var now = clock.GetUtcNow().UtcDateTime;
        var day = LastTradingDay(Today());
        var price = series.Close(day);
        var previous = series.Close(PreviousTradingDay(day));
        var change = Round2(price - previous);
        var asOf = new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerSecond, DateTimeKind.Utc);
        return Task.FromResult(new Quote(price, change, Round2(change / previous * 100), previous, asOf));
    }

    public Task<IReadOnlyList<PricePoint>> GetCandlesAsync(string symbol, string range, CancellationToken cancel)
    {
        var series = SeriesFor(symbol);
        var today = Today();
        var points = new List<PricePoint>();
        switch (range)
        {
            case PriceRanges.OneDay:
                series.Intraday(LastTradingDay(today), 79, 5, points);
                break;
            case PriceRanges.OneWeek:
                var days = new List<long>();
                for (var d = LastTradingDay(today); days.Count < 5; d = PreviousTradingDay(d)) days.Insert(0, d);
                foreach (var d in days) series.Intraday(d, 7, 60, points);
                break;
            case PriceRanges.OneMonth:
                series.Daily(today - 30, today, fridaysOnly: false, points);
                break;
            case PriceRanges.OneYear:
                series.Daily(today - 365, today, fridaysOnly: false, points);
                break;
            case PriceRanges.FiveYears:
                series.Daily(today - 1825, today, fridaysOnly: true, points);
                break;
            default:
                throw new ArgumentException($"Unknown range {range}", nameof(range));
        }
        return Task.FromResult<IReadOnlyList<PricePoint>>(points);
    }

    private Series SeriesFor(string symbol) =>
        fixtures.TryGetValue(symbol, out var fixture)
            ? new Series(symbol, fixture.BasePrice)
            : throw new PriceProviderException($"No mock prices for {symbol}");

    private sealed class Series
    {
        private readonly double basePrice;
        private readonly long hash;
        private readonly long phase;

        public Series(string symbol, double basePrice)
        {
            this.basePrice = basePrice;
            long h = 7;
            foreach (var c in symbol) h = (h * 31 + c) % 100003;
            hash = h;
            phase = h % 365;
        }

        public double Close(long day)
        {
            var noise = (((hash * 131 + day * 7919) % 2001) - 1000) / 1000.0;
            double x = day + phase;
            return Round2(basePrice * (1
                + 0.15 * Math.Sin(2 * Math.PI * x / 365)
                + 0.04 * Math.Sin(2 * Math.PI * x / 29)
                + 0.01 * noise));
        }

        public void Daily(long from, long to, bool fridaysOnly, List<PricePoint> output)
        {
            for (var d = from; d <= to; d++)
            {
                if (fridaysOnly ? Weekday(d) == 4 : Weekday(d) < 5)
                {
                    output.Add(new PricePoint(DateTime.UnixEpoch.AddSeconds(d * SecondsPerDay + 21 * 3600), Close(d)));
                }
            }
        }

        public void Intraday(long day, int count, int stepMinutes, List<PricePoint> output)
        {
            var close = Close(day);
            var previous = Close(PreviousTradingDay(day));
            var open = day * SecondsPerDay + 14 * 3600 + 30 * 60;
            for (var k = 0; k < count; k++)
            {
                var t = (double)k / (count - 1);
                var value = previous + (close - previous) * t
                    + basePrice * 0.004 * Math.Sin(0.9 * k + phase) * Math.Sin(Math.PI * t);
                output.Add(new PricePoint(DateTime.UnixEpoch.AddSeconds(open + (long)k * stepMinutes * 60), Round2(value)));
            }
        }
    }

    /// The current New York calendar day, in days since 1970-01-01. The same day boundary as the price cache.
    private long Today() =>
        DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(clock.GetUtcNow(), NewYork).DateTime).DayNumber - EpochDayNumber;

    /// 0 = Monday ... 6 = Sunday. Day 0 (1970-01-01) was a Thursday.
    private static int Weekday(long day) => (int)((day + 3) % 7);

    private static long LastTradingDay(long day)
    {
        while (Weekday(day) >= 5) day--;
        return day;
    }

    private static long PreviousTradingDay(long day) => LastTradingDay(day - 1);

    // Same rounding as Java's Math.round.
    private static double Round2(double value) => Math.Floor(value * 100 + 0.5) / 100.0;
}
