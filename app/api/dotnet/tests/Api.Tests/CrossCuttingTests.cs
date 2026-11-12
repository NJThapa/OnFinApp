using System.Net.Sockets;
using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Npgsql;
using OnFinApp.Api.Auth;
using OnFinApp.Api.Common;

namespace OnFinApp.Api.Tests;

public class ProblemMappingTests
{
    [Fact]
    public void Api_problems_keep_their_status_and_message()
    {
        Assert.Equal(new ProblemMapping(404, "No instrument with ticker NOPE.", LogLevel.Information),
            ProblemMapping.For(new InstrumentNotFoundException("NOPE")));
        Assert.Equal(new ProblemMapping(409, "You can follow up to 50 instruments.", LogLevel.Information),
            ProblemMapping.For(new WatchlistLimitException(50)));
        Assert.Equal(new ProblemMapping(400, "cursor is not valid.", LogLevel.Information),
            ProblemMapping.For(new InvalidCursorException()));
    }

    [Fact]
    public void Unavailable_prices_are_a_503_logged_as_a_warning()
    {
        Assert.Equal(new ProblemMapping(503, "Prices are unavailable right now.", LogLevel.Warning),
            ProblemMapping.For(new PricesUnavailableException()));
    }

    [Fact]
    public void A_malformed_request_is_a_400()
    {
        Assert.Equal(new ProblemMapping(400, ProblemMapping.InvalidRequestDetail, LogLevel.Information),
            ProblemMapping.For(new BadHttpRequestException("Failed to bind parameter \"int limit\" from \"abc\".")));
    }

    [Fact]
    public void An_unreachable_database_is_a_503()
    {
        var unreachable = new NpgsqlException("Failed to connect to 127.0.0.1:5432", new SocketException());

        Assert.Equal(new ProblemMapping(503, ProblemMapping.UnavailableDetail, LogLevel.Error), ProblemMapping.For(unreachable));
        Assert.Equal(503, ProblemMapping.For(new TimeoutException()).Status);
    }

    [Fact]
    public void A_failing_statement_is_a_500()
    {
        var syntaxError = new PostgresException("syntax error", "ERROR", "ERROR", "42601");

        Assert.Equal(500, ProblemMapping.For(syntaxError).Status);
    }

    [Fact]
    public void Anything_else_is_a_500_that_does_not_reveal_the_cause()
    {
        var mapping = ProblemMapping.For(new InvalidOperationException("connection string password=secret"));

        Assert.Equal(new ProblemMapping(500, "An unexpected error occurred.", LogLevel.Error), mapping);
    }
}

public class CorrelationIdTests
{
    [Theory]
    [InlineData("7f1c2a9e-6f2b-4c1d-9f0a-1234567890ab")]
    [InlineData("checkout_flow.42")]
    [InlineData("A")]
    public void Plain_tokens_are_accepted(string value)
    {
        Assert.Equal(value, CorrelationMiddleware.Accept(value));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("has space")]
    [InlineData("line\nbreak")]
    [InlineData("{\"json\":true}")]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")] // 65 characters
    public void Anything_else_is_replaced(string? value)
    {
        Assert.Null(CorrelationMiddleware.Accept(value));
    }
}

/// The token checks, exercised with locally signed tokens.
public class TokenValidationTests
{
    private const string Issuer = "http://localhost:8082/realms/onfinapp";
    private const string Audience = "onfinapp-api";

    private static readonly RsaSecurityKey Key = new(RSA.Create(2048)) { KeyId = "test-key" };
    private static readonly RsaSecurityKey OtherKey = new(RSA.Create(2048)) { KeyId = "test-key" };

    private static string Token(
        string issuer = Issuer, string? audience = Audience, int expiresInSeconds = 300, RsaSecurityKey? signedWith = null)
    {
        var now = DateTime.UtcNow;
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer,
            Audience = audience,
            Subject = new ClaimsIdentity([new Claim("sub", "user-1")]),
            IssuedAt = now.AddSeconds(Math.Min(expiresInSeconds, 0) - 600),
            NotBefore = now.AddSeconds(Math.Min(expiresInSeconds, 0) - 600),
            Expires = now.AddSeconds(expiresInSeconds),
            SigningCredentials = new SigningCredentials(signedWith ?? Key, SecurityAlgorithms.RsaSha256),
        });
    }

    private static async Task<TokenValidationResult> Validate(string token)
    {
        var parameters = AuthSetup.BuildValidationParameters(new OidcOptions { Issuer = Issuer, Audience = Audience });
        parameters.IssuerSigningKey = Key; // in the running API the keys come from the provider's JWKS
        return await new JsonWebTokenHandler().ValidateTokenAsync(token, parameters);
    }

    [Fact]
    public async Task A_valid_token_is_accepted()
    {
        var result = await Validate(Token());

        Assert.True(result.IsValid, result.Exception?.Message);
    }

    [Fact]
    public async Task A_token_from_another_issuer_is_rejected()
    {
        var result = await Validate(Token(issuer: "http://localhost:8082/realms/other"));

        Assert.IsType<SecurityTokenInvalidIssuerException>(result.Exception);
    }

    [Fact]
    public async Task A_token_for_another_audience_is_rejected()
    {
        var result = await Validate(Token(audience: "some-other-api"));

        Assert.IsType<SecurityTokenInvalidAudienceException>(result.Exception);
    }

    [Fact]
    public async Task A_token_without_an_audience_is_rejected()
    {
        var result = await Validate(Token(audience: null));

        Assert.False(result.IsValid);
    }

    [Fact]
    public async Task An_expired_token_is_rejected()
    {
        var result = await Validate(Token(expiresInSeconds: -120));

        Assert.IsType<SecurityTokenExpiredException>(result.Exception);
    }

    [Fact]
    public async Task A_token_that_expired_within_the_clock_allowance_is_still_accepted()
    {
        var result = await Validate(Token(expiresInSeconds: -30));

        Assert.True(result.IsValid, result.Exception?.Message);
    }

    [Fact]
    public async Task A_token_signed_with_another_key_is_rejected()
    {
        var result = await Validate(Token(signedWith: OtherKey));

        Assert.False(result.IsValid);
    }

    [Fact]
    public async Task An_unsigned_token_is_rejected()
    {
        var unsigned = new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = Issuer,
            Audience = Audience,
            Expires = DateTime.UtcNow.AddMinutes(5),
        });

        Assert.False((await Validate(unsigned)).IsValid);
    }
}
