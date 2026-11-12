package com.onfinapp.api.instrument;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onfinapp.api.instrument.InstrumentResponses.InstrumentDetailResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentPageResponse;
import com.onfinapp.api.instrument.InstrumentResponses.PriceSeriesResponse;
import com.onfinapp.api.price.PriceRanges;
import com.onfinapp.api.user.UserService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private static final int DEFAULT_LIMIT = 20;
    private static final String LIMIT_MESSAGE = "limit must be a whole number from 1 to 50.";

    private final InstrumentService instruments;
    private final UserService users;

    public InstrumentController(InstrumentService instruments, UserService users) {
        this.instruments = instruments;
        this.users = users;
    }

    @GetMapping
    public InstrumentPageResponse search(
            @RequestParam(required = false) @Size(max = 50, message = "query must be at most 50 characters.") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(value = 1, message = LIMIT_MESSAGE) @Max(value = 50, message = LIMIT_MESSAGE)
            Integer limit) {
        return instruments.search(query, cursor, limit == null ? DEFAULT_LIMIT : limit);
    }

    @GetMapping("/{symbol}")
    public InstrumentDetailResponse detail(@PathVariable String symbol, @AuthenticationPrincipal Jwt jwt) {
        return instruments.detail(symbol, users.resolve(jwt).id());
    }

    @GetMapping("/{symbol}/prices")
    public PriceSeriesResponse prices(
            @PathVariable String symbol,
            @RequestParam(required = false)
            @Pattern(regexp = PriceRanges.PATTERN, message = "range must be one of 1D, 1W, 1M, 1Y, 5Y.") String range) {
        return instruments.prices(symbol, range == null ? PriceRanges.DEFAULT : range);
    }
}
