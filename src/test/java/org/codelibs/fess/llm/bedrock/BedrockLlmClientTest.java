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
package org.codelibs.fess.llm.bedrock;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.codelibs.fess.llm.LlmChatRequest;
import org.codelibs.fess.llm.LlmChatResponse;
import org.codelibs.fess.llm.LlmException;
import org.codelibs.fess.llm.LlmMessage;
import org.codelibs.fess.llm.LlmStreamCallback;
import org.codelibs.fess.unit.LogCapturingAppender;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BedrockLlmClientTest extends UnitFessTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONVERSE_OK =
            "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"Hello from Nova\"}]}},"
                    + "\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":12,\"outputTokens\":4,\"totalTokens\":16},\"metrics\":{\"latencyMs\":321}}";

    private MockWebServer mockServer;
    private TestableBedrockLlmClient client;
    private LogCapturingAppender capture;

    @Override
    protected void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        mockServer = new MockWebServer();
        mockServer.start();
        client = new TestableBedrockLlmClient();
        capture = LogCapturingAppender.attach(BedrockLlmClient.class);
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        capture.detach();
        Configurator.setLevel(BedrockLlmClient.class.getName(), (Level) null);
        client.destroy();
        mockServer.shutdown();
        super.tearDown(testInfo);
    }

    private String mockEndpoint() {
        return mockServer.url("/").toString();
    }

    private static MockResponse json(final int status, final String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse bedrockError(final int status, final String type, final String message) {
        return json(status, "{\"message\":\"" + message + "\"}").setHeader("x-amzn-ErrorType",
                type + ":http://internal.amazon.com/coral/com.amazon.bedrock/");
    }

    private static LlmChatRequest userRequest(final String text) {
        return new LlmChatRequest().addUserMessage(text);
    }

    private static JsonNode body(final RecordedRequest recorded) {
        return MAPPER.readTree(recorded.getBody().readUtf8());
    }

    private static void enableDebug() {
        Configurator.setLevel(BedrockLlmClient.class.getName(), Level.DEBUG);
    }

    // --- configuration ---

    @Test
    public void test_getName() {
        assertEquals("bedrock", client.getName());
    }

    @Test
    public void test_defaults() {
        assertEquals("us.amazon.nova-2-lite-v1:0", client.getModel());
        assertEquals("us-east-1", client.getRegion());
        assertEquals("", client.getEndpoint());
        assertEquals("", client.getApiKey());
        assertEquals(120000, client.getTimeout());
        assertEquals(60, client.getAvailabilityCheckInterval());
        assertEquals(10, client.getRetryMaxAttempts());
        assertEquals(2000L, client.getRetryBaseDelayMs());
        assertTrue(client.isTemperatureEnabled());
        assertEquals(16000, client.getContextMaxChars("answer"));
        assertEquals(16000, client.getContextMaxChars("summary"));
        assertEquals(10000, client.getContextMaxChars("faq"));
        assertEquals(10000, client.getContextMaxChars("intent"));
        assertEquals(3, client.getEvaluationMaxRelevantDocs());
        assertEquals(500, client.getEvaluationDescriptionMaxChars());
        assertEquals(8000, client.getHistoryMaxChars());
        assertEquals(8, client.getIntentHistoryMaxMessages());
        assertEquals(4000, client.getIntentHistoryMaxChars());
        assertEquals(800, client.getHistoryAssistantMaxChars());
        assertEquals(800, client.getHistoryAssistantSummaryMaxChars());
    }

    @Test
    public void test_getConfigInt_invalidValueWarnsAndFallsBack() {
        client.set("timeout", "soon").set("answer.context.max.chars", "-5");
        assertEquals(120000, client.getTimeout());
        assertEquals(16000, client.getContextMaxChars("answer"));
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("rag.llm.bedrock.timeout") && m.contains("soon")));
    }

    @Test
    public void test_isTemperatureEnabled_values() {
        client.set("temperature.enabled", "FALSE");
        assertFalse(client.isTemperatureEnabled());
        client.set("temperature.enabled", "");
        assertTrue(client.isTemperatureEnabled());
        client.set("temperature.enabled", "flase");
        assertTrue(client.isTemperatureEnabled());
        client.isTemperatureEnabled();
        assertEquals(2, capture.warnings().stream().filter(m -> m.contains("temperature.enabled") && m.contains("flase")).count());
    }

    // --- request mapping ---

    @Test
    public void test_buildRequestBody_mapsSystemMessagesAndInferenceConfig() {
        final LlmChatRequest request = new LlmChatRequest().addSystemMessage("You are Fess.")
                .addSystemMessage("Answer briefly.")
                .addUserMessage("What is Fess?")
                .setMaxTokens(512)
                .setTemperature(0.25);
        final JsonNode body = MAPPER.valueToTree(client.buildRequestBody(request));
        assertEquals("You are Fess.", body.at("/system/0/text").asString());
        assertEquals("Answer briefly.", body.at("/system/1/text").asString());
        assertEquals(1, body.get("messages").size());
        assertEquals("user", body.at("/messages/0/role").asString());
        assertEquals("What is Fess?", body.at("/messages/0/content/0/text").asString());
        assertEquals(512, body.at("/inferenceConfig/maxTokens").asInt());
        assertEquals(0.25, body.at("/inferenceConfig/temperature").asDouble());
        assertFalse(body.has("additionalModelRequestFields"));
        assertFalse("the model ID belongs in the URL", body.has("modelId"));
    }

    @Test
    public void test_buildRequestBody_temperatureDisabledNeverSendsTemperature() {
        client.set("temperature.enabled", "false");
        final LlmChatRequest request = userRequest("q");
        client.applyDefaultParams(request, "answer");
        assertNull(request.getTemperature(), "no default temperature when temperature is disabled");
        assertEquals(2048, request.getMaxTokens().intValue());
        request.setTemperature(0.9);
        final JsonNode body = MAPPER.valueToTree(client.buildRequestBody(request));
        assertFalse(body.get("inferenceConfig").has("temperature"));
        assertEquals(2048, body.at("/inferenceConfig/maxTokens").asInt());
    }

    @Test
    public void test_applyDefaultParams_table() {
        final Object[][] table = { { "intent", 0.1, 256 }, { "evaluation", 0.1, 256 }, { "unclear", 0.7, 512 }, { "noresults", 0.7, 512 },
                { "docnotfound", 0.7, 256 }, { "direct", 0.7, 1024 }, { "faq", 0.7, 1024 }, { "answer", 0.5, 2048 },
                { "summary", 0.3, 2048 }, { "queryregeneration", 0.3, 256 } };
        for (final Object[] row : table) {
            final LlmChatRequest request = userRequest("q");
            client.applyDefaultParams(request, (String) row[0]);
            assertEquals(row[0] + " temperature", (Double) row[1], request.getTemperature());
            assertEquals(row[0] + " maxTokens", (Integer) row[2], request.getMaxTokens());
        }
        final LlmChatRequest explicit = userRequest("q").setTemperature(0.0).setMaxTokens(77);
        client.applyDefaultParams(explicit, "answer");
        assertEquals(0.0, explicit.getTemperature());
        assertEquals(77, explicit.getMaxTokens().intValue());
        final LlmChatRequest unknown = userRequest("q");
        client.applyDefaultParams(unknown, "unknown");
        assertNull(unknown.getTemperature(), "unknown prompt type gets no default");
    }

    @Test
    public void test_additionalFields_globalAndPerTypeReplaces() {
        client.set("additional.model.request.fields", "{\"reasoningConfig\":{\"type\":\"enabled\",\"maxReasoningEffort\":\"low\"}}");
        final JsonNode global = MAPPER.valueToTree(client.buildRequestBody(userRequest("q")));
        assertEquals("low", global.at("/additionalModelRequestFields/reasoningConfig/maxReasoningEffort").asString());

        client.set("answer.additional.model.request.fields", "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024}}");
        final LlmChatRequest answer = userRequest("q");
        client.applyPromptTypeParams(answer, "answer");
        final JsonNode perType = MAPPER.valueToTree(client.buildRequestBody(answer));
        assertEquals(1024, perType.at("/additionalModelRequestFields/thinking/budget_tokens").asInt());
        assertFalse("per-type replaces global", perType.get("additionalModelRequestFields").has("reasoningConfig"));

        final LlmChatRequest intent = userRequest("q");
        client.applyPromptTypeParams(intent, "intent");
        final JsonNode fallback = MAPPER.valueToTree(client.buildRequestBody(intent));
        assertTrue("a type without its own value uses the global one", fallback.get("additionalModelRequestFields").has("reasoningConfig"));
    }

    @Test
    public void test_additionalFields_invalidValuesWarnEveryTimeAndAreNotSent() {
        client.set("additional.model.request.fields", "{\"thinking\":");
        assertFalse(MAPPER.valueToTree(client.buildRequestBody(userRequest("q"))).has("additionalModelRequestFields"));
        client.buildRequestBody(userRequest("q"));
        assertEquals(2,
                capture.warnings()
                        .stream()
                        .filter(m -> m.contains("rag.llm.bedrock.additional.model.request.fields is not valid JSON"))
                        .count());

        client.set("summary.additional.model.request.fields", "[1,2]");
        final LlmChatRequest summary = userRequest("q");
        client.applyPromptTypeParams(summary, "summary");
        assertFalse(MAPPER.valueToTree(client.buildRequestBody(summary)).has("additionalModelRequestFields"));
        assertTrue(capture.warnings()
                .stream()
                .anyMatch(m -> m.contains("rag.llm.bedrock.summary.additional.model.request.fields is not a JSON object")));
    }

    @Test
    public void test_thinkingBudget_warnsAndIsNotSent() {
        final LlmChatRequest request = userRequest("q").setThinkingBudget(2048);
        final JsonNode body = MAPPER.valueToTree(client.buildRequestBody(request));
        assertFalse(body.toString().contains("2048"));
        assertTrue(capture.warnings()
                .stream()
                .anyMatch(m -> m.contains("thinking.budget is not supported") && m.contains("additional.model.request.fields")));
    }

    @Test
    public void test_buildMessages_normalizesHistoryForConverse() {
        // The intent prompt keeps the last N history messages, which can start on an assistant
        // turn; Converse rejects that, two consecutive messages of one role, and a blank text block.
        final List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system("sys"));
        messages.add(LlmMessage.assistant("an earlier answer"));
        messages.add(LlmMessage.user("first question"));
        messages.add(LlmMessage.user("   "));
        messages.add(LlmMessage.user("second question"));
        messages.add(LlmMessage.assistant(""));
        messages.add(LlmMessage.assistant("an answer"));
        messages.add(new LlmMessage("tool", "odd role"));
        final JsonNode converse = MAPPER.valueToTree(client.buildMessages(messages));
        assertEquals(3, converse.size());
        assertEquals("user", converse.at("/0/role").asString());
        assertEquals("first question", converse.at("/0/content/0/text").asString());
        assertEquals("second question", converse.at("/0/content/1/text").asString());
        assertEquals(2, converse.at("/0/content").size());
        assertEquals("assistant", converse.at("/1/role").asString());
        assertEquals("an answer", converse.at("/1/content/0/text").asString());
        assertEquals("user", converse.at("/2/role").asString());
        assertEquals("odd role", converse.at("/2/content/0/text").asString());
    }

    // --- chat ---

    @Test
    public void test_chat_success_withApiKey() throws Exception {
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        final LlmChatResponse response = client.chat(new LlmChatRequest().addSystemMessage("sys").addUserMessage("Hi").setMaxTokens(64));
        assertEquals("Hello from Nova", response.getContent());
        assertEquals("end_turn", response.getFinishReason());
        assertEquals(12, response.getPromptTokens().intValue());
        assertEquals(4, response.getCompletionTokens().intValue());
        assertEquals(16, response.getTotalTokens().intValue());
        assertEquals("us.amazon.nova-2-lite-v1:0", response.getModel());

        final RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/model/us.amazon.nova-2-lite-v1%3A0/converse", recorded.getPath());
        assertEquals("Bearer " + TestableBedrockLlmClient.API_KEY, recorded.getHeader("Authorization"));
        assertTrue(recorded.getHeader("Content-Type").startsWith("application/json"));
        final JsonNode sent = body(recorded);
        assertEquals("sys", sent.at("/system/0/text").asString());
        assertEquals("Hi", sent.at("/messages/0/content/0/text").asString());
        assertEquals(64, sent.at("/inferenceConfig/maxTokens").asInt());
        assertTrue(capture.infos().stream().anyMatch(m -> m.contains("Chat response received") && m.contains("outputTokens=4")));
    }

    @Test
    public void test_chat_configuredModelIsTrimmed() throws Exception {
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("model", " us.amazon.nova-2-lite-v1:0 ").endpoint(mockEndpoint());
        assertEquals("us.amazon.nova-2-lite-v1:0", client.getModel());
        client.chat(userRequest("Hi"));
        assertEquals("/model/us.amazon.nova-2-lite-v1%3A0/converse", mockServer.takeRequest().getPath());
    }

    @Test
    public void test_chat_requestModelOverridesConfiguredModel() throws Exception {
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        final LlmChatResponse response = client.chat(
                userRequest("Hi").setModel("arn:aws:bedrock:us-east-1:123456789012:inference-profile/global.amazon.nova-2-lite-v1:0"));
        assertEquals("arn:aws:bedrock:us-east-1:123456789012:inference-profile/global.amazon.nova-2-lite-v1:0", response.getModel());
        assertEquals(
                "/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fglobal.amazon.nova-2-lite-v1%3A0/converse",
                mockServer.takeRequest().getPath());
    }

    @Test
    public void test_chat_withSigV4() throws Exception {
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.endpoint(mockEndpoint());
        client.chat(userRequest("Hi"));
        final RecordedRequest recorded = mockServer.takeRequest();
        final String authorization = recorded.getHeader("Authorization");
        assertTrue(authorization,
                authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261002/us-east-1/bedrock/aws4_request, "));
        assertEquals("20261002T000000Z", recorded.getHeader("X-Amz-Date"));
        assertNotNull(recorded.getHeader("x-amz-content-sha256"));
    }

    @Test
    public void test_chat_textBlocksConcatenatedAndReasoningSkipped() {
        mockServer.enqueue(json(200, "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"reasoningContent\":{\"reasoningText\":{\"text\":\"let me think\",\"signature\":\"x\"}}},"
                + "{\"text\":\"Part one. \"},{\"text\":\"Part two.\"}]}},\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":1,\"outputTokens\":2,\"totalTokens\":3}}"));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        assertEquals("Part one. Part two.", client.chat(userRequest("Hi")).getContent());
    }

    @Test
    public void test_chat_maxTokensStopReasonWarns() {
        mockServer.enqueue(json(200, "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"Trunc\"}]}},"
                + "\"stopReason\":\"max_tokens\",\"usage\":{\"inputTokens\":1,\"outputTokens\":2,\"totalTokens\":3}}"));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        final LlmChatResponse response = client.chat(userRequest("Hi"));
        assertEquals("max_tokens", response.getFinishReason());
        assertTrue(
                capture.warnings().stream().anyMatch(m -> m.contains("Chat finished abnormally") && m.contains("stopReason=max_tokens")));
    }

    @Test
    public void test_isAbnormalFinishReason() {
        assertFalse(BedrockLlmClient.isAbnormalFinishReason(null));
        assertFalse(BedrockLlmClient.isAbnormalFinishReason(" "));
        assertFalse(BedrockLlmClient.isAbnormalFinishReason("end_turn"));
        assertFalse(BedrockLlmClient.isAbnormalFinishReason("stop_sequence"));
        for (final String reason : new String[] { "max_tokens", "tool_use", "guardrail_intervened", "content_filtered",
                "malformed_model_output", "malformed_tool_use", "model_context_window_exceeded", "something_new" }) {
            assertTrue(reason, BedrockLlmClient.isAbnormalFinishReason(reason));
        }
    }

    @Test
    public void test_chat_invalidJsonResponseIsInvalidResponse() {
        mockServer.enqueue(json(200, "<html>proxy page</html>"));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_INVALID_RESPONSE, e.getErrorCode());
        }
    }

    @Test
    public void test_chat_errorMapping() {
        final Object[][] cases = { { 403, "AccessDeniedException", "You don't have access to the model.", LlmException.ERROR_AUTH },
                { 404, "ResourceNotFoundException", "Model not found.", LlmException.ERROR_MODEL_NOT_FOUND },
                { 408, "ModelTimeoutException", "Model timed out.", LlmException.ERROR_TIMEOUT },
                { 400, "ValidationException", "Input is too long for requested model.", LlmException.ERROR_CONTEXT_LENGTH_EXCEEDED },
                { 400, "ValidationException", "Malformed input request.", LlmException.ERROR_UNKNOWN },
                { 424, "ModelErrorException", "The model failed.", LlmException.ERROR_UNKNOWN } };
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        for (final Object[] c : cases) {
            mockServer.enqueue(bedrockError((Integer) c[0], (String) c[1], (String) c[2]));
            try {
                client.chat(userRequest("Hi"));
                fail("expected LlmException for " + c[0]);
            } catch (final LlmException e) {
                assertEquals(c[0] + " " + c[2], c[3], e.getErrorCode());
                assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(c[0])));
            }
            assertTrue(capture.warnings()
                    .stream()
                    .anyMatch(m -> m.contains("API error") && m.contains("statusCode=" + c[0]) && m.contains("type=" + c[1])
                            && m.contains((String) c[2])));
        }
        assertEquals("non-retryable statuses are sent once", cases.length, mockServer.getRequestCount());
    }

    @Test
    public void test_chat_retryAfterIsHonoredThenSucceeds() {
        mockServer.enqueue(bedrockError(429, "ThrottlingException", "Too many requests").setHeader("Retry-After", "1"));
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        final long start = System.currentTimeMillis();
        assertEquals("Hello from Nova", client.chat(userRequest("Hi")).getContent());
        assertTrue("Retry-After: 1 must be slept", System.currentTimeMillis() - start >= 900L);
        assertEquals(2, mockServer.getRequestCount());
        assertTrue(capture.infos()
                .stream()
                .anyMatch(m -> m.contains("chat retrying") && m.contains("status=429") && m.contains("sleepMs=1000")));
    }

    @Test
    public void test_chat_retryExhaustedReportsLastStatus() {
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("retry.max", "2").endpoint(mockEndpoint());
        mockServer.enqueue(bedrockError(503, "ServiceUnavailableException", "busy"));
        mockServer.enqueue(bedrockError(503, "ServiceUnavailableException", "busy"));
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_SERVICE_UNAVAILABLE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("503"));
        }
        mockServer.enqueue(bedrockError(429, "ThrottlingException", "slow down"));
        mockServer.enqueue(bedrockError(429, "ThrottlingException", "slow down"));
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_RATE_LIMIT, e.getErrorCode());
        }
        assertEquals(4, mockServer.getRequestCount());
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("retry exhausted") && m.contains("lastStatus=503")));
    }

    @Test
    public void test_chat_connectFailureIsRetried() throws IOException {
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("retry.max", "2").endpoint("http://127.0.0.1:" + port);
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
        }
        assertEquals(1, capture.infos().stream().filter(m -> m.contains("chat retrying") && m.contains("exception=")).count());
    }

    @Test
    public void test_chat_readTimeoutIsNotRetried() {
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("timeout", "500").set("retry.max", "3").endpoint(mockEndpoint());
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
        }
        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    public void test_chat_unresolvableCredentialsIsAuthErrorAndSendsNothing() {
        client.unresolvableCredentials().endpoint(mockEndpoint());
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_AUTH, e.getErrorCode());
        }
        assertEquals(0, mockServer.getRequestCount());
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("AWS credentials could not be resolved") && m.contains("api.key")));
    }

    @Test
    public void test_chat_userInfoEndpointIsRefusedWithoutEchoingIt() {
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint("https://alice:s3cr3t@bedrock.example.com");
        try {
            client.chat(userRequest("Hi"));
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("rag.llm.bedrock.endpoint"));
            assertFalse(e.getMessage(), e.getMessage().contains("s3cr3t"));
        }
        assertTrue(capture.renderedAt(Level.WARN).stream().noneMatch(m -> m.contains("s3cr3t")));
    }

    @Test
    public void test_executeWithRetry_notifiesOnRetryBetweenAttempts() throws Exception {
        client.set("retry.max", "3").set("retry.base.delay.ms", "0");
        final List<Integer> attempts = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final LlmStreamCallback callback = new LlmStreamCallback() {
            @Override
            public void onChunk(final String chunk, final boolean done) {
                // not used
            }

            @Override
            public void onRetry(final String operation, final int attempt, final int maxAttempts, final long sleepMs,
                    final Throwable cause) {
                attempts.add(attempt);
                assertEquals("test", operation);
                assertEquals(3, maxAttempts);
            }
        };
        try {
            client.executeWithRetry("test", () -> {
                calls.incrementAndGet();
                throw new org.codelibs.fess.bedrock.BedrockRetry.RetryableHttpException(500, "boom", -1L);
            }, callback);
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_SERVICE_UNAVAILABLE, e.getErrorCode());
        }
        assertEquals(3, calls.get());
        assertEquals(List.of(1, 2), attempts);
    }

    // --- availability ---

    @Test
    public void test_availability_apiKeyNeedsNoCredentials() {
        client.set("api.key", TestableBedrockLlmClient.API_KEY).unresolvableCredentials();
        assertTrue(client.testCheckAvailabilityNow());
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    public void test_availability_sigV4() {
        assertTrue(client.testCheckAvailabilityNow());
        client.destroy();
        final TestableBedrockLlmClient noCredentials = new TestableBedrockLlmClient().unresolvableCredentials();
        try {
            assertFalse(noCredentials.testCheckAvailabilityNow());
        } finally {
            noCredentials.destroy();
        }
    }

    @Test
    public void test_availability_credentialFailureIsRemembered() {
        // Off EC2 the default chain ends with a ~2 s instance-metadata probe; the periodic check
        // must not pay it on every run.
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
    public void test_availability_blankModelOrRegion() {
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("model", " ");
        assertFalse(client.testCheckAvailabilityNow());
        client.set("model", "us.amazon.nova-2-lite-v1:0").set("region", "");
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue("blank values are not configuration errors", capture.errors().isEmpty());
    }

    @Test
    public void test_availability_configurationErrorsReportedEveryTime() {
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("region", "us-east-1.example.com");
        assertFalse(client.testCheckAvailabilityNow());
        assertFalse(client.testCheckAvailabilityNow());
        assertEquals(2, capture.errors().stream().filter(m -> m.contains("rag.llm.bedrock.region")).count());

        client.set("region", "us-east-1").set("endpoint", "https://alice:s3cr3t@bedrock.example.com");
        assertFalse(client.testCheckAvailabilityNow());
        assertFalse(client.testCheckAvailabilityNow());
        assertEquals(2, capture.errors().stream().filter(m -> m.contains("rag.llm.bedrock.endpoint")).count());

        client.set("endpoint", "https://bedrock.example.com/a b");
        assertFalse(client.testCheckAvailabilityNow());
        assertTrue(capture.errors().stream().noneMatch(m -> m.contains("s3cr3t") || m.contains("a b")));
    }

    @Test
    public void test_destroy_closesTheCredentialsProvider() {
        final AtomicBoolean closed = new AtomicBoolean();
        client.credentials(new ClosingProvider(closed));
        assertTrue(client.testCheckAvailabilityNow());
        client.destroy();
        assertTrue(closed.get());
    }

    // --- credentials never reach a log ---

    @Test
    public void test_credentialsNeverLogged() {
        enableDebug();
        client.set("api.key", TestableBedrockLlmClient.API_KEY).endpoint(mockEndpoint());
        mockServer.enqueue(json(200, CONVERSE_OK));
        mockServer.enqueue(bedrockError(400, "ValidationException", "bad"));
        client.chat(userRequest("Hi"));
        try {
            client.chat(userRequest("Hi"));
        } catch (final LlmException e) {
            assertFalse(e.getMessage().contains(TestableBedrockLlmClient.API_KEY));
        }
        client.destroy();

        client = new TestableBedrockLlmClient();
        client.endpoint(mockEndpoint());
        mockServer.enqueue(json(200, CONVERSE_OK));
        client.chat(userRequest("Hi"));

        assertFalse("DEBUG must actually be on for this test to mean anything", capture.debugs().isEmpty());
        for (final Level level : new Level[] { Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR }) {
            for (final String line : capture.renderedAt(level)) {
                assertFalse(line, line.contains(TestableBedrockLlmClient.API_KEY));
                assertFalse(line, line.contains(TestableBedrockLlmClient.SECRET_ACCESS_KEY));
                assertFalse(line, line.contains(TestableBedrockLlmClient.ACCESS_KEY_ID));
                assertFalse(line, line.contains("AWS4-HMAC-SHA256"));
            }
        }
    }

    /** A credentials provider that records whether it was closed. */
    private static final class ClosingProvider implements AwsCredentialsProvider, SdkAutoCloseable {
        private final AtomicBoolean closed;

        ClosingProvider(final AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create(TestableBedrockLlmClient.ACCESS_KEY_ID, TestableBedrockLlmClient.SECRET_ACCESS_KEY);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
