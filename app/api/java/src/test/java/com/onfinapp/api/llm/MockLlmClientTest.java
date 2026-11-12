package com.onfinapp.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisParser;

/**
 * Expected answers are the files in app/api/shared/llm/mock-examples, computed independently from the rules
 * in app/api/shared/llm/README.md. The .NET API's MockLlmClientTests compares against the same files.
 */
class MockLlmClientTest {

    /** The folder shared by both APIs, relative to app/api/java where the tests run. */
    static final String SHARED_DIR = "../shared";

    private static final Instant WEDNESDAY = Instant.parse("2026-11-11T17:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static MockLlmClient at(Instant now, String... failSymbols) {
        return new MockLlmClient(Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO, Set.of(failSymbols));
    }

    private static LlmRequest request(String symbol, String name, String type) {
        return new LlmRequest("A prompt of 28 characters.!!", "{}", true,
                Map.of("symbol", symbol, "name", name, "type", type, "exchange", "NYSE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "SPY", "BRK.B"})
    void answersMatchTheSharedExamples(String symbol) throws Exception {
        JsonNode example = JSON.readTree(Path.of(SHARED_DIR, "llm", "mock-examples", symbol + ".json").toFile());
        JsonNode instrument = example.get("instrument");

        LlmResult result = at(WEDNESDAY)
                .generate(request(symbol, instrument.get("name").asText(), instrument.get("type").asText()));

        assertThat(JSON.readTree(result.json())).isEqualTo(example.get("answer"));
        assertThat(JSON.valueToTree(result.sources()).toString()).isEqualTo(example.get("searchResults").toString());
        assertThat(result.outputTokens()).isEqualTo(example.get("outputTokens").asInt());
        assertThat(result.inputTokens()).isEqualTo(7); // 28 characters / 4
        assertThat(result.model()).isEqualTo("mock");
    }

    @Test
    void theRatingChangesFromDayToDay() {
        assertThat(ratingAt(WEDNESDAY)).isEqualTo("HOLD");
        assertThat(ratingAt(WEDNESDAY.plus(Duration.ofDays(1)))).isEqualTo("SELL");
        assertThat(ratingAt(WEDNESDAY.plus(Duration.ofDays(2)))).isEqualTo("BUY");
    }

    @Test
    void theDayIsTheNewYorkCalendarDay() {
        // 03:00 UTC on the 12th is still the 11th in New York.
        GeneratedAnalysis analysis = AnalysisParser.parse(
                at(Instant.parse("2026-11-12T03:00:00Z")).generate(request("AAPL", "Apple Inc.", "STOCK")));

        assertThat(analysis.rating()).isEqualTo("HOLD");
        assertThat(analysis.content().keyFigures()).allMatch(figure -> figure.asOf().equals("2026-11-11"));
    }

    @Test
    void failsForTheConfiguredTickers() {
        assertThatThrownBy(() -> at(WEDNESDAY, "AAPL").generate(request("AAPL", "Apple Inc.", "STOCK")))
                .isInstanceOf(LlmException.class)
                .hasMessage("The mock LLM is set to fail for AAPL.");
    }

    @Test
    void theSharedAnalysisPromptLoadsAndRenders() throws Exception {
        PromptTemplate template = PromptTemplate.load(SHARED_DIR, "analysis");

        String prompt = template.render(
                Map.of("symbol", "AAPL", "name", "Apple Inc.", "type", "STOCK", "exchange", "NASDAQ"));

        assertThat(prompt).contains("- Ticker: AAPL", "- Name: Apple Inc.").doesNotContain("{{");
        assertThat(JSON.readTree(template.schema()).get("title").asText()).isEqualTo("Analysis");
    }

    private static String ratingAt(Instant now) {
        return AnalysisParser.parse(at(now).generate(request("AAPL", "Apple Inc.", "STOCK"))).rating();
    }
}
