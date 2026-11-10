using Npgsql;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Default")
    ?? "Host=localhost;Port=5432;Database=app_dotnet;Username=app;Password=app";
builder.Services.AddSingleton(NpgsqlDataSource.Create(connectionString));

var app = builder.Build();

app.MapGet("/api/health", async (NpgsqlDataSource db) =>
{
    try
    {
        await using var cmd = db.CreateCommand("select 1");
        await cmd.ExecuteScalarAsync();
        return Results.Ok(new { stack = "dotnet", status = "ok", db = "up" });
    }
    catch (Exception)
    {
        return Results.Json(new { stack = "dotnet", status = "degraded", db = "down" }, statusCode: 503);
    }
});

app.Run();
