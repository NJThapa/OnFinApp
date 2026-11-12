package com.onfinapp.api.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Analysis settings (analysis.* in application.properties).
 *
 * @param ttlHours how long an analysis is valid
 * @param refreshLimit refreshes one user can queue in any 24 hours
 * @param refreshMinAgeMinutes a refresh is ignored while the current analysis is younger than this
 * @param retryAfterMinutes after a failed generation, no new one is started on demand for this long
 * @param workerEnabled whether this API runs generation jobs
 * @param workerPollMs how often the worker looks for jobs queued by the other API
 * @param jobTimeoutSeconds a job running longer than this is taken to be abandoned and is claimed again
 */
@Validated
@ConfigurationProperties("analysis")
public record AnalysisProperties(
        @Positive int ttlHours,
        @PositiveOrZero int refreshLimit,
        @PositiveOrZero int refreshMinAgeMinutes,
        @PositiveOrZero int retryAfterMinutes,
        boolean workerEnabled,
        @Positive long workerPollMs,
        @Positive int jobTimeoutSeconds) {

    @AssertTrue(message = "analysis.refresh-min-age-minutes must not be longer than the analysis lifetime")
    boolean isRefreshMinAgeWithinLifetime() {
        return refreshMinAgeMinutes <= ttlHours * 60;
    }
}
