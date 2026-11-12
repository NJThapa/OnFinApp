package com.onfinapp.api.analysis;

import java.time.Instant;
import java.util.List;

import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.llm.Source;

/** Analysis data and the response bodies defined in the shared contract. */
public final class AnalysisModels {

    public static final String READY = "READY";
    public static final String GENERATING = "GENERATING";
    public static final String FAILED = "FAILED";

    private AnalysisModels() {
    }

    public record KeyFigure(String label, String value, String asOf) {
    }

    /** The body of an analysis, as stored in analyses.content. */
    public record AnalysisContent(
            String summary, List<String> bullPoints, List<String> bearPoints, List<String> risks, List<KeyFigure> keyFigures) {
    }

    /** An LLM answer that passed every check and is ready to store. */
    public record GeneratedAnalysis(
            AnalysisContent content, List<Source> sources, String rating, String confidence, String rationale) {
    }

    /** How a generation went: stored with the analysis and written to the log. */
    public record GenerationStats(String model, int durationMs, int inputTokens, int outputTokens) {
    }

    /**
     * @param fresh not expired yet
     * @param young younger than the minimum age for a user refresh
     */
    public record StoredAnalysis(
            AnalysisContent content,
            List<Source> sources,
            String rating,
            String confidence,
            String rationale,
            Instant generatedAt,
            Instant expiresAt,
            String generatedBy,
            boolean fresh,
            boolean young) {
    }

    public record RatingRow(long seq, String rating, String confidence, String rationale, Instant createdAt) {
    }

    public record AnalysisResponse(
            String summary,
            List<String> bullPoints,
            List<String> bearPoints,
            List<String> risks,
            List<KeyFigure> keyFigures,
            List<Source> sources,
            String rating,
            String confidence,
            String rationale,
            Instant generatedAt,
            Instant expiresAt,
            String generatedBy) {

        public static AnalysisResponse from(StoredAnalysis a) {
            return new AnalysisResponse(
                    a.content().summary(), a.content().bullPoints(), a.content().bearPoints(), a.content().risks(),
                    a.content().keyFigures(), a.sources(), a.rating(), a.confidence(), a.rationale(),
                    a.generatedAt(), a.expiresAt(), a.generatedBy());
        }
    }

    public record AnalysisStatusResponse(String symbol, String status, AnalysisResponse analysis) {

        public static AnalysisStatusResponse of(Instrument instrument, String status, StoredAnalysis latest) {
            return new AnalysisStatusResponse(
                    instrument.symbol(), status, latest == null ? null : AnalysisResponse.from(latest));
        }
    }

    public record RatingEntryResponse(String rating, String confidence, String rationale, Instant createdAt) {
    }

    public record RatingPageResponse(List<RatingEntryResponse> items, String nextCursor) {
    }
}
