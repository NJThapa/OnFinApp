package com.onfinapp.api.analysis;

/** Thrown when an LLM answer fails the checks in {@link AnalysisParser}. */
public class InvalidAnalysisException extends RuntimeException {

    public InvalidAnalysisException(String reason) {
        super("The LLM answer is not valid: " + reason);
    }
}
