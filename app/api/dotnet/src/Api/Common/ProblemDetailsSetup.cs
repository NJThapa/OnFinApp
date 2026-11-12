using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.WebUtilities;
using Npgsql;

namespace OnFinApp.Api.Common;

/// What the client is told about an exception, and how loudly it is logged.
public sealed record ProblemMapping(int Status, string Detail, LogLevel Level)
{
    public const string UnexpectedDetail = "An unexpected error occurred.";
    public const string UnavailableDetail = "The service is temporarily unavailable. Please try again.";
    public const string InvalidRequestDetail = "The request is not valid.";

    /// The response is chosen by the type of exception:
    /// - <see cref="ApiProblemException"/>: the status and message it carries;
    /// - a malformed request: 400;
    /// - the database being unreachable, or a timeout: 503;
    /// - anything else: 500 with a generic message, so internals never leak to the client.
    public static ProblemMapping For(Exception exception) => exception switch
    {
        ApiProblemException problem => new(problem.Status, problem.Message,
            problem.Status >= 500 ? LogLevel.Warning : LogLevel.Information),
        BadHttpRequestException bad => new(bad.StatusCode, InvalidRequestDetail, LogLevel.Information),
        // A PostgresException is an error reported by the server for a statement; any other NpgsqlException
        // is a connection or I/O failure. Transient server errors (starting up, too many connections) count too.
        NpgsqlException db when db is not PostgresException || db.IsTransient =>
            new(StatusCodes.Status503ServiceUnavailable, UnavailableDetail, LogLevel.Error),
        TimeoutException => new(StatusCodes.Status503ServiceUnavailable, UnavailableDetail, LogLevel.Error),
        _ => new(StatusCodes.Status500InternalServerError, UnexpectedDetail, LogLevel.Error),
    };
}

/// The global exception handler: every exception that escapes an endpoint ends here.
public sealed class ProblemExceptionHandler(IProblemDetailsService problems, ILogger<ProblemExceptionHandler> log)
    : IExceptionHandler
{
    /// Nginx's "client closed request"; there is nobody left to read a body.
    private const int ClientClosedRequest = 499;

    public async ValueTask<bool> TryHandleAsync(HttpContext context, Exception exception, CancellationToken cancel)
    {
        if (exception is OperationCanceledException && context.RequestAborted.IsCancellationRequested)
        {
            log.LogDebug("The client closed the request before it finished");
            context.Response.StatusCode = ClientClosedRequest;
            return true;
        }

        var mapping = ProblemMapping.For(exception);
        if (mapping.Level >= LogLevel.Error)
        {
            log.Log(mapping.Level, exception, "Request failed with {Status}: {ExceptionType}", mapping.Status, exception.GetType().Name);
        }
        else
        {
            log.Log(mapping.Level, "Request refused with {Status}: {Detail}", mapping.Status, mapping.Detail);
        }

        context.Response.StatusCode = mapping.Status;
        return await problems.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = context,
            Exception = exception,
            ProblemDetails = new ProblemDetails { Status = mapping.Status, Detail = mapping.Detail },
        });
    }
}

public static class ProblemDetailsSetup
{
    /// Every error body has the shape the contract defines: type "about:blank", the status' reason phrase as
    /// title, a detail sentence and the request path as instance. The Java API produces the same fields.
    public static IServiceCollection AddApiProblemDetails(this IServiceCollection services)
    {
        services.AddExceptionHandler<ProblemExceptionHandler>();
        services.AddProblemDetails(options => options.CustomizeProblemDetails = context =>
        {
            var status = context.ProblemDetails.Status ?? context.HttpContext.Response.StatusCode;
            var detail = context.ProblemDetails.Detail;

            // Validation failures: report the first message instead of the per-field dictionary.
            if (context.ProblemDetails is HttpValidationProblemDetails validation)
            {
                detail = validation.Errors.Values.SelectMany(messages => messages).FirstOrDefault();
            }

            context.ProblemDetails = new ProblemDetails
            {
                Type = "about:blank",
                Title = ReasonPhrases.GetReasonPhrase(status),
                Status = status,
                Detail = detail ?? (status == StatusCodes.Status400BadRequest
                    ? ProblemMapping.InvalidRequestDetail
                    : ReasonPhrases.GetReasonPhrase(status)),
                Instance = context.HttpContext.Request.Path,
            };
        });
        return services;
    }
}
