using System.Security.Claims;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using Npgsql;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Default")
    ?? "Host=localhost;Port=5432;Database=onfinapp;Username=app;Password=app";
builder.Services.AddSingleton(NpgsqlDataSource.Create(connectionString));
builder.Services.AddSingleton<UserStore>();

// The API is a resource server only: it validates tokens issued by the OIDC provider.
var issuer = builder.Configuration["OIDC_ISSUER"] ?? "http://localhost:8082/realms/onfinapp";
var audience = builder.Configuration["OIDC_AUDIENCE"] ?? "onfinapp-api";
var jwksUri = builder.Configuration["OIDC_JWKS_URI"];
var requireHttps = issuer.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.MapInboundClaims = false; // keep standard claim names: sub, email, name
        options.RequireHttpsMetadata = requireHttps;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = issuer,
            ValidAudience = audience,
        };

        if (string.IsNullOrWhiteSpace(jwksUri))
        {
            options.Authority = issuer; // keys found through OIDC discovery
        }
        else
        {
            // Keys fetched from an explicit URL, for when the issuer URL is not reachable from here.
            options.ConfigurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
                jwksUri,
                new JwksRetriever(issuer),
                new HttpDocumentRetriever { RequireHttps = jwksUri.StartsWith("https://", StringComparison.OrdinalIgnoreCase) });
        }

        options.Events = new JwtBearerEvents
        {
            OnChallenge = async context =>
            {
                context.HandleResponse();
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                context.Response.Headers.WWWAuthenticate = "Bearer";
                await context.Response.WriteAsJsonAsync(
                    new
                    {
                        type = "about:blank",
                        title = "Unauthorized",
                        status = 401,
                        detail = "A valid bearer token is required.",
                    },
                    options: null,
                    contentType: "application/problem+json");
            },
        };
    });
builder.Services.AddAuthorization();

// Browser origins allowed to call the API (the Expo web app). Comma-separated; empty = none.
var allowedOrigins = (builder.Configuration["CORS_ALLOWED_ORIGINS"] ?? "http://localhost:8090")
    .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
builder.Services.AddCors(cors => cors.AddDefaultPolicy(policy => policy
    .WithOrigins(allowedOrigins)
    .WithMethods("GET", "POST", "PUT", "DELETE")
    .WithHeaders("Authorization", "Content-Type")));

var app = builder.Build();

app.UseCors();
app.UseAuthentication();
app.UseAuthorization();

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

var v1 = app.MapGroup("/api/v1").RequireAuthorization();

v1.MapGet("/me", async (ClaimsPrincipal principal, UserStore users) =>
    Results.Ok(await users.ResolveAsync(principal)));

app.Run();

record User(Guid Id, string? Email, string? Name);

/// Maps the authenticated token to the local user row, creating it on first sight.
sealed class UserStore(NpgsqlDataSource db)
{
    private const string Upsert = """
        insert into users (issuer, subject, email, name)
        values ($1, $2, $3, $4)
        on conflict (issuer, subject)
        do update set email = excluded.email, name = excluded.name, updated_at = now()
        returning id, email, name
        """;

    public async Task<User> ResolveAsync(ClaimsPrincipal principal)
    {
        await using var cmd = db.CreateCommand(Upsert);
        cmd.Parameters.Add(new() { Value = principal.FindFirstValue("iss")! });
        cmd.Parameters.Add(new() { Value = principal.FindFirstValue("sub")! });
        cmd.Parameters.Add(new() { Value = (object?)principal.FindFirstValue("email") ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });
        cmd.Parameters.Add(new() { Value = (object?)principal.FindFirstValue("name") ?? DBNull.Value, NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Text });

        await using var reader = await cmd.ExecuteReaderAsync();
        await reader.ReadAsync();
        return new User(
            reader.GetGuid(0),
            reader.IsDBNull(1) ? null : reader.GetString(1),
            reader.IsDBNull(2) ? null : reader.GetString(2));
    }
}

/// Builds the token-validation configuration from a JWKS document instead of OIDC discovery.
sealed class JwksRetriever(string issuer) : IConfigurationRetriever<OpenIdConnectConfiguration>
{
    public async Task<OpenIdConnectConfiguration> GetConfigurationAsync(
        string address, IDocumentRetriever retriever, CancellationToken cancel)
    {
        var json = await retriever.GetDocumentAsync(address, cancel);
        var config = new OpenIdConnectConfiguration { Issuer = issuer };
        foreach (var key in new JsonWebKeySet(json).GetSigningKeys())
        {
            config.SigningKeys.Add(key);
        }
        return config;
    }
}
