package com.onfinapp.api.analysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.onfinapp.api.analysis.AnalysisModels.AnalysisResponse;
import com.onfinapp.api.analysis.AnalysisModels.AnalysisStatusResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingEntryResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingPageResponse;
import com.onfinapp.api.auth.SecurityConfig;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.common.CorrelationFilter;
import com.onfinapp.api.common.ProblemHandler;
import com.onfinapp.api.user.User;
import com.onfinapp.api.user.UserService;

/** The HTTP layer: 200 or 202 by status, the response bodies, and the refresh-limit error. */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, ProblemHandler.class, CorrelationFilter.class})
@TestPropertySource(properties = "oidc.jwks-uri=http://localhost/unused")
class AnalysisControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalysisService analyses;

    @MockitoBean
    private UserService users;

    @BeforeEach
    void signedInUser() {
        when(users.resolve(any())).thenReturn(new User(USER_ID, "test@example.com", "Test"));
    }

    @Test
    void aReadyAnalysisIsA200WithTheContractsFields() throws Exception {
        when(analyses.get(eq("aapl"), any())).thenReturn(new AnalysisStatusResponse("AAPL", "READY",
                AnalysisResponse.from(SampleAnalysis.stored("BUY", true, true))));

        mvc.perform(get("/api/v1/instruments/aapl/analysis").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"symbol":"AAPL","status":"READY","analysis":{
                          "summary":"A summary.","bullPoints":["Bull."],"bearPoints":["Bear."],"risks":["Risk."],
                          "keyFigures":[{"label":"P/E ratio","value":"30.1","asOf":"2026-11-11"}],
                          "sources":[{"title":"Report","url":"https://example.com/report"}],
                          "rating":"BUY","confidence":"MEDIUM","rationale":"Because.",
                          "generatedAt":"2026-11-11T17:00:00Z","expiresAt":"2026-11-12T17:00:00Z","generatedBy":"dotnet"}}
                        """, true));
    }

    @Test
    void whileGeneratingTheAnswerIsA202AndCarriesTheCorrelationId() throws Exception {
        when(analyses.get(any(), any())).thenReturn(new AnalysisStatusResponse("AAPL", "GENERATING", null));

        mvc.perform(get("/api/v1/instruments/AAPL/analysis").with(jwt()).header("X-Correlation-ID", "flow-42"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("""
                        {"symbol":"AAPL","status":"GENERATING","analysis":null}
                        """, true));

        verify(analyses).get("AAPL", "flow-42");
    }

    @Test
    void aFailedGenerationIsA200() throws Exception {
        when(analyses.get(any(), any())).thenReturn(new AnalysisStatusResponse("AAPL", "FAILED", null));

        mvc.perform(get("/api/v1/instruments/AAPL/analysis").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void refreshIsQueuedForTheSignedInUser() throws Exception {
        when(analyses.refresh(eq("AAPL"), eq(USER_ID), any()))
                .thenReturn(new AnalysisStatusResponse("AAPL", "GENERATING", null));

        mvc.perform(post("/api/v1/instruments/AAPL/analysis/refresh").with(jwt())).andExpect(status().isAccepted());
    }

    @Test
    void refreshAtTheLimitIsA429Problem() throws Exception {
        when(analyses.refresh(any(), any(), any())).thenThrow(new ApiProblemException.RefreshLimit(5));

        mvc.perform(post("/api/v1/instruments/AAPL/analysis/refresh").with(jwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Too Many Requests","status":429,
                         "detail":"You can refresh up to 5 analyses in 24 hours.",
                         "instance":"/api/v1/instruments/AAPL/analysis/refresh"}
                        """, true));
    }

    @Test
    void ratingsDefaultTheLimitTo20() throws Exception {
        when(analyses.ratings("AAPL", null, 20)).thenReturn(new RatingPageResponse(
                List.of(new RatingEntryResponse("HOLD", "LOW", "Mixed.", Instant.parse("2026-11-11T17:00:00Z"))), "NDI"));

        mvc.perform(get("/api/v1/instruments/AAPL/ratings").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[{"rating":"HOLD","confidence":"LOW","rationale":"Mixed.","createdAt":"2026-11-11T17:00:00Z"}],
                         "nextCursor":"NDI"}
                        """, true));
    }

    @Test
    void anInvalidRatingsLimitIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/instruments/AAPL/ratings?limit=51").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Bad Request","status":400,
                         "detail":"limit must be a whole number from 1 to 50.","instance":"/api/v1/instruments/AAPL/ratings"}
                        """, true));
    }

    @Test
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/instruments/AAPL/analysis")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/instruments/AAPL/analysis/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/instruments/AAPL/ratings")).andExpect(status().isUnauthorized());
    }
}
