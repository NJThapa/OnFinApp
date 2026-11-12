namespace OnFinApp.Api.Llm;

/// A web page the LLM's search tool returned or the answer cites.
public sealed record Source(string Title, string Url);

/// <param name="Prompt">The full prompt text.</param>
/// <param name="Schema">JSON schema the answer must match; sent to the vendor for schema-constrained output.</param>
/// <param name="WebSearch">Whether the model may use its web search tool.</param>
/// <param name="Variables">The values that were put into the prompt. Vendors ignore them; the mock builds its answer from them.</param>
public sealed record LlmRequest(string Prompt, string Schema, bool WebSearch, IReadOnlyDictionary<string, string> Variables);

/// <param name="Json">The answer, as JSON text.</param>
/// <param name="Sources">Every page the search tool returned.</param>
public sealed record LlmResult(string Json, IReadOnlyList<Source> Sources, int InputTokens, int OutputTokens, string Model);

/// The language model behind news, analyses and ratings. Chosen with LLM_PROVIDER.
/// Implementations throw <see cref="LlmException"/> when they cannot answer.
public interface ILlmClient
{
    Task<LlmResult> GenerateAsync(LlmRequest request, CancellationToken cancel);
}

/// Thrown by an LLM client that cannot answer.
public sealed class LlmException(string message, Exception? inner = null) : Exception(message, inner);
