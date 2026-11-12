using System.Diagnostics;
using Npgsql;
using OpenTelemetry;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace OnFinApp.Api.Common;

public static class TelemetrySetup
{
    private const string DefaultServiceName = "onfinapp-api-dotnet";

    /// Source of the spans the API starts itself, such as background generation jobs.
    public static readonly ActivitySource Source = new("OnFinApp.Api");

    /// Console logs as one JSON object per line, carrying the correlation ID and the trace and span IDs.
    public static void AddStructuredLogging(this WebApplicationBuilder builder)
    {
        builder.Logging.Configure(options =>
            options.ActivityTrackingOptions = ActivityTrackingOptions.TraceId | ActivityTrackingOptions.SpanId);
        builder.Logging.AddJsonConsole(options =>
        {
            options.IncludeScopes = true;
            options.UseUtcTimestamp = true;
            options.TimestampFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";
        });
    }

    /// OpenTelemetry traces, metrics and logs for incoming requests, outgoing HTTP calls and database
    /// statements. They are exported over OTLP only when OTEL_EXPORTER_OTLP_ENDPOINT is set; the standard
    /// OTEL_* variables configure the exporter. Health checks are not traced.
    public static void AddTelemetry(this WebApplicationBuilder builder)
    {
        var serviceName = builder.Configuration["OTEL_SERVICE_NAME"] ?? DefaultServiceName;

        builder.Logging.AddOpenTelemetry(options =>
        {
            options.IncludeScopes = true;
            options.IncludeFormattedMessage = true;
        });

        var telemetry = builder.Services.AddOpenTelemetry()
            .ConfigureResource(resource => resource.AddService(serviceName))
            .WithTracing(tracing => tracing
                .AddAspNetCoreInstrumentation(options =>
                    options.Filter = context => !context.Request.Path.StartsWithSegments("/api/health"))
                .AddHttpClientInstrumentation()
                .AddNpgsql()
                .AddSource(Source.Name))
            .WithMetrics(metrics => metrics
                .AddAspNetCoreInstrumentation()
                .AddHttpClientInstrumentation()
                .AddRuntimeInstrumentation());

        if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
        {
            telemetry.UseOtlpExporter();
        }
    }
}
