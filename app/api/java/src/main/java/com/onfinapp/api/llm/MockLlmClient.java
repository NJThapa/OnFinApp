package com.onfinapp.api.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deterministic invented analyses; no network. The rules are specified in shared/llm/README.md
 * and implemented identically in the .NET API.
 */
public class MockLlmClient implements LlmClient {

    public static final String MODEL_NAME = "mock";

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] RATINGS = {"BUY", "HOLD", "SELL"};
    private static final String[] CONFIDENCES = {"LOW", "MEDIUM", "HIGH"};
    private static final String[] RATIONALE_ENDINGS = {"point up.", "are mixed.", "point down."};

    private record KeyFigure(String label, String value, String asOf) {
    }

    private record Answer(
            String summary,
            List<String> bullPoints,
            List<String> bearPoints,
            List<String> risks,
            List<KeyFigure> keyFigures,
            String rating,
            String confidence,
            String rationale,
            List<Source> sources) {
    }

    private final Clock clock;
    private final Duration delay;
    private final Set<String> failSymbols;

    public MockLlmClient(Clock clock, Duration delay, Set<String> failSymbols) {
        this.clock = clock;
        this.delay = delay;
        this.failSymbols = failSymbols;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String symbol = request.variables().get("symbol");
        String name = request.variables().get("name");
        boolean etf = "ETF".equals(request.variables().get("type"));

        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("The mock LLM was interrupted.", e);
            }
        }
        if (failSymbols.contains(symbol)) {
            throw new LlmException("The mock LLM is set to fail for " + symbol + ".");
        }

        long h = 7;
        for (int i = 0; i < symbol.length(); i++) {
            h = (h * 31 + symbol.charAt(i)) % 100003;
        }
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        long d = today.toEpochDay();
        String date = today.toString();
        int rating = (int) ((h + d) % 3);

        List<Source> sources = List.of(
                new Source(name + " investor relations (mock)", "https://example.com/mock/" + symbol + "/investor-relations"),
                new Source(symbol + " latest quarterly report (mock)", "https://example.com/mock/" + symbol + "/quarterly-report"),
                new Source("Market commentary on " + symbol + " (mock)", "https://example.com/mock/" + symbol + "/market-commentary"));

        String notResearch = " This text is generated from fixtures by the mock LLM; it is not real research.";
        Answer answer = new Answer(
                etf
                        ? "Mock analysis of the fund " + name + " (" + symbol + ")." + notResearch
                        : "Mock analysis of " + name + " (" + symbol + ")." + notResearch,
                etf
                        ? List.of(
                                "Mock bull point: " + symbol + " spreads its holdings widely.",
                                "Mock bull point: the expense ratio is low for its category.")
                        : List.of(
                                "Mock bull point: " + name + " keeps growing revenue in its core business.",
                                "Mock bull point: margins leave room to keep investing.",
                                "Mock bull point: the balance sheet is strong."),
                etf
                        ? List.of(
                                "Mock bear point: the largest holdings make up a big share of " + symbol + ".",
                                "Mock bear point: the recent trend has been flat.")
                        : List.of(
                                "Mock bear point: " + symbol + " trades at a high valuation.",
                                "Mock bear point: growth is slowing in the largest segment."),
                etf
                        ? List.of("Mock risk: a broad market decline.", "Mock risk: concentration in a few sectors.")
                        : List.of("Mock risk: tougher competition.", "Mock risk: regulatory changes."),
                etf
                        ? List.of(
                                new KeyFigure("Expense ratio", hundredths(3 + h % 70) + "%", date),
                                new KeyFigure("Top 10 holdings weight", tenths(150 + h % 450) + "%", date),
                                new KeyFigure("1-year return", tenths(h % 400 - 100) + "%", date))
                        : List.of(
                                new KeyFigure("Revenue growth (YoY)", tenths(h % 400 - 100) + "%", date),
                                new KeyFigure("Operating margin", tenths(50 + h % 350) + "%", date),
                                new KeyFigure("P/E ratio", tenths(80 + h % 420), date)),
                RATINGS[rating],
                CONFIDENCES[(int) (h / 7 % 3)],
                "Mock rationale: the invented figures for " + symbol + " " + RATIONALE_ENDINGS[rating],
                sources);

        try {
            return new LlmResult(JSON.writeValueAsString(answer), sources,
                    request.prompt().length() / 4, (int) (400 + h % 200), MODEL_NAME);
        } catch (JsonProcessingException e) {
            throw new LlmException("The mock LLM could not write its answer.", e);
        }
    }

    private static String tenths(long value) {
        long abs = Math.abs(value);
        return (value < 0 ? "-" : "") + abs / 10 + "." + abs % 10;
    }

    private static String hundredths(long value) {
        return value / 100 + "." + String.format("%02d", value % 100);
    }
}
