using System.Text.Json;
using OnFinApp.Api.Common;

namespace OnFinApp.Api.Prices;

/// Quotes and chart history, read through the shared cache.
/// Quotes are fresh for 60 seconds, intraday charts for 5 minutes, daily charts until the next New York day.
/// When the provider fails, the stale entry is served if there is one.
public sealed class PriceService(IPriceCacheRepository cache, IPriceProvider provider, ILogger<PriceService> log)
{
    public const int QuoteTtlSeconds = 60;
    public const int IntradayTtlSeconds = 300;
    private const int MaxConcurrentFetches = 8;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly SemaphoreSlim fetchers = new(MaxConcurrentFetches);

    /// Null when the provider is unavailable and nothing is cached.
    public async Task<Quote?> GetQuoteAsync(InstrumentRef instrument, CancellationToken cancel) =>
        (await GetQuotesAsync([instrument], cancel)).GetValueOrDefault(instrument.Id);

    /// Quotes for several instruments; instruments without a quote are absent from the result.
    public async Task<IReadOnlyDictionary<Guid, Quote>> GetQuotesAsync(
        IReadOnlyList<InstrumentRef> instruments, CancellationToken cancel)
    {
        var cached = await cache.ReadQuotesAsync(instruments.Select(i => i.Id).ToList(), QuoteTtlSeconds, cancel);

        async Task<(Guid Id, Quote? Quote)> ResolveAsync(InstrumentRef instrument)
        {
            var hit = cached.GetValueOrDefault(instrument.Id);
            if (hit is { Fresh: true }) return (instrument.Id, Parse<Quote>(hit.Json));

            await fetchers.WaitAsync(cancel);
            try
            {
                log.LogDebug("Quote for {Symbol} is not cached or stale; asking the price provider", instrument.Symbol);
                var quote = await provider.GetQuoteAsync(instrument.Symbol, cancel);
                await cache.WriteAsync(instrument.Id, IPriceCacheRepository.QuoteKey, JsonSerializer.Serialize(quote, Json), cancel);
                return (instrument.Id, quote);
            }
            catch (PriceProviderException e)
            {
                log.LogWarning("Price provider failed for the {Symbol} quote ({Error}); serving {Fallback}",
                    instrument.Symbol, e.Message, hit is null ? "no quote" : "the stale cached quote");
                return (instrument.Id, hit is null ? null : Parse<Quote>(hit.Json));
            }
            finally
            {
                fetchers.Release();
            }
        }

        var resolved = await Task.WhenAll(instruments.Select(ResolveAsync));
        return resolved.Where(r => r.Quote is not null).ToDictionary(r => r.Id, r => r.Quote!);
    }

    /// Throws <see cref="PricesUnavailableException"/> when the provider fails and nothing is cached.
    public async Task<IReadOnlyList<PricePoint>> GetCandlesAsync(InstrumentRef instrument, string range, CancellationToken cancel)
    {
        int? ttl = PriceRanges.IsIntraday(range) ? IntradayTtlSeconds : null;
        var hit = await cache.ReadAsync(instrument.Id, range, ttl, cancel);
        if (hit is { Fresh: true }) return Parse<List<PricePoint>>(hit.Json);

        try
        {
            log.LogDebug("{Range} prices for {Symbol} are not cached or stale; asking the price provider", range, instrument.Symbol);
            var points = await provider.GetCandlesAsync(instrument.Symbol, range, cancel);
            await cache.WriteAsync(instrument.Id, range, JsonSerializer.Serialize(points, Json), cancel);
            return points;
        }
        catch (PriceProviderException e)
        {
            log.LogWarning("Price provider failed for {Symbol} {Range} prices ({Error}); serving {Fallback}",
                instrument.Symbol, range, e.Message, hit is null ? "an error" : "the stale cached prices");
            if (hit is not null) return Parse<List<PricePoint>>(hit.Json);
            throw new PricesUnavailableException();
        }
    }

    private static T Parse<T>(string json) =>
        JsonSerializer.Deserialize<T>(json, Json) ?? throw new InvalidOperationException("The price cache holds an empty entry.");
}
