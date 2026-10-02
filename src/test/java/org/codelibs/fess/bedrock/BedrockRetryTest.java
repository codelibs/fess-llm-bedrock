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
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;

public class BedrockRetryTest extends UnitFessTestCase {

    @Test
    public void test_isRetryableStatus() {
        for (final int status : new int[] { 429, 500, 502, 503, 504 }) {
            assertTrue(String.valueOf(status), BedrockRetry.isRetryableStatus(status));
        }
        for (final int status : new int[] { 200, 400, 401, 403, 404, 408, 424 }) {
            assertFalse(String.valueOf(status), BedrockRetry.isRetryableStatus(status));
        }
    }

    @Test
    public void test_parseRetryAfterSeconds() {
        assertEquals(-1L, BedrockRetry.parseRetryAfterSeconds(null));
        assertEquals(-1L, BedrockRetry.parseRetryAfterSeconds(" "));
        assertEquals(-1L, BedrockRetry.parseRetryAfterSeconds("-3"));
        assertEquals(-1L, BedrockRetry.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(7L, BedrockRetry.parseRetryAfterSeconds(" 7 "));
        assertEquals(600L, BedrockRetry.parseRetryAfterSeconds("3600"));
    }

    @Test
    public void test_isConnectFailure_onlyBeforeTheRequestReachedBedrock() {
        assertTrue(BedrockRetry.isConnectFailure(new ConnectException("refused")));
        assertTrue(BedrockRetry.isConnectFailure(new HttpHostConnectException("refused")));
        assertTrue(BedrockRetry.isConnectFailure(new ConnectTimeoutException("connect timed out")));
        assertTrue(BedrockRetry.isConnectFailure(new UnknownHostException("bedrock-runtime.example")));
        assertFalse("a read timeout may follow an accepted request", BedrockRetry.isConnectFailure(new SocketTimeoutException("read")));
        assertFalse(BedrockRetry.isConnectFailure(new NoHttpResponseException("no response")));
        assertFalse(BedrockRetry.isConnectFailure(new IOException("reset")));
    }

    @Test
    public void test_computeBackoffMs_retryAfterWinsAndIsCapped() {
        assertEquals(5000L, BedrockRetry.computeBackoffMs(1, 2000L, 60_000L, 5L));
        assertEquals(60_000L, BedrockRetry.computeBackoffMs(1, 2000L, 60_000L, 600L));
    }

    @Test
    public void test_computeBackoffMs_exponentialWithJitter() {
        for (int i = 0; i < 50; i++) {
            final long first = BedrockRetry.computeBackoffMs(1, 1000L, Long.MAX_VALUE, -1L);
            assertTrue("attempt 1: " + first, first >= 800L && first <= 1200L);
            final long third = BedrockRetry.computeBackoffMs(3, 1000L, Long.MAX_VALUE, 0L);
            assertTrue("attempt 3 ignores Retry-After: 0: " + third, third >= 3800L && third <= 4200L);
        }
        assertEquals(0L, BedrockRetry.computeBackoffMs(4, 0L, 60_000L, -1L));
    }
}
