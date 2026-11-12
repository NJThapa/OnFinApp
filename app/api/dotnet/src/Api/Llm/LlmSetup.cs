using Microsoft.Extensions.Options;

namespace OnFinApp.Api.Llm;

/// LLM settings. Bound from LLM_PROVIDER, SHARED_DIR, LLM_MOCK_DELAY_MS and LLM_MOCK_FAIL_SYMBOLS.
public sealed class LlmOptions
{
    /// "mock" answers from fixed rules; no API key needed.
    public string Provider { get; set; } = "mock";

    /// Folder shared by both APIs (app/api/shared); prompts and schemas are in its llm folder.
    public string SharedDir { get; set; } = "../../../shared";

    /// How long the mock waits before answering, so the "generating" state is visible.
    public int MockDelayMs { get; set; } = 1500;

    /// Comma-separated tickers for which the mock fails.
    public string MockFailSymbols { get; set; } = "";
}

/// A prompt and the JSON schema of its answer, read from the shared llm folder.
public sealed record PromptTemplate(string Text, string Schema)
{
    public static PromptTemplate Load(string sharedDir, string name)
    {
        var folder = Path.Combine(sharedDir, "llm");
        try
        {
            return new PromptTemplate(
                File.ReadAllText(Path.Combine(folder, $"{name}.prompt.md")),
                File.ReadAllText(Path.Combine(folder, $"{name}.schema.json")));
        }
        catch (IOException e)
        {
            throw new InvalidOperationException($"Cannot read the {name} prompt and schema in {Path.GetFullPath(folder)}.", e);
        }
    }

    /// The prompt with every {{key}} replaced by its value.
    public string Render(IReadOnlyDictionary<string, string> variables)
    {
        var text = Text;
        foreach (var (key, value) in variables) text = text.Replace("{{" + key + "}}", value);
        return text;
    }
}

public static class LlmSetup
{
    public static IServiceCollection AddLlm(this IServiceCollection services, IConfiguration config)
    {
        services.AddOptions<LlmOptions>()
            .Configure(options =>
            {
                options.Provider = config["LLM_PROVIDER"] ?? options.Provider;
                options.SharedDir = config["SHARED_DIR"] ?? options.SharedDir;
                options.MockDelayMs = config.GetValue("LLM_MOCK_DELAY_MS", options.MockDelayMs);
                options.MockFailSymbols = config["LLM_MOCK_FAIL_SYMBOLS"] ?? options.MockFailSymbols;
            })
            .Validate(options => options.MockDelayMs >= 0, "LLM_MOCK_DELAY_MS must not be negative.")
            .ValidateOnStart();

        services.AddSingleton<ILlmClient>(provider =>
        {
            var options = provider.GetRequiredService<IOptions<LlmOptions>>().Value;
            provider.GetRequiredService<ILoggerFactory>().CreateLogger("OnFinApp.Api.Llm")
                .LogInformation("Using the {Provider} LLM", options.Provider);
            return options.Provider switch
            {
                "mock" => new MockLlmClient(
                    provider.GetRequiredService<TimeProvider>(),
                    TimeSpan.FromMilliseconds(options.MockDelayMs),
                    options.MockFailSymbols
                        .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                        .Select(symbol => symbol.ToUpperInvariant())
                        .ToHashSet()),
                _ => throw new InvalidOperationException($"Unknown LLM_PROVIDER '{options.Provider}'."),
            };
        });
        return services;
    }
}
