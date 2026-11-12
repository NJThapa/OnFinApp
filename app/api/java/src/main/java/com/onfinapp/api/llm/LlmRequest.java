package com.onfinapp.api.llm;

import java.util.Map;

/**
 * @param prompt the full prompt text
 * @param schema JSON schema the answer must match; sent to the vendor for schema-constrained output
 * @param webSearch whether the model may use its web search tool
 * @param variables the values that were put into the prompt. Vendors ignore them; the mock builds its answer from them
 */
public record LlmRequest(String prompt, String schema, boolean webSearch, Map<String, String> variables) {
}
