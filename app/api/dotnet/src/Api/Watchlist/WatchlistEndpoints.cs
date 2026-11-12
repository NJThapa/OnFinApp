using System.Security.Claims;
using Microsoft.AspNetCore.Http.HttpResults;
using OnFinApp.Api.Users;

namespace OnFinApp.Api.Watchlist;

public static class WatchlistEndpoints
{
    public static IServiceCollection AddWatchlist(this IServiceCollection services, IConfiguration config)
    {
        services.AddOptions<WatchlistOptions>()
            .Configure(options => options.Limit = config.GetValue("WATCHLIST_LIMIT", options.Limit))
            .Validate(options => options.Limit > 0, "WATCHLIST_LIMIT must be positive.")
            .ValidateOnStart();
        return services
            .AddSingleton<IWatchlistRepository, WatchlistRepository>()
            .AddSingleton<WatchlistService>();
    }

    public static void MapWatchlist(this RouteGroupBuilder v1)
    {
        var group = v1.MapGroup("/watchlist");
        group.MapGet("", Get);
        group.MapPut("/{symbol}", Follow);
        group.MapDelete("/{symbol}", Unfollow);
    }

    private static async Task<Ok<WatchlistResponse>> Get(
        ClaimsPrincipal principal, WatchlistService watchlist, UserService users, CancellationToken cancel)
    {
        var user = await users.ResolveAsync(principal, cancel);
        return TypedResults.Ok(await watchlist.GetAsync(user.Id, cancel));
    }

    private static async Task<NoContent> Follow(
        string symbol, ClaimsPrincipal principal, WatchlistService watchlist, UserService users, CancellationToken cancel)
    {
        var user = await users.ResolveAsync(principal, cancel);
        await watchlist.FollowAsync(user.Id, symbol, cancel);
        return TypedResults.NoContent();
    }

    private static async Task<NoContent> Unfollow(
        string symbol, ClaimsPrincipal principal, WatchlistService watchlist, UserService users, CancellationToken cancel)
    {
        var user = await users.ResolveAsync(principal, cancel);
        await watchlist.UnfollowAsync(user.Id, symbol, cancel);
        return TypedResults.NoContent();
    }
}
