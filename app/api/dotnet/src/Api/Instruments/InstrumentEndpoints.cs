using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using Microsoft.AspNetCore.Http.HttpResults;
using OnFinApp.Api.Prices;
using OnFinApp.Api.Users;

namespace OnFinApp.Api.Instruments;

public static class InstrumentEndpoints
{
    private const int DefaultLimit = 20;
    private const int MaxLimit = 50;
    private const int MaxQueryLength = 50;

    public static IServiceCollection AddInstruments(this IServiceCollection services) =>
        services
            .AddSingleton<IInstrumentRepository, InstrumentRepository>()
            .AddSingleton<InstrumentService>()
            .AddHostedService<InstrumentSync>();

    public static void MapInstruments(this RouteGroupBuilder v1)
    {
        var group = v1.MapGroup("/instruments");
        group.MapGet("", Search);
        group.MapGet("/{symbol}", GetDetail);
        group.MapGet("/{symbol}/prices", GetPrices);
    }

    private static async Task<Ok<InstrumentPageResponse>> Search(
        [MaxLength(MaxQueryLength, ErrorMessage = "query must be at most 50 characters.")] string? query,
        string? cursor,
        [Range(1, MaxLimit, ErrorMessage = "limit must be a whole number from 1 to 50.")] int? limit,
        InstrumentService instruments,
        CancellationToken cancel) =>
        TypedResults.Ok(await instruments.SearchAsync(query, cursor, limit ?? DefaultLimit, cancel));

    private static async Task<Ok<InstrumentDetailResponse>> GetDetail(
        string symbol, ClaimsPrincipal principal, InstrumentService instruments, UserService users, CancellationToken cancel)
    {
        var user = await users.ResolveAsync(principal, cancel);
        return TypedResults.Ok(await instruments.GetDetailAsync(symbol, user.Id, cancel));
    }

    private static async Task<Ok<PriceSeriesResponse>> GetPrices(
        string symbol,
        [AllowedValues(PriceRanges.OneDay, PriceRanges.OneWeek, PriceRanges.OneMonth, PriceRanges.OneYear, PriceRanges.FiveYears, null,
            ErrorMessage = "range must be one of 1D, 1W, 1M, 1Y, 5Y.")] string? range,
        InstrumentService instruments,
        CancellationToken cancel) =>
        TypedResults.Ok(await instruments.GetPricesAsync(symbol, string.IsNullOrEmpty(range) ? PriceRanges.Default : range, cancel));
}
