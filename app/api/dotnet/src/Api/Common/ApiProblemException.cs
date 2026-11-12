namespace OnFinApp.Api.Common;

/// An error the client should see. Rendered as an RFC 9457 problem response by <see cref="ProblemExceptionHandler"/>.
public abstract class ApiProblemException(int status, string detail) : Exception(detail)
{
    public int Status => status;
}

public sealed class InstrumentNotFoundException(string symbol)
    : ApiProblemException(StatusCodes.Status404NotFound, $"No instrument with ticker {symbol}.");

public sealed class InvalidCursorException()
    : ApiProblemException(StatusCodes.Status400BadRequest, "cursor is not valid.");

public sealed class WatchlistLimitException(int limit)
    : ApiProblemException(StatusCodes.Status409Conflict, $"You can follow up to {limit} instruments.");

public sealed class PricesUnavailableException()
    : ApiProblemException(StatusCodes.Status503ServiceUnavailable, "Prices are unavailable right now.");

public sealed class RefreshLimitException(int limit)
    : ApiProblemException(StatusCodes.Status429TooManyRequests, $"You can refresh up to {limit} analyses in 24 hours.");
