using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using OnFinApp.Api.Common;

namespace OnFinApp.Api.Auth;

/// OIDC resource-server settings. Bound from OIDC_ISSUER, OIDC_AUDIENCE and OIDC_JWKS_URI.
public sealed class OidcOptions
{
    public string Issuer { get; set; } = "http://localhost:8082/realms/onfinapp";
    public string Audience { get; set; } = "onfinapp-api";

    /// Optional: set only when the issuer URL is not reachable from the API (e.g. inside docker compose).
    public string? JwksUri { get; set; }
}

public static class AuthSetup
{
    /// The API is a resource server only: it validates tokens issued by the OIDC provider.
    public static IServiceCollection AddOidcResourceServer(this IServiceCollection services, IConfiguration config)
    {
        var oidc = new OidcOptions();
        if (config["OIDC_ISSUER"] is { Length: > 0 } issuer) oidc.Issuer = issuer;
        if (config["OIDC_AUDIENCE"] is { Length: > 0 } audience) oidc.Audience = audience;
        if (config["OIDC_JWKS_URI"] is { Length: > 0 } jwksUri) oidc.JwksUri = jwksUri;

        services
            .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(options =>
            {
                options.MapInboundClaims = false; // keep standard claim names: sub, email, name
                options.RequireHttpsMetadata = IsHttps(oidc.Issuer);
                options.TokenValidationParameters = BuildValidationParameters(oidc);

                if (oidc.JwksUri is null)
                {
                    options.Authority = oidc.Issuer; // keys found through OIDC discovery
                }
                else
                {
                    // Keys fetched from an explicit URL, for when the issuer URL is not reachable from here.
                    options.ConfigurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
                        oidc.JwksUri,
                        new JwksRetriever(oidc.Issuer),
                        new HttpDocumentRetriever { RequireHttps = IsHttps(oidc.JwksUri) });
                }

                options.Events = new JwtBearerEvents
                {
                    OnAuthenticationFailed = LogRejectedToken,
                    OnChallenge = WriteUnauthorizedAsync,
                };
            });
        services.AddAuthorization();
        return services;
    }

    /// What a token must satisfy: signed by one of the provider's keys, issued by the configured issuer,
    /// meant for this API (audience), and not expired. The same checks, with the same one-minute allowance
    /// for clock differences, as the Java API.
    public static TokenValidationParameters BuildValidationParameters(OidcOptions oidc) => new()
    {
        ValidateIssuerSigningKey = true,
        RequireSignedTokens = true,
        ValidateIssuer = true,
        ValidIssuer = oidc.Issuer,
        ValidateAudience = true,
        ValidAudience = oidc.Audience,
        ValidateLifetime = true,
        RequireExpirationTime = true,
        ClockSkew = TimeSpan.FromSeconds(60),
    };

    /// Browser origins allowed to call the API (the Expo web app). CORS_ALLOWED_ORIGINS is comma-separated.
    public static IServiceCollection AddApiCors(this IServiceCollection services, IConfiguration config)
    {
        var origins = (config["CORS_ALLOWED_ORIGINS"] ?? "http://localhost:8090")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return services.AddCors(cors => cors.AddDefaultPolicy(policy => policy
            .WithOrigins(origins)
            .WithMethods("GET", "POST", "PUT", "DELETE")
            .WithHeaders("Authorization", "Content-Type", CorrelationMiddleware.Header)
            .WithExposedHeaders(CorrelationMiddleware.Header)));
    }

    /// Logs why a token was rejected. The token itself is never logged.
    private static Task LogRejectedToken(AuthenticationFailedContext context)
    {
        context.HttpContext.RequestServices.GetRequiredService<ILoggerFactory>()
            .CreateLogger("OnFinApp.Api.Auth")
            .LogInformation("Bearer token rejected: {Reason}", context.Exception.GetType().Name);
        return Task.CompletedTask;
    }

    private static async Task WriteUnauthorizedAsync(JwtBearerChallengeContext context)
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
    }

    private static bool IsHttps(string url) => url.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
}

/// Builds the token-validation configuration from a JWKS document instead of OIDC discovery.
internal sealed class JwksRetriever(string issuer) : IConfigurationRetriever<OpenIdConnectConfiguration>
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
