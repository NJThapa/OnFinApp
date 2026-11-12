package com.onfinapp.api.analysis;

import java.time.Instant;
import java.util.List;

import com.onfinapp.api.analysis.AnalysisModels.AnalysisContent;
import com.onfinapp.api.analysis.AnalysisModels.KeyFigure;
import com.onfinapp.api.analysis.AnalysisModels.StoredAnalysis;
import com.onfinapp.api.llm.LlmResult;
import com.onfinapp.api.llm.Source;

/** Test data shared by the analysis tests. */
final class SampleAnalysis {

    static final String URL = "https://example.com/report";

    static final String JSON = """
            {
              "summary": "A summary.",
              "bullPoints": ["Bull."],
              "bearPoints": ["Bear."],
              "risks": ["Risk."],
              "keyFigures": [{"label": "P/E ratio", "value": "30.1", "asOf": "2026-11-11"}],
              "rating": "BUY",
              "confidence": "MEDIUM",
              "rationale": "Because.",
              "sources": [{"title": "Report", "url": "https://example.com/report"}]
            }
            """;

    private SampleAnalysis() {
    }

    static LlmResult llmResult(String json) {
        return new LlmResult(json,
                List.of(new Source("Report", URL), new Source("Other", "https://example.com/other")), 120, 340, "test-model");
    }

    static StoredAnalysis stored(String rating, boolean fresh, boolean young) {
        return new StoredAnalysis(
                new AnalysisContent("A summary.", List.of("Bull."), List.of("Bear."), List.of("Risk."),
                        List.of(new KeyFigure("P/E ratio", "30.1", "2026-11-11"))),
                List.of(new Source("Report", URL)),
                rating, "MEDIUM", "Because.",
                Instant.parse("2026-11-11T17:00:00Z"), Instant.parse("2026-11-12T17:00:00Z"), "dotnet", fresh, young);
    }
}
