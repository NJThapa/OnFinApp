package com.onfinapp.api.llm;

/**
 * The language model behind news, analyses and ratings. Chosen with LLM_PROVIDER.
 * Implementations throw {@link LlmException} when they cannot answer.
 */
public interface LlmClient {

    LlmResult generate(LlmRequest request);
}
