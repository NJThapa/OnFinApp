using System.Security.Claims;
using Npgsql;
using NpgsqlTypes;

namespace OnFinApp.Api.Users;

public sealed record User(Guid Id, string? Email, string? Name);

/// <param name="Created">True when this request created the row.</param>
public sealed record UpsertedUser(User User, bool Created);

public interface IUserRepository
{
    /// Creates or updates the row keyed by (issuer, subject) and returns it.
    Task<UpsertedUser> UpsertAsync(string issuer, string subject, string? email, string? name, CancellationToken cancel);
}

public sealed class UserRepository(NpgsqlDataSource db) : IUserRepository
{
    private const string Upsert = """
        insert into users (issuer, subject, email, name)
        values ($1, $2, $3, $4)
        on conflict (issuer, subject)
        do update set email = excluded.email, name = excluded.name, updated_at = now()
        returning id, email, name, (xmax = 0) as created
        """;

    public async Task<UpsertedUser> UpsertAsync(string issuer, string subject, string? email, string? name, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Upsert);
        cmd.Parameters.Add(new() { Value = issuer });
        cmd.Parameters.Add(new() { Value = subject });
        cmd.Parameters.Add(new() { Value = (object?)email ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Text });
        cmd.Parameters.Add(new() { Value = (object?)name ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Text });

        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        await reader.ReadAsync(cancel);
        var user = new User(
            reader.GetGuid(0),
            reader.IsDBNull(1) ? null : reader.GetString(1),
            reader.IsDBNull(2) ? null : reader.GetString(2));
        return new UpsertedUser(user, reader.GetBoolean(3));
    }
}

/// Maps the authenticated token to the local user row, creating it on first sight.
public sealed class UserService(IUserRepository users, ILogger<UserService> log)
{
    public async Task<User> ResolveAsync(ClaimsPrincipal principal, CancellationToken cancel)
    {
        var upserted = await users.UpsertAsync(
            principal.FindFirstValue("iss") ?? throw new InvalidOperationException("The token has no iss claim."),
            principal.FindFirstValue("sub") ?? throw new InvalidOperationException("The token has no sub claim."),
            principal.FindFirstValue("email"),
            principal.FindFirstValue("name"),
            cancel);
        if (upserted.Created) log.LogInformation("Created user {UserId} on first sign-in", upserted.User.Id);
        return upserted.User;
    }
}

public static class UserEndpoints
{
    public static IServiceCollection AddUsers(this IServiceCollection services) =>
        services.AddSingleton<IUserRepository, UserRepository>().AddSingleton<UserService>();

    public static void MapUsers(this RouteGroupBuilder v1) =>
        v1.MapGet("/me", async (ClaimsPrincipal principal, UserService users, CancellationToken cancel) =>
            TypedResults.Ok(await users.ResolveAsync(principal, cancel)));
}
