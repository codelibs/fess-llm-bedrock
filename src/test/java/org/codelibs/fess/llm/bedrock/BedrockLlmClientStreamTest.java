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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.codelibs.fess.bedrock.EventStreamFrames;
import org.codelibs.fess.llm.LlmChatRequest;
import org.codelibs.fess.llm.LlmException;
import org.codelibs.fess.llm.LlmStreamCallback;
import org.codelibs.fess.unit.LogCapturingAppender;
import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

public class BedrockLlmClientStreamTest extends UnitFessTestCase {

    private MockWebServer mockServer;
    private TestableBedrockLlmClient client;
    private LogCapturingAppender capture;

    @Override
    protected void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        mockServer = new MockWebServer();
        mockServer.start();
        client = new TestableBedrockLlmClient();
        client.set("api.key", TestableBedrockLlmClient.API_KEY).set("retry.max", "3").endpoint(mockServer.url("/").toString());
        capture = LogCapturingAppender.attach(BedrockLlmClient.class);
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        capture.detach();
        client.destroy();
        mockServer.shutdown();
        super.tearDown(testInfo);
    }

    private static MockResponse eventStream(final EventStreamFrames frames) {
        return new MockResponse().setHeader("Content-Type", "application/vnd.amazon.eventstream")
                .setBody(new Buffer().write(frames.toByteArray()));
    }

    /** Records every callback the client makes. */
    private static final class Recorder implements LlmStreamCallback {
        final List<String> chunks = new ArrayList<>();
        final List<Boolean> dones = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final List<Integer> retries = new ArrayList<>();

        @Override
        public void onChunk(final String chunk, final boolean done) {
            chunks.add(chunk);
            dones.add(done);
        }

        @Override
        public void onError(final Throwable error) {
            errors.add(error);
        }

        @Override
        public void onRetry(final String operation, final int attempt, final int maxAttempts, final long sleepMs, final Throwable cause) {
            retries.add(attempt);
        }

        String text() {
            return String.join("", chunks);
        }
    }

    private LlmException streamExpectingFailure(final Recorder recorder) {
        try {
            client.streamChat(new LlmChatRequest().addUserMessage("Hi"), recorder);
            fail("expected LlmException");
            return null;
        } catch (final LlmException e) {
            assertEquals("onError must be called exactly once, before the rethrow", 1, recorder.errors.size());
            assertSame(e, recorder.errors.get(0));
            assertFalse("no terminal chunk after a failure", recorder.dones.contains(Boolean.TRUE));
            return e;
        }
    }

    @Test
    public void test_streamChat_deliversTextThenOneTerminalChunk() throws Exception {
        mockServer.enqueue(eventStream(new EventStreamFrames().start().text("Hello").text(", world").stop("end_turn")));
        final Recorder recorder = new Recorder();
        client.streamChat(new LlmChatRequest().addUserMessage("Hi").setMaxTokens(100), recorder);
        assertEquals(List.of("Hello", ", world", ""), recorder.chunks);
        assertEquals(List.of(false, false, true), recorder.dones);
        assertTrue(recorder.errors.isEmpty());
        final RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("/model/us.amazon.nova-2-lite-v1%3A0/converse-stream", recorded.getPath());
        assertEquals("Bearer " + TestableBedrockLlmClient.API_KEY, recorded.getHeader("Authorization"));
        final String completed = capture.infos().stream().filter(m -> m.contains("Stream completed")).findFirst().orElseThrow();
        assertTrue(completed, completed.contains("chunkCount=2"));
        assertTrue(completed, completed.contains("stopReason=end_turn"));
        assertTrue(completed, completed.contains("inputTokens=11"));
        assertTrue(completed, completed.contains("outputTokens=7"));
        assertTrue(completed, completed.contains("totalTokens=18"));
    }

    @Test
    public void test_streamChat_reasoningDeltasAreDropped() {
        mockServer.enqueue(eventStream(new EventStreamFrames().start()
                .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"text\":\"thinking...\"}}}")
                .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"signature\":\"sig\"}}}")
                .text("Answer")
                .stop("end_turn")));
        final Recorder recorder = new Recorder();
        client.streamChat(new LlmChatRequest().addUserMessage("Hi"), recorder);
        assertEquals("Answer", recorder.text());
        assertTrue(capture.infos().stream().anyMatch(m -> m.contains("Stream completed") && m.contains("reasoningDeltaCount=2")));
    }

    @Test
    public void test_streamChat_abnormalStopReasonWarnsButCompletes() {
        mockServer.enqueue(eventStream(new EventStreamFrames().start().text("Truncat").stop("max_tokens")));
        final Recorder recorder = new Recorder();
        client.streamChat(new LlmChatRequest().addUserMessage("Hi"), recorder);
        assertEquals(List.of("Truncat", ""), recorder.chunks);
        assertTrue(
                capture.warnings().stream().anyMatch(m -> m.contains("Stream finished abnormally") && m.contains("stopReason=max_tokens")));
    }

    @Test
    public void test_streamChat_exceptionFrameAfterTextIsMappedAndNotRetried() {
        mockServer.enqueue(eventStream(new EventStreamFrames().start()
                .text("Hel")
                .exception("throttlingException", "{\"message\":\"Too many tokens, please wait.\"}")));
        final Recorder recorder = new Recorder();
        final LlmException e = streamExpectingFailure(recorder);
        assertEquals(LlmException.ERROR_RATE_LIMIT, e.getErrorCode());
        assertEquals(List.of("Hel"), recorder.chunks);
        assertEquals("a stream that has started is never retried", 1, mockServer.getRequestCount());
        assertTrue(capture.warnings()
                .stream()
                .anyMatch(m -> m.contains("Stream exception received") && m.contains("exceptionType=throttlingException")
                        && m.contains("Too many tokens")));
    }

    @Test
    public void test_streamExceptionErrorCode() {
        assertEquals(LlmException.ERROR_RATE_LIMIT, BedrockLlmClient.streamExceptionErrorCode("throttlingException", "x"));
        assertEquals(LlmException.ERROR_SERVICE_UNAVAILABLE, BedrockLlmClient.streamExceptionErrorCode("serviceUnavailableException", "x"));
        assertEquals(LlmException.ERROR_CONTEXT_LENGTH_EXCEEDED,
                BedrockLlmClient.streamExceptionErrorCode("validationException", "Input is too long for requested model."));
        assertEquals(LlmException.ERROR_INVALID_RESPONSE, BedrockLlmClient.streamExceptionErrorCode("validationException", "bad field"));
        assertEquals(LlmException.ERROR_UNKNOWN, BedrockLlmClient.streamExceptionErrorCode("modelStreamErrorException", "x"));
        assertEquals(LlmException.ERROR_UNKNOWN, BedrockLlmClient.streamExceptionErrorCode("internalServerException", "x"));
        assertEquals(LlmException.ERROR_UNKNOWN, BedrockLlmClient.streamExceptionErrorCode(null, null));
    }

    @Test
    public void test_streamChat_validationExceptionTooLong() {
        mockServer.enqueue(eventStream(
                new EventStreamFrames().exception("validationException", "{\"message\":\"Input is too long for requested model.\"}")));
        assertEquals(LlmException.ERROR_CONTEXT_LENGTH_EXCEEDED, streamExpectingFailure(new Recorder()).getErrorCode());
    }

    @Test
    public void test_streamChat_errorFrameFails() {
        mockServer.enqueue(eventStream(new EventStreamFrames().start().error("InternalFailure", "stream broke")));
        assertEquals(LlmException.ERROR_UNKNOWN, streamExpectingFailure(new Recorder()).getErrorCode());
        assertTrue(
                capture.warnings().stream().anyMatch(m -> m.contains("Stream error received") && m.contains("errorCode=InternalFailure")));
    }

    @Test
    public void test_streamChat_missingMessageStopIsInvalidResponse() {
        mockServer.enqueue(eventStream(new EventStreamFrames().start().text("Half an ans")));
        final Recorder recorder = new Recorder();
        final LlmException e = streamExpectingFailure(recorder);
        assertEquals(LlmException.ERROR_INVALID_RESPONSE, e.getErrorCode());
        assertEquals(List.of("Half an ans"), recorder.chunks);
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("Stream ended without a messageStop event")));
    }

    @Test
    public void test_streamChat_corruptFrameIsInvalidResponse() {
        final byte[] body = new EventStreamFrames().start().text("Hello").stop("end_turn").toByteArray();
        body[body.length / 2] ^= 0x55;
        mockServer.enqueue(
                new MockResponse().setHeader("Content-Type", "application/vnd.amazon.eventstream").setBody(new Buffer().write(body)));
        assertEquals(LlmException.ERROR_INVALID_RESPONSE, streamExpectingFailure(new Recorder()).getErrorCode());
        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    public void test_streamChat_disconnectMidBodyIsNotRetried() {
        final EventStreamFrames frames = new EventStreamFrames().start();
        for (int i = 0; i < 200; i++) {
            frames.text("chunk-" + i + " ");
        }
        frames.stop("end_turn");
        mockServer.enqueue(eventStream(frames).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        mockServer.enqueue(eventStream(new EventStreamFrames().start().text("replayed").stop("end_turn")));
        final Recorder recorder = new Recorder();
        final LlmException e = streamExpectingFailure(recorder);
        assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
        assertEquals(1, mockServer.getRequestCount());
        assertFalse(recorder.text().contains("replayed"));
    }

    @Test
    public void test_streamChat_retriesBeforeTheBodyStarts() {
        mockServer.enqueue(new MockResponse().setResponseCode(503)
                .setHeader("x-amzn-ErrorType", "ServiceUnavailableException")
                .setBody("{\"message\":\"busy\"}"));
        mockServer.enqueue(eventStream(new EventStreamFrames().start().text("ok").stop("end_turn")));
        final Recorder recorder = new Recorder();
        client.streamChat(new LlmChatRequest().addUserMessage("Hi"), recorder);
        assertEquals(List.of("ok", ""), recorder.chunks);
        assertEquals(List.of(1), recorder.retries);
        assertEquals(2, mockServer.getRequestCount());
        assertTrue(capture.warnings().stream().anyMatch(m -> m.contains("Streaming API error") && m.contains("statusCode=503")));
    }

    @Test
    public void test_streamChat_nonRetryableStatusFailsWithMappedCode() {
        mockServer.enqueue(new MockResponse().setResponseCode(403)
                .setHeader("x-amzn-ErrorType", "AccessDeniedException")
                .setBody("{\"message\":\"no access\"}"));
        assertEquals(LlmException.ERROR_AUTH, streamExpectingFailure(new Recorder()).getErrorCode());
        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    public void test_streamChat_callbackFailureCancelsAndReportsOnError() {
        final EventStreamFrames frames = new EventStreamFrames().start();
        for (int i = 0; i < 50; i++) {
            frames.text("chunk-" + i);
        }
        mockServer.enqueue(eventStream(frames.stop("end_turn")));
        final List<Throwable> errors = new ArrayList<>();
        final LlmStreamCallback failing = new LlmStreamCallback() {
            @Override
            public void onChunk(final String chunk, final boolean done) {
                throw new IllegalStateException("client went away");
            }

            @Override
            public void onError(final Throwable error) {
                errors.add(error);
            }
        };
        try {
            client.streamChat(new LlmChatRequest().addUserMessage("Hi"), failing);
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
            assertEquals(1, errors.size());
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void test_streamChat_abandonedStreamIsNotDrained() {
        // When the caller gives up (the browser closed the SSE connection), the rest of the answer
        // must not be read to its end: closing an unfinished entity drains it, holding the thread,
        // the concurrency permit and the model until the answer nobody reads is complete.
        final EventStreamFrames frames = new EventStreamFrames().start();
        for (int i = 0; i < 60; i++) {
            frames.text("chunk-" + i + " of an answer nobody will read");
        }
        frames.stop("end_turn");
        // About 9 KB at 256 bytes per 200 ms: reading it to the end takes about 7 seconds.
        mockServer.enqueue(eventStream(frames).throttleBody(256, 200, TimeUnit.MILLISECONDS));
        final IllegalStateException abort = new IllegalStateException("client disconnected");
        final long start = System.currentTimeMillis();
        try {
            client.streamChat(new LlmChatRequest().addUserMessage("Hi"), (chunk, done) -> {
                throw abort;
            });
            fail("expected LlmException");
        } catch (final LlmException e) {
            assertSame(abort, e.getCause());
        }
        final long elapsed = System.currentTimeMillis() - start;
        assertTrue("streamChat kept reading the abandoned stream for " + elapsed + "ms", elapsed < 3000);
        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    public void test_streamChat_userInfoEndpointNotifiesOnError() {
        client.set("endpoint", "https://alice:s3cr3t@bedrock.example.com");
        final Recorder recorder = new Recorder();
        final LlmException e = streamExpectingFailure(recorder);
        assertEquals(LlmException.ERROR_CONNECTION, e.getErrorCode());
        assertFalse(e.getMessage().contains("s3cr3t"));
        assertEquals(0, mockServer.getRequestCount());
    }
}
