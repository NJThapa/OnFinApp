using Microsoft.Extensions.Options;

namespace OnFinApp.Api.Prices;

/// Price-data settings. Bound from PRICE_PROVIDER, SHARED_DIR and INSTRUMENT_SYNC_INTERVAL_HOURS.
public sealed class PriceOptions
{
    /// "mock" generates invented prices from the fixtures in the shared folder; no API key needed.
    public string Provider { get; set; } = "mock";

    /// Folder shared by both APIs (app/api/shared): mock fixtures now, LLM prompts later.
    public string SharedDir { get; set; } = "../../../shared";

    public int InstrumentSyncIntervalHours { get; set; } = 24;
}

public static class PriceSetup
{
    public static IServiceCollection AddPrices(this IServiceCollection services, IConfiguration config)
    {
        services.AddOptions<PriceOptions>()
            .Configure(options =>
            {
                options.Provider = config["PRICE_PROVIDER"] ?? options.Provider;
                options.SharedDir = config["SHARED_DIR"] ?? options.SharedDir;
                options.InstrumentSyncIntervalHours = config.GetValue("INSTRUMENT_SYNC_INTERVAL_HOURS", options.InstrumentSyncIntervalHours);
            })
            .Validate(options => options.InstrumentSyncIntervalHours > 0, "INSTRUMENT_SYNC_INTERVAL_HOURS must be positive.")
            .ValidateOnStart();

        services.AddSingleton<IPriceProvider>(provider =>
        {
            var options = provider.GetRequiredService<IOptions<PriceOptions>>().Value;
            provider.GetRequiredService<ILoggerFactory>().CreateLogger("OnFinApp.Api.Prices")
                .LogInformation("Using the {Provider} price provider", options.Provider);
            return options.Provider switch
            {
                "mock" => MockPriceProvider.FromSharedFolder(options.SharedDir, provider.GetRequiredService<TimeProvider>()),
                _ => throw new InvalidOperationException($"Unknown PRICE_PROVIDER '{options.Provider}'."),
            };
        });
        services.AddSingleton<IPriceCacheRepository, PriceCacheRepository>();
        services.AddSingleton<PriceService>();
        return services;
    }
}
