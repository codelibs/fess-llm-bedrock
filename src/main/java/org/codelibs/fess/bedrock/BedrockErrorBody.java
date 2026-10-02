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

import java.util.Locale;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders a Bedrock error response as a single log line.
 *
 * <p>Bedrock answers a failed call with a body of the form {@code {"message": "..."}} and names the
 * error in the {@code x-amzn-ErrorType} header, as {@code ThrottlingException} or
 * {@code ThrottlingException:http://internal.amazon.com/coral/com.amazon.bedrock/}. Both the LLM
 * client and the embedding client render it through here.
 */
public final class BedrockErrorBody {

    /** The response header naming the error type. */
    public static final String ERROR_TYPE_HEADER = "x-amzn-ErrorType";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Where a {@code SignatureDoesNotMatch} message starts quoting what was signed, as AWS words it.
     * What follows can include the session token.
     */
    private static final String[] SIGNING_DETAIL_MARKERS =
            { "The Canonical String for this request should have been", "The String-to-Sign should have been" };

    /** Maximum characters of a non-JSON body kept in the rendered line. */
    private static final int MAX_RAW_LENGTH = 1024;

    private BedrockErrorBody() {
        // utility class
    }

    /**
     * Renders the error as {@code type=<type>,message=<message>}. The type comes from the
     * {@code x-amzn-ErrorType} header with anything from the first {@code ':'} removed, or
     * {@code null} when the header is absent. The message is the body's {@code message} (or
     * {@code Message}) field; a body that is not such a JSON object is kept as trimmed text,
     * clipped to 1024 characters.
     *
     * @param errorBody the response body, may be {@code null}.
     * @param errorTypeHeader the {@code x-amzn-ErrorType} header value, may be {@code null}.
     * @return the rendered line, never {@code null}.
     */
    public static String render(final String errorBody, final String errorTypeHeader) {
        return "type=" + errorType(errorTypeHeader) + ",message=" + message(errorBody);
    }

    /**
     * Returns the error type named by an {@code x-amzn-ErrorType} header value.
     *
     * @param errorTypeHeader the header value, may be {@code null}.
     * @return the type without its {@code :<namespace>} suffix, or {@code null}.
     */
    public static String errorType(final String errorTypeHeader) {
        if (errorTypeHeader == null || errorTypeHeader.isBlank()) {
            return null;
        }
        final String trimmed = errorTypeHeader.trim();
        final int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(0, colon);
    }

    /**
     * Returns the human-readable message of an error body. A {@code SignatureDoesNotMatch} message
     * goes on to quote the canonical request and the string to sign, which can carry the
     * {@code x-amz-security-token} value; everything from that point on is dropped, so the result
     * is safe to log and to put into an exception.
     *
     * @param errorBody the response body, may be {@code null}.
     * @return the {@code message} field, the clipped raw body, or {@code ""}.
     */
    public static String message(final String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "";
        }
        String text = null;
        try {
            final JsonNode root = objectMapper.readTree(errorBody);
            if (root.isObject()) {
                final JsonNode message = root.has("message") ? root.get("message") : root.get("Message");
                if (message != null && message.isString()) {
                    text = message.asString();
                }
            }
        } catch (final JacksonException e) {
            // not JSON - fall through to the raw body
        }
        if (text == null) {
            final String trimmed = errorBody.trim();
            text = trimmed.length() > MAX_RAW_LENGTH ? trimmed.substring(0, MAX_RAW_LENGTH) + "...(truncated)" : trimmed;
        }
        return withoutSigningDetails(text);
    }

    /**
     * Cuts a message at the point where AWS starts quoting the canonical request or the string to
     * sign.
     *
     * @param text the message.
     * @return the message up to that point, trimmed.
     */
    static String withoutSigningDetails(final String text) {
        int cut = text.length();
        for (final String marker : SIGNING_DETAIL_MARKERS) {
            final int index = text.indexOf(marker);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return cut == text.length() ? text : text.substring(0, cut).trim();
    }

    /**
     * Returns whether a validation message says the input does not fit the model, which Bedrock
     * reports as {@code 400 ValidationException} with wording such as "Input is too long for
     * requested model" or a mention of the context window.
     *
     * @param message the error message, may be {@code null}.
     * @return true when the message indicates the input is too long.
     */
    public static boolean indicatesInputTooLong(final String message) {
        if (message == null) {
            return false;
        }
        final String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("too long") || lower.contains("context window") || lower.contains("context length");
    }
}
