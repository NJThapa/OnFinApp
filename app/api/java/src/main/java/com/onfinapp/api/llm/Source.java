package com.onfinapp.api.llm;

/** A web page the LLM's search tool returned or the answer cites. */
public record Source(String title, String url) {
}
