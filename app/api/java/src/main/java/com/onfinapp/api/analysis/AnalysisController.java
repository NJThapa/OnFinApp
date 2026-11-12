package com.onfinapp.api.analysis;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onfinapp.api.analysis.AnalysisModels.AnalysisStatusResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingPageResponse;
import com.onfinapp.api.common.CorrelationFilter;
import com.onfinapp.api.user.UserService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/instruments/{symbol}")
public class AnalysisController {

    private static final int DEFAULT_LIMIT = 20;
    private static final String LIMIT_MESSAGE = "limit must be a whole number from 1 to 50.";

    private final AnalysisService analyses;
    private final UserService users;

    public AnalysisController(AnalysisService analyses, UserService users) {
        this.analyses = analyses;
        this.users = users;
    }

    @GetMapping("/analysis")
    public ResponseEntity<AnalysisStatusResponse> get(@PathVariable String symbol) {
        return respond(analyses.get(symbol, correlationId()));
    }

    @PostMapping("/analysis/refresh")
    public ResponseEntity<AnalysisStatusResponse> refresh(@PathVariable String symbol, @AuthenticationPrincipal Jwt jwt) {
        return respond(analyses.refresh(symbol, users.resolve(jwt).id(), correlationId()));
    }

    @GetMapping("/ratings")
    public RatingPageResponse ratings(
            @PathVariable String symbol,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(value = 1, message = LIMIT_MESSAGE) @Max(value = 50, message = LIMIT_MESSAGE)
            Integer limit) {
        return analyses.ratings(symbol, cursor, limit == null ? DEFAULT_LIMIT : limit);
    }

    /** 202 while a generation is queued or running, otherwise 200. */
    private static ResponseEntity<AnalysisStatusResponse> respond(AnalysisStatusResponse body) {
        HttpStatus status = AnalysisModels.GENERATING.equals(body.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    /** Set by {@link CorrelationFilter} for the current request. */
    private static String correlationId() {
        return MDC.get(CorrelationFilter.LOG_PROPERTY);
    }
}
