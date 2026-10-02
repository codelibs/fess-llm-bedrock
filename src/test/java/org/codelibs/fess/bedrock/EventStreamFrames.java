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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import software.amazon.eventstream.HeaderValue;
import software.amazon.eventstream.Message;

/**
 * Builds {@code application/vnd.amazon.eventstream} bodies for tests with AWS's own encoder, so a
 * test body is byte-for-byte what Bedrock would send.
 */
public final class EventStreamFrames {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /**
     * Appends an {@code event} frame.
     *
     * @param eventType the {@code :event-type}, e.g. {@code contentBlockDelta}.
     * @param json the JSON payload.
     * @return this builder.
     */
    public EventStreamFrames event(final String eventType, final String json) {
        final Map<String, HeaderValue> headers = new LinkedHashMap<>();
        headers.put(":message-type", HeaderValue.fromString("event"));
        headers.put(":event-type", HeaderValue.fromString(eventType));
        headers.put(":content-type", HeaderValue.fromString("application/json"));
        return frame(headers, json);
    }

    /**
     * Appends an {@code exception} frame.
     *
     * @param exceptionType the {@code :exception-type}, e.g. {@code throttlingException}.
     * @param json the JSON payload, e.g. {@code {"message":"..."}}.
     * @return this builder.
     */
    public EventStreamFrames exception(final String exceptionType, final String json) {
        final Map<String, HeaderValue> headers = new LinkedHashMap<>();
        headers.put(":message-type", HeaderValue.fromString("exception"));
        headers.put(":exception-type", HeaderValue.fromString(exceptionType));
        headers.put(":content-type", HeaderValue.fromString("application/json"));
        return frame(headers, json);
    }

    /**
     * Appends an {@code error} frame.
     *
     * @param code the {@code :error-code}.
     * @param message the {@code :error-message}.
     * @return this builder.
     */
    public EventStreamFrames error(final String code, final String message) {
        final Map<String, HeaderValue> headers = new LinkedHashMap<>();
        headers.put(":message-type", HeaderValue.fromString("error"));
        headers.put(":error-code", HeaderValue.fromString(code));
        headers.put(":error-message", HeaderValue.fromString(message));
        return frame(headers, "");
    }

    /**
     * Appends a delta carrying answer text.
     *
     * @param text the text.
     * @return this builder.
     */
    public EventStreamFrames text(final String text) {
        return event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":" + jsonString(text) + "},\"p\":\"abcd\"}");
    }

    /**
     * Appends the frames that open a message and its first content block.
     *
     * @return this builder.
     */
    public EventStreamFrames start() {
        event("messageStart", "{\"role\":\"assistant\",\"p\":\"ab\"}");
        return event("contentBlockStart", "{\"contentBlockIndex\":0,\"start\":{},\"p\":\"ab\"}");
    }

    /**
     * Appends {@code contentBlockStop}, {@code messageStop} and {@code metadata}.
     *
     * @param stopReason the stop reason.
     * @return this builder.
     */
    public EventStreamFrames stop(final String stopReason) {
        event("contentBlockStop", "{\"contentBlockIndex\":0,\"p\":\"ab\"}");
        event("messageStop", "{\"stopReason\":\"" + stopReason + "\",\"p\":\"ab\"}");
        return event("metadata", "{\"usage\":{\"inputTokens\":11,\"outputTokens\":7,\"totalTokens\":18},\"metrics\":{\"latencyMs\":42}}");
    }

    /**
     * Returns the encoded body.
     *
     * @return the bytes.
     */
    public byte[] toByteArray() {
        return out.toByteArray();
    }

    private EventStreamFrames frame(final Map<String, HeaderValue> headers, final String json) {
        new Message(headers, json.getBytes(StandardCharsets.UTF_8)).encode(out);
        return this;
    }

    private static String jsonString(final String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
