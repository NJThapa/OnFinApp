package com.onfinapp.api.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onfinapp.api.analysis.AnalysisModels.AnalysisContent;
import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisModels.KeyFigure;
import com.onfinapp.api.llm.LlmResult;
import com.onfinapp.api.llm.Source;

/**
 * Turns an LLM answer into a {@link GeneratedAnalysis}, or rejects it.
 * The rules are listed in shared/llm/README.md and are the same in the .NET API.
 */
public final class AnalysisParser {

    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Set<String> RATINGS = Set.of("BUY", "HOLD", "SELL");
    private static final Set<String> CONFIDENCES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The answer as the LLM wrote it: anything may be missing. */
    private record Answer(
            String summary,
            List<String> bullPoints,
            List<String> bearPoints,
            List<String> risks,
            List<AnswerFigure> keyFigures,
            String rating,
            String confidence,
            String rationale,
            List<AnswerSource> sources) {
    }

    private record AnswerFigure(String label, String value, String asOf) {
    }

    private record AnswerSource(String title, String url) {
    }

    private AnalysisParser() {
    }

    public static GeneratedAnalysis parse(LlmResult result) {
        Answer answer;
        try {
            answer = JSON.readValue(result.json(), Answer.class);
        } catch (JsonProcessingException e) {
            throw new InvalidAnalysisException("it is not JSON of the expected shape.");
        }
        if (answer == null) {
            throw new InvalidAnalysisException("it is empty.");
        }

        String summary = text(answer.summary(), "summary");
        List<String> bullPoints = points(answer.bullPoints(), "bullPoints");
        List<String> bearPoints = points(answer.bearPoints(), "bearPoints");
        List<String> risks = points(answer.risks(), "risks");
        if (answer.keyFigures() == null) {
            throw new InvalidAnalysisException("keyFigures is missing.");
        }
        List<KeyFigure> keyFigures = new ArrayList<>();
        for (AnswerFigure figure : answer.keyFigures()) {
            String label = text(figure == null ? null : figure.label(), "a key figure label");
            String value = text(figure.value(), "a key figure value");
            if (figure.asOf() == null || !ISO_DATE.matcher(figure.asOf()).matches()) {
                throw new InvalidAnalysisException("a key figure has no asOf date written as YYYY-MM-DD.");
            }
            keyFigures.add(new KeyFigure(label, value, figure.asOf()));
        }

        if (answer.rating() == null || !RATINGS.contains(answer.rating())) {
            throw new InvalidAnalysisException("rating must be BUY, HOLD or SELL.");
        }
        if (answer.confidence() == null || !CONFIDENCES.contains(answer.confidence())) {
            throw new InvalidAnalysisException("confidence must be LOW, MEDIUM or HIGH.");
        }

        // A rating must rest on sources, and only on pages the search tool really returned.
        if (answer.sources() == null || answer.sources().isEmpty()) {
            throw new InvalidAnalysisException("it cites no sources.");
        }
        Set<String> returned = result.sources().stream().map(Source::url).collect(Collectors.toSet());
        List<Source> sources = new ArrayList<>();
        for (AnswerSource source : answer.sources()) {
            String url = source == null ? null : source.url();
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                throw new InvalidAnalysisException("a source has no http(s) URL.");
            }
            if (!returned.contains(url)) {
                throw new InvalidAnalysisException("it cites a source the search tool did not return.");
            }
            sources.add(new Source(text(source.title(), "a source title"), url));
        }

        return new GeneratedAnalysis(
                new AnalysisContent(summary, bullPoints, bearPoints, risks, keyFigures),
                sources, answer.rating(), answer.confidence(), text(answer.rationale(), "rationale"));
    }

    private static String text(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new InvalidAnalysisException(what + " is missing or blank.");
        }
        return value;
    }

    private static List<String> points(List<String> values, String what) {
        if (values == null || values.isEmpty()) {
            throw new InvalidAnalysisException(what + " is missing or empty.");
        }
        return values.stream().map(value -> text(value, "an entry of " + what)).toList();
    }
}
