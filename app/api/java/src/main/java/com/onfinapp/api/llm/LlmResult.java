package com.onfinapp.api.llm;

import java.util.List;

/**
 * @param json the answer, as JSON text
 * @param sources every page the search tool returned
 */
public record LlmResult(String json, List<Source> sources, int inputTokens, int outputTokens, String model) {
}
