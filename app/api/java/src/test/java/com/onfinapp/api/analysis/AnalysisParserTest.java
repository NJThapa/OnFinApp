package com.onfinapp.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisModels.KeyFigure;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.llm.Source;

class AnalysisParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode valid() {
        try {
            return (ObjectNode) JSON.readTree(SampleAnalysis.JSON);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GeneratedAnalysis parse(ObjectNode answer) {
        return AnalysisParser.parse(SampleAnalysis.llmResult(answer.toString()));
    }

    private static ObjectNode first(ObjectNode answer, String list) {
        return (ObjectNode) answer.get(list).get(0);
    }

    @Test
    void aValidAnswerIsAccepted() {
        GeneratedAnalysis analysis = parse(valid());

        assertThat(analysis.rating()).isEqualTo("BUY");
        assertThat(analysis.confidence()).isEqualTo("MEDIUM");
        assertThat(analysis.rationale()).isEqualTo("Because.");
        assertThat(analysis.content().summary()).isEqualTo("A summary.");
        assertThat(analysis.content().keyFigures()).containsExactly(new KeyFigure("P/E ratio", "30.1", "2026-11-11"));
        assertThat(analysis.sources()).containsExactly(new Source("Report", SampleAnalysis.URL));
    }

    @Test
    void anAnswerMayHaveNoKeyFigures() {
        ObjectNode answer = valid();
        answer.putArray("keyFigures");

        assertThat(parse(answer).content().keyFigures()).isEmpty();
    }

    static Stream<Arguments> invalidAnswers() {
        return Stream.of(
                invalid("summary is missing or blank.", a -> a.put("summary", " ")),
                invalid("bullPoints is missing or empty.", a -> a.remove("bullPoints")),
                invalid("bearPoints is missing or empty.", a -> a.putArray("bearPoints")),
                invalid("an entry of risks is missing or blank.", a -> a.putArray("risks").add("")),
                invalid("keyFigures is missing.", a -> a.remove("keyFigures")),
                invalid("a key figure has no asOf date written as YYYY-MM-DD.",
                        a -> first(a, "keyFigures").put("asOf", "11 Nov 2026")),
                invalid("a key figure value is missing or blank.", a -> first(a, "keyFigures").remove("value")),
                invalid("rating must be BUY, HOLD or SELL.", a -> a.put("rating", "STRONG BUY")),
                invalid("confidence must be LOW, MEDIUM or HIGH.", a -> a.remove("confidence")),
                invalid("rationale is missing or blank.", a -> a.put("rationale", "")),
                invalid("it cites no sources.", a -> a.putArray("sources")),
                invalid("it cites no sources.", a -> a.remove("sources")),
                invalid("a source has no http(s) URL.", a -> first(a, "sources").put("url", "ftp://example.com/report")),
                invalid("it cites a source the search tool did not return.",
                        a -> first(a, "sources").put("url", "https://example.com/invented")),
                invalid("a source title is missing or blank.", a -> first(a, "sources").put("title", "")),
                invalid("it is not JSON of the expected shape.", a -> a.put("bullPoints", "not a list")));
    }

    private static Arguments invalid(String reason, Consumer<ObjectNode> change) {
        return Arguments.of(reason, change);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAnswers")
    void anInvalidAnswerIsRejected(String reason, Consumer<ObjectNode> change) {
        ObjectNode answer = valid();
        change.accept(answer);

        assertThatThrownBy(() -> parse(answer))
                .isInstanceOf(InvalidAnalysisException.class)
                .hasMessage("The LLM answer is not valid: " + reason);
    }

    @Test
    void textThatIsNotJsonIsRejected() {
        assertThatThrownBy(() -> AnalysisParser.parse(SampleAnalysis.llmResult("Sorry, I cannot help.")))
                .isInstanceOf(InvalidAnalysisException.class);
    }

    @Test
    void ratingCursorRoundTripsAndEncodesAsBase64UrlOfTheSequenceNumber() {
        assertThat(RatingCursor.decode(RatingCursor.encode(42))).isEqualTo(42);
        // Both APIs must produce the same cursor: base64url("42") without padding.
        assertThat(RatingCursor.encode(42)).isEqualTo("NDI");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "!!!",
        "QUFQTA", // "AAPL"
        "MA", // "0"
        "LTU", // "-5"
        "MTIzNDU2Nzg5MDEyMzQ1Njc4OQ", // 19 digits
    })
    void ratingCursorRejectsAnythingElse(String cursor) {
        assertThatThrownBy(() -> RatingCursor.decode(cursor)).isInstanceOf(ApiProblemException.InvalidCursor.class);
    }
}
