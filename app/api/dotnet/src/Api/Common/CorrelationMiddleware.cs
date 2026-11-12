using System.Diagnostics;
using System.Text.RegularExpressions;

namespace OnFinApp.Api.Common;

/// Gives every request a correlation ID and writes one log line when it finishes.
///
/// The ID comes from the X-Correlation-ID request header. When the header is missing or is not a plain
/// token (letters, digits, dot, underscore, hyphen; at most 64 characters), a new UUID is used instead, so
/// arbitrary client text never reaches the logs. The ID is returned in the same response header, added to
/// every log line written during the request, and recorded on the request's trace span.
public sealed partial class CorrelationMiddleware(RequestDelegate next, ILogger<CorrelationMiddleware> log)
{
    public const string Header = "X-Correlation-ID";
    public const string SpanAttribute = "app.correlation_id";

    public async Task InvokeAsync(HttpContext context)
    {
        var correlationId = Accept(context.Request.Headers[Header]) ?? Guid.NewGuid().ToString();
        Activity.Current?.SetTag(SpanAttribute, correlationId);

        // Set when the response starts, so it survives the exception handler clearing the response.
        context.Response.OnStarting(() =>
        {
            context.Response.Headers[Header] = correlationId;
            return Task.CompletedTask;
        });

        using (log.BeginScope("CorrelationId:{CorrelationId}", correlationId))
        {
            var started = Stopwatch.GetTimestamp();
            try
            {
                await next(context);
            }
            finally
            {
                LogRequest(context, Stopwatch.GetElapsedTime(started));
            }
        }
    }

    /// The header value if it is safe to log, otherwise null.
    public static string? Accept(string? value) =>
        value is not null && SafeToken().IsMatch(value) ? value : null;

    private void LogRequest(HttpContext context, TimeSpan elapsed)
    {
        var status = context.Response.StatusCode;
        var level = status >= 500 ? LogLevel.Warning
            : context.Request.Path.StartsWithSegments("/api/health") ? LogLevel.Debug
            : LogLevel.Information;
        log.Log(level, "{Method} {Path} responded {Status} in {ElapsedMs} ms",
            context.Request.Method, context.Request.Path.Value, status, (long)elapsed.TotalMilliseconds);
    }

    [GeneratedRegex(@"^[A-Za-z0-9._-]{1,64}$")]
    private static partial Regex SafeToken();
}
