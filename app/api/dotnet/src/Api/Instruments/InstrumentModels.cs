using OnFinApp.Api.Prices;

namespace OnFinApp.Api.Instruments;

public sealed record Instrument(Guid Id, string Symbol, string Name, string Type, string Exchange)
{
    public InstrumentRef Ref => new(Id, Symbol);
}

/// A search hit. Rank 0: exact ticker, 1: ticker starts with the query, 2: name contains it (or no query).
public sealed record InstrumentMatch(Instrument Instrument, int Rank);

// Response bodies, as defined in the shared contract.

public sealed record InstrumentResponse(string Symbol, string Name, string Type, string Exchange)
{
    public static InstrumentResponse From(Instrument i) => new(i.Symbol, i.Name, i.Type, i.Exchange);
}

public sealed record InstrumentPageResponse(IReadOnlyList<InstrumentResponse> Items, string? NextCursor);

public sealed record InstrumentDetailResponse(
    string Symbol, string Name, string Type, string Exchange, Quote? Quote, bool Following);

public sealed record PriceSeriesResponse(string Symbol, string Range, IReadOnlyList<PricePoint> Points);
