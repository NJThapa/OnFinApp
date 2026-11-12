package com.onfinapp.api.instrument;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.onfinapp.api.auth.SecurityConfig;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.common.CorrelationFilter;
import com.onfinapp.api.common.ProblemHandler;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentPageResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentResponse;
import com.onfinapp.api.instrument.InstrumentResponses.PriceSeriesResponse;
import com.onfinapp.api.user.User;
import com.onfinapp.api.user.UserService;

/** The HTTP layer: parameter validation, defaults, and the shape of error bodies. */
@WebMvcTest(InstrumentController.class)
@Import({SecurityConfig.class, ProblemHandler.class, CorrelationFilter.class})
@TestPropertySource(properties = "oidc.jwks-uri=http://localhost/unused")
class InstrumentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InstrumentService instruments;

    @MockitoBean
    private UserService users;

    @BeforeEach
    void signedInUser() {
        when(users.resolve(any())).thenReturn(new User(UUID.randomUUID(), "test@example.com", "Test"));
    }

    @Test
    void searchPassesParametersAndDefaultsTheLimitTo20() throws Exception {
        when(instruments.search("apple", null, 20)).thenReturn(new InstrumentPageResponse(
                List.of(new InstrumentResponse("AAPL", "Apple Inc.", "STOCK", "NASDAQ")), null));

        mvc.perform(get("/api/v1/instruments?query=apple").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[{"symbol":"AAPL","name":"Apple Inc.","type":"STOCK","exchange":"NASDAQ"}],"nextCursor":null}
                        """, true));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            limit=0       | limit must be a whole number from 1 to 50.
            limit=51      | limit must be a whole number from 1 to 50.
            limit=abc     | The request is not valid.
            query=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | query must be at most 50 characters.
            """)
    void invalidSearchParametersAreBadRequests(String query, String detail) throws Exception {
        mvc.perform(get("/api/v1/instruments?" + query).with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Bad Request","status":400,"detail":"%s","instance":"/api/v1/instruments"}
                        """.formatted(detail), true));
    }

    @Test
    void pricesDefaultToOneMonth() throws Exception {
        when(instruments.prices("MSFT", "1M")).thenReturn(new PriceSeriesResponse("MSFT", "1M", List.of()));

        mvc.perform(get("/api/v1/instruments/MSFT/prices").with(jwt())).andExpect(status().isOk());

        verify(instruments).prices("MSFT", "1M");
    }

    @Test
    void anUnknownRangeIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/instruments/MSFT/prices?range=2D").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Bad Request","status":400,
                         "detail":"range must be one of 1D, 1W, 1M, 1Y, 5Y.","instance":"/api/v1/instruments/MSFT/prices"}
                        """, true));
    }

    @Test
    void serviceErrorsBecomeProblemResponses() throws Exception {
        when(instruments.detail(anyString(), any())).thenThrow(new ApiProblemException.InstrumentNotFound("NOPE"));

        mvc.perform(get("/api/v1/instruments/nope").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Not Found","status":404,
                         "detail":"No instrument with ticker NOPE.","instance":"/api/v1/instruments/nope"}
                        """, true));
    }

    @Test
    void anUnexpectedErrorIsA500ThatDoesNotRevealTheCause() throws Exception {
        when(instruments.detail(anyString(), any())).thenThrow(new IllegalStateException("password=secret"));

        mvc.perform(get("/api/v1/instruments/AAPL").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Internal Server Error","status":500,
                         "detail":"An unexpected error occurred.","instance":"/api/v1/instruments/AAPL"}
                        """, true));
    }

    @Test
    void anUnreachableDatabaseIsA503() throws Exception {
        when(instruments.detail(anyString(), any()))
                .thenThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));

        mvc.perform(get("/api/v1/instruments/AAPL").with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Service Unavailable","status":503,
                         "detail":"The service is temporarily unavailable. Please try again.",
                         "instance":"/api/v1/instruments/AAPL"}
                        """, true));
    }

    @Test
    void aStatementTimeoutIsA503() throws Exception {
        when(instruments.detail(anyString(), any())).thenThrow(new QueryTimeoutException("timed out"));

        mvc.perform(get("/api/v1/instruments/AAPL").with(jwt())).andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownRoutesAndWrongMethodsAreProblemResponses() throws Exception {
        mvc.perform(get("/api/v1/nothing").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Not Found","status":404,"detail":"Not Found","instance":"/api/v1/nothing"}
                        """, true));
        mvc.perform(post("/api/v1/instruments").with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Method Not Allowed","status":405,
                         "detail":"Method Not Allowed","instance":"/api/v1/instruments"}
                        """, true));
    }

    @Test
    void theCorrelationIdIsEchoedOrGenerated() throws Exception {
        mvc.perform(get("/api/v1/instruments/AAPL").with(jwt()).header("X-Correlation-ID", "flow-42"))
                .andExpect(header().string("X-Correlation-ID", "flow-42"));
        // Missing, or not a plain token: replaced by a new UUID. Rejected requests carry one too.
        mvc.perform(get("/api/v1/instruments/AAPL").with(jwt()).header("X-Correlation-ID", "not a token"))
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/api/v1/instruments"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void tickersWithADotReachTheService() throws Exception {
        mvc.perform(get("/api/v1/instruments/brk.b").with(jwt()));

        verify(instruments).detail(anyString(), any());
        verify(instruments).detail(org.mockito.ArgumentMatchers.eq("brk.b"), any());
    }

    @Test
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/instruments"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid bearer token is required."}
                        """, true));
    }
}
