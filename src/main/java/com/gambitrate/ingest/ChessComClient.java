package com.gambitrate.ingest;

import com.gambitrate.config.ChessComProperties;
import com.gambitrate.ingest.dto.ArchivesResponse;
import com.gambitrate.ingest.dto.MonthlyGamesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ChessComClient {

    private static final Logger log = LoggerFactory.getLogger(ChessComClient.class);

    private final RestClient restClient;
    private final ChessComProperties properties;
    private final Lock hostLock = new ReentrantLock(true);
    private final String baseUrl;

    public ChessComClient(RestClient chessComRestClient, ChessComProperties properties) {
        this.restClient = chessComRestClient;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    public List<String> getArchives(String username) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("pub", "player", username, "games", "archives")
                .build()
                .encode()
                .toUri();
        ArchivesResponse response = get(uri, ArchivesResponse.class);
        return response.archives() == null ? List.of() : response.archives();
    }

    public MonthlyGamesResponse getGamesForMonth(String archiveUrl) {
        if (archiveUrl == null || archiveUrl.isBlank()) {
            throw new ChessComUpstreamException("Chess.com returned a blank monthly archive URL.");
        }
        return get(URI.create(archiveUrl), MonthlyGamesResponse.class);
    }

    private <T> T get(URI uri, Class<T> responseType) {
        hostLock.lock();
        try {
            for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
                pause(properties.requestDelay());
                long start = System.nanoTime();
                try {
                    ResponseEntity<T> response = restClient.get()
                            .uri(uri)
                            .retrieve()
                            .toEntity(responseType);
                    long elapsedMs = elapsedMillis(start);
                    log.info("chesscom_request url={} status={} cache=disabled latency_ms={}",
                            uri, response.getStatusCode().value(), elapsedMs);
                    if (response.getBody() == null) {
                        throw new ChessComUpstreamException("Chess.com returned an empty response.");
                    }
                    return response.getBody();
                } catch (RestClientResponseException exception) {
                    int status = exception.getStatusCode().value();
                    long elapsedMs = elapsedMillis(start);
                    log.warn("chesscom_request url={} status={} cache=disabled latency_ms={} attempt={}",
                            uri, status, elapsedMs, attempt);

                    if (status == 404) {
                        throw new PlayerNotFoundException(extractUsername(uri));
                    }
                    if (status == 429) {
                        long retryAfter = retryAfterSeconds(exception, attempt);
                        if (attempt == properties.maxAttempts()) {
                            throw new ChessComRateLimitException(retryAfter);
                        }
                        log.warn("chesscom_rate_limited url={} retry_after_s={} attempt={}",
                                uri, retryAfter, attempt);
                        pause(Duration.ofSeconds(retryAfter));
                        continue;
                    }
                    throw new ChessComUpstreamException(
                            "Chess.com returned HTTP " + status + " for " + uri, exception);
                } catch (ResourceAccessException exception) {
                    log.warn("chesscom_request url={} status=network_error cache=disabled latency_ms={}",
                            uri, elapsedMillis(start));
                    throw new ChessComUpstreamException("Could not reach Chess.com.", exception);
                } catch (RestClientException exception) {
                    log.warn("chesscom_request url={} status=client_error cache=disabled latency_ms={}",
                            uri, elapsedMillis(start));
                    throw new ChessComUpstreamException("Chess.com request failed.", exception);
                }
            }
        } finally {
            hostLock.unlock();
        }
        throw new ChessComUpstreamException("Chess.com request failed after retries.");
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void pause(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChessComUpstreamException("Interrupted while waiting to call Chess.com.", exception);
        }
    }

    private static long retryAfterSeconds(RestClientResponseException exception, int attempt) {
        HttpHeaders headers = exception.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                return Math.max(1, Long.parseLong(retryAfter.trim()));
            } catch (NumberFormatException ignored) {
                try {
                    ZonedDateTime retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
                    return Math.max(1, Duration.between(ZonedDateTime.now(retryAt.getZone()), retryAt).toSeconds());
                } catch (RuntimeException ignoredDate) {
                    // Fall through to the exponential backoff default.
                }
            }
        }
        return Math.min(60, 1L << Math.min(attempt - 1, 5));
    }

    private static String extractUsername(URI uri) {
        String path = uri.getPath();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("player".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return path;
    }
}
