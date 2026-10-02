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
package org.codelibs.fess.embedding.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.codelibs.fess.embedding.EmbeddingException;
import org.codelibs.fess.embedding.bedrock.BedrockEmbeddingClient.ModelFamily;
import org.codelibs.fess.unit.LogCapturingAppender;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BedrockEmbeddingClientTest extends UnitFessTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer mockServer;
    private TestableBedrockEmbeddingClient client;
    private LogCapturingAppender capture;

    @Override
    protected void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        mockServer = new MockWebServer();
        mockServer.start();
        client = new TestableBedrockEmbeddingClient();
        capture = LogCapturingAppender.attach(BedrockEmbeddingClient.class);
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        capture.detach();
        Configurator.setLevel(BedrockEmbeddingClient.class.getName(), (Level) null);
        client.destroy();
        mockServer.shutdown();
        super.tearDown(testInfo);
    }

    private String mockEndpoint() {
        return mockServer.url("/").toString();
    }

    private static String vector(final int dimension, final float value) {
        final StringBuilder buf = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                buf.append(',');
            }
            buf.append(value);
        }
        return buf.append(']').toString();
    }

    private static MockResponse json(final String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse titan(final int dimension, final float value) {
        return json("{\"embedding\":" + vector(dimension, value) + ",\"inputTextTokenCount\":3}");
    }

    private static MockResponse cohereFloats(final int count, final int dimension) {
        final List<String> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            vectors.add(vector(dimension, i));
        }
        return json("{\"id\":\"x\",\"response_type\":\"embeddings_floats\",\"embeddings\":[" + String.join(",", vectors) + "]}");
    }

    private static MockResponse cohereByType(final int count, final int dimension) {
        final List<String> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            vectors.add(vector(dimension, i));
        }
        return json(
                "{\"id\":\"x\",\"response_type\":\"embeddings_by_type\",\"embeddings\":{\"float\":[" + String.join(",", vectors) + "]}}");
    }

    private static JsonNode body(final RecordedRequest recorded) {
        return MAPPER.readTree(recorded.getBody().readUtf8());
    }

    private static List<String> texts(final int count) {
        final List<String> texts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            texts.add("chunk " + i);
        }
        return texts;
    }

    // --- configuration ---

    @Test
    public void test_defaults() {
        assertEquals("bedrock", client.getName());
        assertEquals("amazon.titan-embed-text-v2:0", client.getModel());
        assertEquals("us-east-1", client.getRegion());
        assertEquals("", client.getEndpoint());
        assertEquals("", client.getApiKey());
        assertEquals(120000, client.getTimeout());
        assertEquals(10, client.getRetryMaxAttempts());
        assertEquals(2000L, client.getRetryBaseDelayMs());
        assertEquals(60000L, client.getRetryMaxDelayMs());
        assertTrue(client.isNormalize());
        assertEquals("", client.getTruncate());
    }

    @Test
    public void test_detectFamily() {
        assertEquals(ModelFamily.TITAN_V2, BedrockEmbeddingClient.detectFamily("amazon.titan-embed-text-v2:0"));
        assertEquals(ModelFamily.TITAN_V2,
                BedrockEmbeddingClient.detectFamily("arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2:0"));
        assertEquals(ModelFamily.COHERE_V3, BedrockEmbeddingClient.detectFamily("cohere.embed-english-v3"));
        assertEquals(ModelFamily.COHERE_V3, BedrockEmbeddingClient.detectFamily("cohere.embed-multilingual-v3"));
        assertEquals(ModelFamily.COHERE_V4, BedrockEmbeddingClient.detectFamily("cohere.embed-v4:0"));
        assertEquals(ModelFamily.COHERE_V4, BedrockEmbeddingClient.detectFamily("us.cohere.embed-v4:0"));
        assertEquals(ModelFamily.COHERE_V4, BedrockEmbeddingClient.detectFamily("global.cohere.embed-v4:0"));
        assertEquals(ModelFamily.COHERE_V4,
                BedrockEmbeddingClient.detectFamily("arn:aws:bedrock:us-east-1:123456789012:inference-profile/global.cohere.embed-v4:0"));
        assertEquals(ModelFamily.COHERE_V4, BedrockEmbeddingClient.detectFamily("Cohere.Embed-V4:0"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily("amazon.titan-embed-text-v1"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily("cohere.embed-english-v2"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily("cohere.embed-light-v3"));
        assertEquals(ModelFamily.UNSUPPORTED,
                BedrockEmbeddingClient.detectFamily("arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/a1b2c3d4e5f6"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily("amazon.titan-embed-image-v1"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily("amazon.nova-2-multimodal-embeddings-v1:0"));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily(""));
        assertEquals(ModelFamily.UNSUPPORTED, BedrockEmbeddingClient.detectFamily(null));
    }

    // --- Titan ---

    @Test
    public void test_titan_oneRequestPerTextInOrder() throws Exception {
        mockServer.enqueue(titan(256, 0.5f));
        mockServer.enqueue(titan(256, 0.25f));
        client.dimension(256).set("api.key", TestableBedrockEmbeddingClient.API_KEY).set("normalize", "false").endpoint(mockEndpoint());
        final List<float[]> vectors = client.embedDocuments(List.of("first", "second"));
        assertEquals(2, vectors.size());
        assertEquals(0.5f, vectors.get(0)[0]);
        assertEquals(0.25f, vectors.get(1)[255]);
        final RecordedRequest first = mockServer.takeRequest();
        assertEquals("/model/amazon.titan-embed-text-v2%3A0/invoke", first.getPath());
        assertEquals("Bearer " + TestableBedrockEmbeddingClient.API_KEY, first.getHeader("Authorization"));
        assertEquals("application/json", first.getHeader("Accept"));
        final JsonNode sent = body(first);
        assertEquals("first", sent.get("inputText").asString());
        assertEquals(256, sent.get("dimensions").asInt());
        assertFalse(sent.get("normalize").asBoolean());
        assertEquals("second", body(mockServer.takeRequest()).get("inputText").asString());
        assertTrue(
                capture.infos().stream().anyMatch(m -> m.contains("Embed completed") && m.contains("count=2") && m.contains("requests=2")));
    }

    @Test
    public void test_titan_unsupportedDimensionIsNotAvailableAndFailsWithoutARequest() {
        client.dimension(1536).set("api.key", TestableBedrockEmbeddingClient.API_KEY).endpoint(mockEndpoint());
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors()
                .stream()
                .anyMatch(m -> m.contains("content_chunker.embedding.dimension=1536") && m.contains("256, 512 or 1024")));
        try {
            client.embedDocuments(List.of("x"));
            fail("expected EmbeddingException");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("1536"));
        }
        assertEquals(0, mockServer.getRequestCount());
    }

    // --- Cohere ---

    @Test
    public void test_cohereV3_batchesOf96WithDocumentInputType() throws Exception {
        mockServer.enqueue(cohereFloats(96, 1024));
        mockServer.enqueue(cohereFloats(96, 1024));
        mockServer.enqueue(cohereFloats(8, 1024));
        client.set("model", "cohere.embed-multilingual-v3")
                .set("api.key", TestableBedrockEmbeddingClient.API_KEY)
                .set("truncate", "END")
                .endpoint(mockEndpoint());
        final List<float[]> vectors = client.embedDocuments(texts(200));
        assertEquals(200, vectors.size());
        assertEquals(95.0f, vectors.get(95)[0]);
        assertEquals(7.0f, vectors.get(199)[0]);
        final JsonNode first = body(mockServer.takeRequest());
        assertEquals(96, first.get("texts").size());
        assertEquals("chunk 0", first.at("/texts/0").asString());
        assertEquals("search_document", first.get("input_type").asString());
        assertEquals("END", first.get("truncate").asString());
        assertFalse("v3 has a fixed dimension", first.has("output_dimension"));
        assertEquals(96, body(mockServer.takeRequest()).get("texts").size());
        final RecordedRequest last = mockServer.takeRequest();
        assertEquals("/model/cohere.embed-multilingual-v3/invoke", last.getPath());
        assertEquals(8, body(last).get("texts").size());
    }

    @Test
    public void test_cohereV3_otherDimensionIsNotAvailable() {
        client.set("model", "cohere.embed-english-v3").dimension(768).set("api.key", TestableBedrockEmbeddingClient.API_KEY);
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors().stream().anyMatch(m -> m.contains("768") && m.contains("1024")));
    }

    @Test
    public void test_configuredModelIsTrimmed() throws Exception {
        mockServer.enqueue(titan(256, 0.5f));
        client.set("model", " amazon.titan-embed-text-v2:0 ")
                .dimension(256)
                .set("api.key", TestableBedrockEmbeddingClient.API_KEY)
                .endpoint(mockEndpoint());
        assertEquals("amazon.titan-embed-text-v2:0", client.getModel());
        client.embedDocuments(List.of("first"));
        assertEquals("/model/amazon.titan-embed-text-v2%3A0/invoke", mockServer.takeRequest().getPath());
    }

    @Test
    public void test_cohereV4_byTypeResponseAndQueryInputType() throws Exception {
        mockServer.enqueue(cohereByType(2, 1536));
        client.set("model", "global.cohere.embed-v4:0")
                .dimension(1536)
                .set("api.key", TestableBedrockEmbeddingClient.API_KEY)
                .endpoint(mockEndpoint());
        final List<float[]> vectors = client.embedQuery(List.of("+Fess +Docker", "title:\"Fess\"^2"));
        assertEquals(2, vectors.size());
        assertEquals(1536, vectors.get(1).length);
        assertEquals(1.0f, vectors.get(1)[0]);
        final RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("/model/global.cohere.embed-v4%3A0/invoke", recorded.getPath());
        final JsonNode sent = body(recorded);
        assertEquals("search_query", sent.get("input_type").asString());
        assertEquals(1536, sent.get("output_dimension").asInt());
        assertFalse("truncate is only sent when configured", sent.has("truncate"));
        assertEquals("Fess Docker", sent.at("/texts/0").asString());
        assertEquals("Fess", sent.at("/texts/1").asString());
    }

    @Test
    public void test_cohereV4_floatArrayResponseAlsoParses() {
        mockServer.enqueue(cohereFloats(1, 256));
        client.set("model", "cohere.embed-v4:0")
                .dimension(256)
                .set("api.key", TestableBedrockEmbeddingClient.API_KEY)
                .endpoint(mockEndpoint());
        assertEquals(256, client.embedDocuments(List.of("x")).get(0).length);
    }

    @Test
    public void test_cohere_responseValidation() {
        client.set("model", "cohere.embed-v4:0")
                .dimension(256)
                .set("api.key", TestableBedrockEmbeddingClient.API_KEY)
                .endpoint(mockEndpoint());
        mockServer.enqueue(cohereFloats(1, 256));
        try {
            client.embedDocuments(List.of("a", "b"));
            fail("count mismatch");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("count mismatch"));
        }
        mockServer.enqueue(cohereFloats(1, 255));
        try {
            client.embedDocuments(List.of("a"));
            fail("dimension mismatch");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("dimension mismatch"));
        }
        mockServer.enqueue(json("{\"embeddings\":[[1e999" + ",0.0".repeat(255) + "]]}"));
        try {
            client.embedDocuments(List.of("a"));
            fail("non-finite component");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not finite"));
        }
        mockServer.enqueue(json("{\"embeddings\":{\"int8\":[[1]]}}"));
        try {
            client.embedDocuments(List.of("a"));
            fail("no float embeddings");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no float embeddings"));
        }
    }

    // --- unsupported model, errors, retry ---

    @Test
    public void test_unsupportedModel() {
        client.set("model", "amazon.titan-embed-text-v1").set("api.key", TestableBedrockEmbeddingClient.API_KEY).endpoint(mockEndpoint());
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors()
                .stream()
                .anyMatch(m -> m.contains("not a supported embedding model") && m.contains("amazon.titan-embed-text-v1")));
        try {
            client.embedDocuments(List.of("x"));
            fail("expected EmbeddingException");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a supported embedding model"));
        }
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    public void test_nonRetryableErrorFailsOnce() {
        mockServer.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("x-amzn-ErrorType", "ValidationException:http://internal.amazon.com/coral/com.amazon.bedrock/")
                .setBody("{\"message\":\"Malformed input request: expected maxLength: 2048\"}"));
        client.set("api.key", TestableBedrockEmbeddingClient.API_KEY).endpoint(mockEndpoint());
        try {
            client.embedDocuments(List.of("x"));
            fail("expected EmbeddingException");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("400"));
            assertTrue(e.getMessage(), e.getMessage().contains("type=ValidationException"));
            assertTrue(e.getMessage(), e.getMessage().contains("expected maxLength: 2048"));
        }
        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    public void test_retryableErrorIsRetriedThenExhausted() {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"busy\"}"));
        mockServer.enqueue(titan(1024, 0.1f));
        client.set("api.key", TestableBedrockEmbeddingClient.API_KEY).set("retry.max", "2").endpoint(mockEndpoint());
        assertEquals(1, client.embedDocuments(List.of("x")).size());
        assertEquals(2, mockServer.getRequestCount());

        mockServer.enqueue(new MockResponse().setResponseCode(429).setBody("{\"message\":\"slow down\"}"));
        mockServer.enqueue(new MockResponse().setResponseCode(429).setBody("{\"message\":\"slow down\"}"));
        try {
            client.embedDocuments(List.of("x"));
            fail("expected EmbeddingException");
        } catch (final EmbeddingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("429"));
        }
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("embed retry exhausted") && m.contains("lastStatus=429")));
    }

    @Test
    public void test_retryMaxDelay_nonPositiveFallsBack() {
        client.set("retry.max.delay.ms", "0");
        assertEquals(60000L, client.getRetryMaxDelayMs());
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("retry.max.delay.ms must be positive")));
    }

    // --- auth and availability ---

    @Test
    public void test_sigV4Request() throws Exception {
        mockServer.enqueue(titan(1024, 0.1f));
        client.endpoint(mockEndpoint());
        client.embedDocuments(List.of("x"));
        final RecordedRequest recorded = mockServer.takeRequest();
        assertTrue(recorded.getHeader("Authorization")
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261002/us-east-1/bedrock/aws4_request, "));
    }

    @Test
    public void test_availability() {
        client.set("api.key", TestableBedrockEmbeddingClient.API_KEY).unresolvableCredentials();
        assertTrue(client.testCheckAvailabilityNow());
        client.destroy();

        client = new TestableBedrockEmbeddingClient();
        assertTrue("SigV4 with resolvable credentials", client.testCheckAvailabilityNow());
        client.destroy();

        client = new TestableBedrockEmbeddingClient().unresolvableCredentials();
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue("missing credentials are not a configuration error", capture.errors().isEmpty());

        client.dimension(null);
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors().stream().anyMatch(m -> m.contains("content_chunker.embedding.dimension is not configured")));

        client.dimension(1024).set("endpoint", "https://alice:s3cr3t@bedrock.example.com");
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors().stream().anyMatch(m -> m.contains("content_chunker.embedding.bedrock.endpoint")));
        assertTrue(capture.errors().stream().noneMatch(m -> m.contains("s3cr3t")));
    }

    @Test
    public void test_availability_credentialFailureIsRemembered() {
        final AtomicInteger lookups = new AtomicInteger();
        client.credentials(new AwsCredentialsProvider() {
            @Override
            public AwsCredentials resolveCredentials() {
                lookups.incrementAndGet();
                throw new IllegalStateException("Unable to load credentials from any of the providers in the chain");
            }
        });
        assertFalse(client.testCheckAvailabilityNow());
        assertFalse(client.testCheckAvailabilityNow());
        assertEquals(1, lookups.get());
    }

    @Test
    public void test_emptyInputSendsNothing() {
        client.endpoint(mockEndpoint());
        assertTrue(client.embedDocuments(List.of()).isEmpty());
        assertTrue(client.embedQuery(null).isEmpty());
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    public void test_credentialsNeverLogged() {
        Configurator.setLevel(BedrockEmbeddingClient.class.getName(), Level.DEBUG);
        mockServer.enqueue(new MockResponse().setResponseCode(400).setBody("{\"message\":\"bad\"}"));
        mockServer.enqueue(titan(1024, 0.1f));
        client.set("api.key", TestableBedrockEmbeddingClient.API_KEY).endpoint(mockEndpoint());
        try {
            client.embedDocuments(List.of("x"));
        } catch (final EmbeddingException e) {
            assertFalse(e.getMessage().contains(TestableBedrockEmbeddingClient.API_KEY));
        }
        client.destroy();
        client = new TestableBedrockEmbeddingClient();
        client.endpoint(mockEndpoint());
        client.embedQuery(List.of("+Fess"));
        assertFalse("DEBUG must actually be on", capture.debugs().isEmpty());
        for (final Level level : new Level[] { Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR }) {
            for (final String line : capture.renderedAt(level)) {
                assertFalse(line, line.contains(TestableBedrockEmbeddingClient.API_KEY));
                assertFalse(line, line.contains(TestableBedrockEmbeddingClient.SECRET_ACCESS_KEY));
                assertFalse(line, line.contains(TestableBedrockEmbeddingClient.ACCESS_KEY_ID));
                assertFalse(line, line.contains("AWS4-HMAC-SHA256"));
            }
        }
    }

    // --- query normalization (same contract as the sibling plugins) ---

    @Test
    public void test_toPlainQuery() {
        assertEquals("Fess Docker", client.toPlainQuery("+Fess +Docker"));
        assertEquals("tutorial guide howto", client.toPlainQuery("(tutorial OR guide OR howto)"));
        assertEquals("Fess", client.toPlainQuery("title:\"Fess\"^2"));
        assertEquals("text-embedding-3-small", client.toPlainQuery("text-embedding-3-small"));
        assertEquals("C++ 入門", client.toPlainQuery("C++ 入門"));
        assertEquals("AND OR", client.toPlainQuery("AND OR"));
        assertEquals("   ", client.toPlainQuery("   "));
        assertNull(client.toPlainQuery(null));
        assertEquals("陶芸 釉薬", client.toPlainQuery("+陶芸    +釉薬"));
    }

    @Test
    public void test_embedDocuments_sendsTextUntouched() throws Exception {
        mockServer.enqueue(titan(1024, 0.1f));
        client.set("api.key", TestableBedrockEmbeddingClient.API_KEY).endpoint(mockEndpoint());
        client.embedDocuments(List.of("title: \"Fess\" (prose) AND more"));
        assertEquals("title: \"Fess\" (prose) AND more", body(mockServer.takeRequest()).get("inputText").asString());
    }
}
