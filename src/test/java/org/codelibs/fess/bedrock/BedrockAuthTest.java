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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.utils.SdkAutoCloseable;

public class BedrockAuthTest extends UnitFessTestCase {

    /** The AWS documentation example key pair; never a real credential. */
    static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    static final String SESSION_TOKEN = "example-session-token";

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
    private static final String URL = "http://localhost:12345/model/us.amazon.nova-2-lite-v1%3A0/converse";
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    private MockWebServer mockServer;

    @Override
    protected void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        mockServer.shutdown();
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        System.clearProperty("aws.sessionToken");
        super.tearDown(testInfo);
    }

    private static BedrockAuth staticAuth(final AwsCredentials credentials) {
        return new BedrockAuth(() -> StaticCredentialsProvider.create(credentials), FIXED_CLOCK);
    }

    @Test
    public void test_apiKey_sendsBearerAndNeverResolvesCredentials() {
        final AtomicInteger created = new AtomicInteger();
        final BedrockAuth auth = new BedrockAuth(() -> {
            created.incrementAndGet();
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
        }, FIXED_CLOCK);
        final HttpPost post = new HttpPost(URI.create(URL));
        auth.authorize(post, URI.create(URL), BODY, " test-bedrock-api-key ", "us-east-1");
        assertEquals("Bearer test-bedrock-api-key", post.getFirstHeader("Authorization").getValue());
        assertNull(post.getFirstHeader("X-Amz-Date"), "no SigV4 header with an API key");
        assertEquals(0, created.get());
    }

    @Test
    public void test_sigv4_pinnedSignatureForFixedInput() {
        final HttpPost post = new HttpPost(URI.create(URL));
        staticAuth(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)).authorize(post, URI.create(URL), BODY, "", "us-east-1");
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261002/us-east-1/bedrock/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=0a91d60c5a0d7ca54688c2d9ae64274a33f932c274dd0af70f1d5aa2edbcb8c8",
                post.getFirstHeader("Authorization").getValue());
        assertEquals("20261002T000000Z", post.getFirstHeader("X-Amz-Date").getValue());
        assertEquals("localhost:12345", post.getFirstHeader("Host").getValue());
        assertEquals("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
                post.getFirstHeader("x-amz-content-sha256").getValue());
        assertNull(post.getFirstHeader("X-Amz-Security-Token"), "no session token with long-term credentials");
    }

    @Test
    public void test_sigv4_sessionCredentialsAddSecurityToken() {
        final HttpPost post = new HttpPost(URI.create(URL));
        staticAuth(AwsSessionCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY, SESSION_TOKEN)).authorize(post, URI.create(URL), BODY,
                null, "ap-northeast-1");
        final String authorization = post.getFirstHeader("Authorization").getValue();
        assertTrue(authorization, authorization
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261002/ap-northeast-1/bedrock/aws4_request, "));
        assertTrue(authorization, authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token,"));
        assertEquals(SESSION_TOKEN, post.getFirstHeader("X-Amz-Security-Token").getValue());
    }

    @Test
    public void test_sigv4_credentialsFromSystemProperties() {
        System.setProperty("aws.accessKeyId", ACCESS_KEY_ID);
        System.setProperty("aws.secretAccessKey", SECRET_ACCESS_KEY);
        System.setProperty("aws.sessionToken", SESSION_TOKEN);
        final BedrockAuth auth = new BedrockAuth();
        try {
            final HttpPost post = new HttpPost(URI.create(URL));
            auth.authorize(post, URI.create(URL), BODY, "", "us-west-2");
            final String authorization = post.getFirstHeader("Authorization").getValue();
            assertTrue(authorization, authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"));
            assertTrue(authorization, authorization.contains("/us-west-2/bedrock/aws4_request, "));
            assertEquals(SESSION_TOKEN, post.getFirstHeader("X-Amz-Security-Token").getValue());
        } finally {
            auth.close();
        }
    }

    @Test
    public void test_provider_createdOnceAndClosedByClose() {
        final AtomicInteger created = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final BedrockAuth auth = new BedrockAuth(() -> {
            created.incrementAndGet();
            return new CloseableProvider(closed);
        }, FIXED_CLOCK);
        auth.authorize(new HttpPost(URI.create(URL)), URI.create(URL), BODY, "", "us-east-1");
        auth.authorize(new HttpPost(URI.create(URL)), URI.create(URL), BODY, "", "us-east-1");
        assertEquals(1, created.get());
        auth.close();
        assertTrue(closed.get());
        auth.close();
        auth.resolveCredentials();
        assertEquals("a closed authorizer creates a fresh provider on next use", 2, created.get());
    }

    @Test
    public void test_unresolvableCredentials_throwAndLeaveRequestUnsigned() {
        final BedrockAuth auth = new BedrockAuth(() -> new AwsCredentialsProvider() {
            @Override
            public AwsCredentials resolveCredentials() {
                throw new IllegalStateException("Unable to load credentials from any of the providers in the chain");
            }
        }, FIXED_CLOCK);
        final HttpPost post = new HttpPost(URI.create(URL));
        try {
            auth.authorize(post, URI.create(URL), BODY, "", "us-east-1");
            fail("expected the provider's exception");
        } catch (final IllegalStateException e) {
            assertNull(post.getFirstHeader("Authorization"), "nothing may be sent half-signed");
        }
    }

    @Test
    public void test_requestOnTheWireMatchesWhatWasSigned() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{}"));
        final String url = mockServer.url("/").toString().replaceAll("/$", "")
                + "/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fus.amazon.nova-2-lite-v1%3A0/converse";
        final URI uri = BedrockEndpoint.toUri(url, "rag.llm.bedrock.endpoint");
        final HttpPost post = new HttpPost(uri);
        post.setEntity(new ByteArrayEntity(BODY, ContentType.APPLICATION_JSON));
        staticAuth(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)).authorize(post, uri, BODY, "", "us-east-1");
        try (CloseableHttpClient client = HttpClients.createDefault(); var response = client.execute(post)) {
            assertEquals(200, response.getCode());
        }
        final RecordedRequest recorded = mockServer.takeRequest();
        assertTrue(recorded.getRequestLine(), recorded.getRequestLine()
                .startsWith(
                        "POST /model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fus.amazon.nova-2-lite-v1%3A0/"));
        // Re-sign what the server received; the signature only matches if path, host and body
        // arrived exactly as they were signed.
        final byte[] receivedBody = recorded.getBody().readByteArray();
        final URI receivedUri = URI.create("http://" + recorded.getHeader("Host") + recorded.getPath());
        final String expected = AwsV4HttpSigner.create()
                .sign(r -> r.request(SdkHttpRequest.builder().method(SdkHttpMethod.POST).uri(receivedUri).build())
                        .payload(ContentStreamProvider.fromByteArray(receivedBody))
                        .identity(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY))
                        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "bedrock")
                        .putProperty(AwsV4HttpSigner.REGION_NAME, "us-east-1")
                        .putProperty(HttpSigner.SIGNING_CLOCK, FIXED_CLOCK))
                .request()
                .firstMatchingHeader("Authorization")
                .orElseThrow();
        assertEquals(expected, recorded.getHeader("Authorization"));
    }

    @Test
    public void test_checkCredentials_remembersAFailureForAMinute() {
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicBoolean available = new AtomicBoolean(false);
        final MutableClock clock = new MutableClock();
        final BedrockAuth auth = new BedrockAuth(() -> new AwsCredentialsProvider() {
            @Override
            public AwsCredentials resolveCredentials() {
                lookups.incrementAndGet();
                if (!available.get()) {
                    throw new IllegalStateException("Unable to load credentials from any of the providers in the chain");
                }
                return AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
            }
        }, clock);
        assertEquals("Unable to load credentials from any of the providers in the chain", auth.checkCredentials());
        clock.millis += BedrockAuth.CREDENTIALS_FAILURE_CACHE_MS - 1;
        assertNotNull(auth.checkCredentials());
        assertEquals("a failure is remembered, not looked up again", 1, lookups.get());
        available.set(true);
        clock.millis += 1;
        assertNull(auth.checkCredentials(), "looked up again once the minute is over");
        assertNull(auth.checkCredentials());
        assertEquals("success is never cached here", 3, lookups.get());
        final HttpPost post = new HttpPost(URI.create(URL));
        available.set(false);
        assertNotNull(auth.checkCredentials());
        try {
            auth.authorize(post, URI.create(URL), BODY, "", "us-east-1");
            fail("signing must not use the remembered failure, and must fail on its own lookup");
        } catch (final IllegalStateException e) {
            assertEquals(5, lookups.get());
        }
    }

    /** A clock a test can move. */
    private static final class MutableClock extends Clock {
        long millis = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    /** A provider that records whether it was closed. */
    private static final class CloseableProvider implements AwsCredentialsProvider, SdkAutoCloseable {
        private final AtomicBoolean closed;

        CloseableProvider(final AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
