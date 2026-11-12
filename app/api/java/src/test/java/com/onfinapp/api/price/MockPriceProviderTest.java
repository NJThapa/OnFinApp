package com.onfinapp.api.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.onfinapp.api.price.MockPriceProvider.MockInstrument;

/**
 * Expected values were computed independently from the formulas in app/api/shared/mock/README.md.
 * The .NET API's MockPriceProviderTests asserts the same numbers.
 */
class MockPriceProviderTest {

    private static final List<MockInstrument> FIXTURES = List.of(
            new MockInstrument("AAPL", "Apple Inc.", "STOCK", "NASDAQ", 100),
            new MockInstrument("MSFT", "Microsoft Corporation", "STOCK", "NASDAQ", 200),
            new MockInstrument("BRK.B", "Berkshire Hathaway Inc. Class B", "STOCK", "NYSE", 420));

    private static final Instant WEDNESDAY = Instant.parse("2026-11-11T17:00:00Z");

    private static MockPriceProvider at(Instant now) {
        return new MockPriceProvider(FIXTURES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PricePoint point(String time, double close) {
        return new PricePoint(Instant.parse(time), close);
    }

    @Test
    void quoteOnATradingDay() {
        assertThat(at(WEDNESDAY).quote("AAPL")).isEqualTo(new Quote(111.83, -0.65, -0.58, 112.48, WEDNESDAY));
    }

    @Test
    void quoteForATickerWithADot() {
        Quote quote = at(WEDNESDAY).quote("BRK.B");

        assertThat(List.of(quote.price(), quote.change(), quote.changePercent(), quote.previousClose()))
                .containsExactly(387.91, -0.61, -0.16, 388.52);
    }

    @Test
    void quoteAtTheWeekendIsFridays() {
        Quote saturday = at(Instant.parse("2026-11-14T17:00:00Z")).quote("AAPL");

        assertThat(List.of(saturday.price(), saturday.change(), saturday.changePercent(), saturday.previousClose()))
                .containsExactly(110.99, -0.33, -0.3, 111.32);
    }

    @Test
    void theDayChangesAtMidnightInNewYorkNotUtc() {
        // 01:00 UTC on the 12th is still the evening of the 11th in New York.
        assertThat(at(Instant.parse("2026-11-12T01:00:00Z")).quote("AAPL").price()).isEqualTo(111.83);
    }

    @Test
    void quoteTruncatesAsOfToWholeSeconds() {
        assertThat(at(WEDNESDAY.plusMillis(789)).quote("AAPL").asOf()).isEqualTo(WEDNESDAY);
    }

    @Test
    void oneDayHas79FiveMinutePointsFromPreviousCloseToClose() {
        List<PricePoint> points = at(WEDNESDAY).candles("MSFT", PriceRanges.ONE_DAY);

        assertThat(points).hasSize(79);
        assertThat(points.get(0)).isEqualTo(point("2026-11-11T14:30:00Z", 226.31));
        assertThat(points.get(1)).isEqualTo(point("2026-11-11T14:35:00Z", 226.31));
        assertThat(points.get(40)).isEqualTo(point("2026-11-11T17:50:00Z", 224.45));
        assertThat(points.get(78)).isEqualTo(point("2026-11-11T21:00:00Z", 224.21));
    }

    @Test
    void oneWeekHasSevenHourlyPointsForEachOfFiveTradingDays() {
        List<PricePoint> points = at(WEDNESDAY).candles("MSFT", PriceRanges.ONE_WEEK);

        assertThat(points).hasSize(35);
        assertThat(points.get(0).time()).isEqualTo("2026-11-05T14:30:00Z");
        assertThat(points.get(7)).isEqualTo(point("2026-11-06T14:30:00Z", 235.73));
        assertThat(points.get(34)).isEqualTo(point("2026-11-11T20:30:00Z", 224.21));
    }

    @Test
    void oneMonthHasACloseForEachTradingDay() {
        List<PricePoint> points = at(WEDNESDAY).candles("MSFT", PriceRanges.ONE_MONTH);

        assertThat(points).hasSize(23);
        assertThat(points.get(0)).isEqualTo(point("2026-10-12T21:00:00Z", 230.59));
        assertThat(points.get(22)).isEqualTo(point("2026-11-11T21:00:00Z", 224.21));
        assertThat(points).noneMatch(p -> dayOfWeek(p) == DayOfWeek.SATURDAY || dayOfWeek(p) == DayOfWeek.SUNDAY);
    }

    @Test
    void oneYearAndFiveYears() {
        List<PricePoint> year = at(WEDNESDAY).candles("MSFT", PriceRanges.ONE_YEAR);
        List<PricePoint> fiveYears = at(WEDNESDAY).candles("MSFT", PriceRanges.FIVE_YEARS);

        assertThat(year).hasSize(262);
        assertThat(fiveYears).hasSize(261);
        assertThat(fiveYears).allMatch(p -> dayOfWeek(p) == DayOfWeek.FRIDAY);
        assertThat(fiveYears.get(0)).isEqualTo(point("2021-11-12T21:00:00Z", 219.35));
        assertThat(fiveYears.get(260)).isEqualTo(point("2026-11-06T21:00:00Z", 234.24));
    }

    @Test
    void latestDailyCloseEqualsTheQuote() {
        MockPriceProvider provider = at(WEDNESDAY);
        Quote quote = provider.quote("MSFT");
        List<PricePoint> month = provider.candles("MSFT", PriceRanges.ONE_MONTH);

        assertThat(month.get(month.size() - 1).close()).isEqualTo(quote.price());
        assertThat(month.get(month.size() - 2).close()).isEqualTo(quote.previousClose());
    }

    @Test
    void unknownSymbolIsAProviderFailure() {
        assertThatThrownBy(() -> at(WEDNESDAY).quote("NOPE")).isInstanceOf(PriceProviderException.class);
    }

    @Test
    void listsTheFixtureInstruments() {
        List<ProviderInstrument> list = at(WEDNESDAY).listInstruments();

        assertThat(list).extracting(ProviderInstrument::symbol).containsExactly("AAPL", "MSFT", "BRK.B");
        assertThat(list.get(0)).isEqualTo(new ProviderInstrument("AAPL", "Apple Inc.", "STOCK", "NASDAQ"));
    }

    private static DayOfWeek dayOfWeek(PricePoint point) {
        return point.time().atZone(ZoneOffset.UTC).getDayOfWeek();
    }
}
