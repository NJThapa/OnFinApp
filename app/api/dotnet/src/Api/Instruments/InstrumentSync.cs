using Microsoft.Extensions.Options;
using OnFinApp.Api.Prices;

namespace OnFinApp.Api.Instruments;

/// Copies the price provider's instrument list into the instruments table at startup and then on an interval.
public sealed class InstrumentSync(
    IPriceProvider provider,
    IInstrumentRepository instruments,
    IOptions<PriceOptions> options,
    ILogger<InstrumentSync> log) : BackgroundService
{
    private static readonly TimeSpan RetryDelay = TimeSpan.FromMinutes(1);

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        var interval = TimeSpan.FromHours(options.Value.InstrumentSyncIntervalHours);
        while (!stop.IsCancellationRequested)
        {
            var wait = interval;
            try
            {
                var list = await provider.ListInstrumentsAsync(stop);
                await instruments.UpsertAsync(list, stop);
                log.LogInformation("Synced {Count} instruments from the price provider", list.Count);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                // Database or provider down: try again soon.
                log.LogWarning("Instrument sync failed, retrying in a minute: {Error}", e.Message);
                wait = RetryDelay;
            }

            try
            {
                await Task.Delay(wait, stop);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }
}
