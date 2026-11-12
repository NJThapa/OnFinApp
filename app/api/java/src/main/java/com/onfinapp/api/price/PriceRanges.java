package com.onfinapp.api.price;

public final class PriceRanges {

    public static final String ONE_DAY = "1D";
    public static final String ONE_WEEK = "1W";
    public static final String ONE_MONTH = "1M";
    public static final String ONE_YEAR = "1Y";
    public static final String FIVE_YEARS = "5Y";

    public static final String DEFAULT = ONE_MONTH;

    /** For validating the range request parameter. */
    public static final String PATTERN = "1D|1W|1M|1Y|5Y";

    private PriceRanges() {
    }

    /** Ranges made of points within a day; they go stale in minutes rather than daily. */
    public static boolean isIntraday(String range) {
        return ONE_DAY.equals(range) || ONE_WEEK.equals(range);
    }
}
