using Npgsql;

namespace OnFinApp.Api.Health;

public sealed record HealthResponse(string Stack, string Status, string Db);

public static class HealthEndpoints
{
    /// Public liveness and database check. The body is defined by the shared contract, which is why this is
    /// not the framework's health-check endpoint.
    public static void MapHealth(this IEndpointRouteBuilder app) =>
        app.MapGet("/api/health", async (NpgsqlDataSource db, CancellationToken cancel) =>
        {
            try
            {
                await using var cmd = db.CreateCommand("select 1");
                await cmd.ExecuteScalarAsync(cancel);
                return Results.Ok(new HealthResponse("dotnet", "ok", "up"));
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                return Results.Json(new HealthResponse("dotnet", "degraded", "down"), statusCode: StatusCodes.Status503ServiceUnavailable);
            }
        });
}
