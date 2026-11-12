namespace OnFinApp.Api.Prices;

public sealed record Quote(double Price, double Change, double ChangePercent, double PreviousClose, DateTime AsOf);

public sealed record PricePoint(DateTime Time, double Close);

/// An instrument as the price provider lists it.
public sealed record ProviderInstrument(string Symbol, string Name, string Type, string Exchange);

/// What the price services need to know about an instrument.
public sealed record InstrumentRef(Guid Id, string Symbol);

public static class PriceRanges
{
    public const string OneDay = "1D";
    public const string OneWeek = "1W";
    public const string OneMonth = "1M";
    public const string OneYear = "1Y";
    public const string FiveYears = "5Y";

    public const string Default = OneMonth;

    /// Ranges made of points within a day; they go stale in minutes rather than daily.
    public static bool IsIntraday(string range) => range is OneDay or OneWeek;
}

/// Thrown by a price provider that cannot answer.
public sealed class PriceProviderException(string message, Exception? inner = null) : Exception(message, inner);
