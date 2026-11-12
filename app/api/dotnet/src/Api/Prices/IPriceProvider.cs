namespace OnFinApp.Api.Prices;

/// Source of the instrument list, quotes and chart history. Chosen with PRICE_PROVIDER.
/// Implementations throw <see cref="PriceProviderException"/> when they cannot answer.
public interface IPriceProvider
{
    Task<IReadOnlyList<ProviderInstrument>> ListInstrumentsAsync(CancellationToken cancel);

    Task<Quote> GetQuoteAsync(string symbol, CancellationToken cancel);

    /// Closing prices for one of <see cref="PriceRanges"/>, oldest first.
    Task<IReadOnlyList<PricePoint>> GetCandlesAsync(string symbol, string range, CancellationToken cancel);
}
