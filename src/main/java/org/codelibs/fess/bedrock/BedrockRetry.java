/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.bedrock;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.hc.client5.http.ConnectTimeoutException;

/**
 * Shared retry vocabulary for the Bedrock clients in this plugin: which HTTP statuses are worth
 * retrying, how a {@code Retry-After} header is read, which transport failures happened before the
 * request could reach Bedrock, and the signal a call body raises to ask for a retry.
 *
 * <p>Each client owns its own retry policy (attempt budget, per-sleep cap, exception type); only
 * the vocabulary is shared, so the LLM client and the embedding client cannot disagree about what
 * a {@code 503} or a {@code Retry-After: 3600} means. Mirrors {@code OpenAiRetry} in
 * fess-llm-openai.
 */
public final class BedrockRetry {

    /** Maximum seconds honored from a server-provided {@code Retry-After}. */
    public static final long RETRY_AFTER_CAP_SECONDS = 600L;

    private BedrockRetry() {
        // utility class
    }

    /**
     * Returns whether the given HTTP status should be retried: {@code 429} (ThrottlingException,
     * ModelNotReadyException), {@code 500} (InternalServerException), {@code 502}, {@code 503}
     * (ServiceUnavailableException) and {@code 504}.
     *
     * @param statusCode the HTTP status code.
     * @return true when the status is retryable.
     */
    public static boolean isRetryableStatus(final int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * Parses a {@code Retry-After} header value as integer seconds. The HTTP-date form is not
     * supported and returns {@code -1}, as do absent, blank, negative and non-numeric values.
     * Values above {@link #RETRY_AFTER_CAP_SECONDS} are clamped.
     *
     * @param value the raw header value, or {@code null}.
     * @return the seconds to wait, or {@code -1}.
     */
    public static long parseRetryAfterSeconds(final String value) {
        if (value == null) {
            return -1L;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return -1L;
        }
        try {
            final long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return -1L;
            }
            return Math.min(seconds, RETRY_AFTER_CAP_SECONDS);
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Returns whether an I/O failure happened before the request could reach Bedrock: a refused
     * connection, a connect timeout, or a host name that did not resolve. Only these are retried;
     * any other {@link IOException} (a read timeout, a reset mid-response) may follow a request
     * Bedrock already accepted and billed.
     *
     * @param e the I/O failure.
     * @return true when retrying cannot duplicate a request Bedrock has seen.
     */
    public static boolean isConnectFailure(final IOException e) {
        return e instanceof ConnectException || e instanceof ConnectTimeoutException || e instanceof UnknownHostException;
    }

    /**
     * Computes one backoff sleep in milliseconds. A positive {@code retryAfterSeconds} overrides
     * the exponential computation ({@code base * 2^(attempt-1)} with +/-20% jitter); a literal
     * {@code Retry-After: 0} falls through to the exponential path so that a throttled endpoint is
     * not hit back-to-back. The result is clamped to {@code [0, maxDelayMs]}.
     *
     * @param attempt the 1-based attempt that just failed.
     * @param baseDelayMs the base delay in milliseconds ({@code >= 0}).
     * @param maxDelayMs the per-sleep cap in milliseconds ({@code > 0}).
     * @param retryAfterSeconds the parsed {@code Retry-After}, or {@code -1}.
     * @return the sleep in milliseconds.
     */
    public static long computeBackoffMs(final int attempt, final long baseDelayMs, final long maxDelayMs, final long retryAfterSeconds) {
        final long delayMs;
        if (retryAfterSeconds > 0L) {
            delayMs = retryAfterSeconds * 1000L;
        } else {
            final long jitter = (long) (baseDelayMs * 0.2 * ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
            delayMs = (long) (baseDelayMs * Math.pow(2, attempt - 1)) + jitter;
        }
        return Math.min(maxDelayMs, Math.max(0L, delayMs));
    }

    /**
     * Signal raised by a call body when the response status is retryable. Caught by each client's
     * {@code executeWithRetry}; never escapes a client.
     */
    public static final class RetryableHttpException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The HTTP status code that triggered the retry. */
        public final int statusCode;

        /** The HTTP reason phrase. */
        public final String reason;

        /** Seconds parsed from {@code Retry-After}, or {@code -1}. */
        public final long retryAfterSeconds;

        /**
         * Creates a retry signal.
         *
         * @param statusCode the HTTP status code.
         * @param reason the HTTP reason phrase.
         * @param retryAfterSeconds seconds parsed from {@code Retry-After}, or {@code -1}.
         */
        public RetryableHttpException(final int statusCode, final String reason, final long retryAfterSeconds) {
            super("retryable http error: " + statusCode + " " + reason);
            this.statusCode = statusCode;
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /**
     * One attempt of a retryable HTTP call.
     *
     * @param <T> the result type.
     */
    @FunctionalInterface
    public interface HttpCall<T> {
        /**
         * Performs one attempt.
         *
         * @return the result.
         * @throws IOException on a transport failure.
         */
        T call() throws IOException;
    }
}
