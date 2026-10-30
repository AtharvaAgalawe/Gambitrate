package com.gambitrate.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gambitrate.config.ChessComProperties;
import com.gambitrate.config.RedisCacheConfig;
import com.gambitrate.ingest.dto.ArchivesResponse;
import com.gambitrate.ingest.dto.MonthlyGamesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ChessComClient {

    private static final Logger log = LoggerFactory.getLogger(ChessComClient.class);

    private final RestClient restClient;
    private final ChessComProperties properties;
    private final RedisOutboundRateLimiter rateLimiter;
    private final RedisConditionalRequestStore conditionalStore;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public ChessComClient(
            RestClient chessComRestClient,
            ChessComProperties properties,
            RedisOutboundRateLimiter rateLimiter,
            RedisConditionalRequestStore conditionalStore,
            CacheManager cacheManager,
            ObjectMapper objectMapper
    ) {
        this.restClient = chessComRestClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.conditionalStore = conditionalStore;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    public void assertPlayerExists(String username) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("pub", "player", username)
                .build()
                .encode()
                .toUri();
        request(uri, RequestPolicy.BYPASS, null, "profile");
    }

    public List<String> getArchives(String username) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("pub", "player", username, "games", "archives")
                .build()
                .encode()
                .toUri();
        String json = request(
                uri,
                RequestPolicy.REVALIDATE,
                cache(RedisCacheConfig.ARCHIVES_CACHE),
                "archives"
        );
        ArchivesResponse response = parse(json, ArchivesResponse.class);
        return response.archives() == null ? List.of() : response.archives();
    }

    public MonthlyGamesResponse getGamesForMonth(
            String archiveUrl,
            YearMonth month,
            YearMonth currentMonth
    ) {
        if (archiveUrl == null || archiveUrl.isBlank()) {
            throw new ChessComUpstreamException("Chess.com returned a blank monthly archive URL.");
        }
        boolean current = !month.isBefore(currentMonth);
        String cacheName = current
                ? RedisCacheConfig.CURRENT_MONTH_CACHE
                : RedisCacheConfig.PAST_MONTH_CACHE;
        String label = current ? "current-month" : "past-month";
        String json = request(
                URI.create(archiveUrl),
                current ? RequestPolicy.REVALIDATE : RequestPolicy.IMMUTABLE,
                cache(cacheName),
                label
        );
        return parse(json, MonthlyGamesResponse.class);
    }

    private String request(URI uri, RequestPolicy policy, Cache cache, String cacheLabel) {
        String key = uri.toString();
        String cachedBody = null;
        String etag = null;

        if (policy != RequestPolicy.BYPASS && cache != null) {
            cachedBody = cache.get(key, String.class);
            if (policy == RequestPolicy.IMMUTABLE && cachedBody != null) {
                log.info("chesscom_request url={} status=cache_hit cache={} latency_ms=0",
                        uri, cacheLabel);
                return cachedBody;
            }
            if (cachedBody != null) {
                etag = conditionalStore.getEtag(uri).orElse(null);
            }
        }

        String requestEtag = etag;
        return rateLimiter.execute(() -> executeRequest(
                uri,
                policy,
                cache,
                cacheLabel,
                key,
                requestEtag
        ));
    }

    private String executeRequest(
            URI uri,
            RequestPolicy policy,
            Cache cache,
            String cacheLabel,
            String cacheKey,
            String initialEtag
    ) {
        String etag = initialEtag;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            pause(properties.requestDelay());
            long start = System.nanoTime();
            try {
                RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
                if (etag != null && !etag.isBlank()) {
                    request = request.header(HttpHeaders.IF_NONE_MATCH, etag);
                }
                ResponseEntity<String> response = request.retrieve().toEntity(String.class);
                long elapsedMs = elapsedMillis(start);
                int status = response.getStatusCode().value();

                if (status == HttpStatus.NOT_MODIFIED.value() && cache != null) {
                    String cachedBody = cache.get(cacheKey, String.class);
                    if (cachedBody != null) {
                        log.info("chesscom_request url={} status=304 cache={} latency_ms={}",
                                uri, cacheLabel, elapsedMs);
                        return cachedBody;
                    }
                    log.warn("chesscom_304_without_body url={} cache={}; retrying unconditionally",
                            uri, cacheLabel);
                    etag = null;
                    continue;
                }

                String body = response.getBody();
                if (body == null) {
                    throw new ChessComUpstreamException("Chess.com returned an empty response.");
                }
                if (cache != null) {
                    cache.put(cacheKey, body);
                }
                conditionalStore.putEtag(uri, response.getHeaders().getETag());
                log.info("chesscom_request url={} status={} cache={} latency_ms={}",
                        uri, status, policy == RequestPolicy.BYPASS ? "bypass" : "miss", elapsedMs);
                return body;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                long elapsedMs = elapsedMillis(start);
                log.warn("chesscom_request url={} status={} cache={} latency_ms={} attempt={}",
                        uri, status, policy == RequestPolicy.BYPASS ? "bypass" : "miss",
                        elapsedMs, attempt);

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
                log.warn("chesscom_request url={} status=network_error cache={} latency_ms={}",
                        uri, policy == RequestPolicy.BYPASS ? "bypass" : "miss", elapsedMillis(start));
                throw new ChessComUpstreamException("Could not reach Chess.com.", exception);
            } catch (RestClientException exception) {
                log.warn("chesscom_request url={} status=client_error cache={} latency_ms={}",
                        uri, policy == RequestPolicy.BYPASS ? "bypass" : "miss", elapsedMillis(start));
                throw new ChessComUpstreamException("Chess.com request failed.", exception);
            }
        }
        throw new ChessComUpstreamException("Chess.com request failed after retries.");
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new ChessComUpstreamException("Could not parse chess.com response.", exception);
        }
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            throw new IllegalStateException("Redis cache is not configured: " + name);
        }
        return cache;
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

    private enum RequestPolicy {
        BYPASS,
        REVALIDATE,
        IMMUTABLE
    }
}
