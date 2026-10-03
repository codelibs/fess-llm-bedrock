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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.bedrock.BedrockAuth;
import org.codelibs.fess.bedrock.BedrockEndpoint;
import org.codelibs.fess.bedrock.BedrockErrorBody;
import org.codelibs.fess.bedrock.BedrockRetry;
import org.codelibs.fess.bedrock.EventStreamReader;
import org.codelibs.fess.llm.AbstractLlmClient;
import org.codelibs.fess.llm.LlmChatRequest;
import org.codelibs.fess.llm.LlmChatResponse;
import org.codelibs.fess.llm.LlmException;
import org.codelibs.fess.llm.LlmMessage;
import org.codelibs.fess.llm.LlmStreamCallback;
import org.codelibs.fess.llm.LlmUsage;
import org.codelibs.fess.util.ComponentUtil;
import org.codelibs.fess.util.CredentialUrlUtil;

import software.amazon.eventstream.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * LLM client for Amazon Bedrock, through the Converse and ConverseStream APIs of Bedrock Runtime.
 *
 * <p>Requests go through the HttpClient 5 that {@link AbstractLlmClient} builds, like every other
 * {@code fess-llm-*} client; the AWS SDK is used only to resolve credentials and sign the request
 * (see {@link BedrockAuth}) and to decode the ConverseStream event stream (see {@link EventStreamReader}).
 * Configuration is read through {@code FessConfig.getOrDefault} under {@value #CONFIG_PREFIX}.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html">Converse</a>
 */
public class BedrockLlmClient extends AbstractLlmClient {

    private static final Logger logger = LogManager.getLogger(BedrockLlmClient.class);

    /** The name identifier of this client, matched against {@code rag.llm.name}. */
    protected static final String NAME = "bedrock";

    /** The configuration key prefix. */
    protected static final String CONFIG_PREFIX = "rag.llm.bedrock";

    /** The model used when none is configured: Amazon Nova 2 Lite through the US inference profile. */
    protected static final String DEFAULT_MODEL = "us.amazon.nova-2-lite-v1:0";

    /** Key suffix of the raw JSON passed as {@code additionalModelRequestFields}. */
    protected static final String CONFIG_ADDITIONAL_FIELDS = "additional.model.request.fields";

    /** Key suffix of the switch that stops {@code temperature} from being sent. */
    protected static final String CONFIG_TEMPERATURE_ENABLED = "temperature.enabled";

    /** Extra-param key carrying a per-prompt-type {@code additionalModelRequestFields} value. */
    static final String EXTRA_ADDITIONAL_FIELDS = "bedrock.additional.model.request.fields";

    /** Extra-param key carrying the configuration key the per-prompt-type value came from. */
    static final String EXTRA_ADDITIONAL_FIELDS_KEY = "bedrock.additional.model.request.fields.key";

    /** Stop reasons that mean the model finished normally. */
    private static final Set<String> NORMAL_STOP_REASONS = Set.of("end_turn", "stop_sequence");

    private static final String ROLE_USER = "user";

    private static final String ROLE_ASSISTANT = "assistant";

    private BedrockAuth auth;

    /**
     * Default constructor.
     */
    public BedrockLlmClient() {
        // Default constructor
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected String getConfigPrefix() {
        return CONFIG_PREFIX;
    }

    /**
     * Reads a configuration value under {@value #CONFIG_PREFIX} through {@code getOrDefault}, i.e.
     * {@code fess_config.properties} plus {@code -Dfess.config.*} - the channel of every
     * {@code rag.llm.*} key. Not {@code conf/system.properties}.
     *
     * @param keySuffix the key suffix appended to the prefix and a dot.
     * @param defaultValue the value returned when the key is absent.
     * @return the configured value, or {@code defaultValue}.
     */
    protected String getConfigString(final String keySuffix, final String defaultValue) {
        return ComponentUtil.getFessConfig().getOrDefault(getConfigPrefix() + "." + keySuffix, defaultValue);
    }

    /**
     * Reads a positive integer through {@link #getConfigString(String, String)}; a missing,
     * non-positive or unparseable value yields {@code defaultValue}, the last with a WARN.
     */
    @Override
    protected int getConfigInt(final String keySuffix, final int defaultValue) {
        final String value = getConfigString(keySuffix, null);
        if (value != null) {
            try {
                final int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (final NumberFormatException e) {
                logger.warn("[LLM:BEDROCK] Invalid config value for key={}.{}: '{}'. Using default: {}", getConfigPrefix(), keySuffix,
                        value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * Gets the Bedrock API key. Blank selects SigV4 with AWS credentials.
     *
     * @return the API key, or {@code ""}.
     */
    protected String getApiKey() {
        return getConfigString("api.key", "");
    }

    /**
     * Gets the AWS region.
     *
     * @return the region (default {@value BedrockEndpoint#DEFAULT_REGION}).
     */
    protected String getRegion() {
        final String region = getConfigString("region", BedrockEndpoint.DEFAULT_REGION);
        return region == null ? "" : region.trim();
    }

    /**
     * Gets the endpoint override, e.g. a VPC interface endpoint.
     *
     * @return the endpoint, or {@code ""} to derive it from the region.
     */
    protected String getEndpoint() {
        return getConfigString("endpoint", "");
    }

    @Override
    protected String getModel() {
        final String model = getConfigString("model", DEFAULT_MODEL);
        return model == null ? null : model.trim();
    }

    @Override
    protected int getTimeout() {
        return getConfigInt("timeout", 120000);
    }

    @Override
    protected int getAvailabilityCheckInterval() {
        return getConfigInt("availability.check.interval", 60);
    }

    @Override
    protected boolean isRagChatEnabled() {
        return Boolean.parseBoolean(ComponentUtil.getFessConfig().getOrDefault("rag.chat.enabled", "false"));
    }

    @Override
    protected String getLlmType() {
        return ComponentUtil.getFessConfig().getSystemProperty("rag.llm.name", "ollama");
    }

    /**
     * Maximum attempts (initial call plus retries) for one HTTP call.
     *
     * @return {@code rag.llm.bedrock.retry.max} (default 10).
     */
    protected int getRetryMaxAttempts() {
        return getConfigInt("retry.max", 10);
    }

    /**
     * Base delay of the exponential backoff between attempts.
     *
     * @return {@code rag.llm.bedrock.retry.base.delay.ms} (default 2000); {@code 0} is allowed.
     */
    protected long getRetryBaseDelayMs() {
        final String value = getConfigString("retry.base.delay.ms", "2000");
        if (value == null) {
            return 2000L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("[LLM:BEDROCK] Invalid {}.retry.base.delay.ms value: '{}'. Using 2000.", getConfigPrefix(), value);
            return 2000L;
        }
    }

    /**
     * Whether {@code temperature} may be sent. {@code false} keeps it out of every request, for a
     * model mode that rejects sampling parameters (Claude with extended thinking). Any value other
     * than {@code true} / {@code false} / blank is reported and read as {@code true}.
     *
     * @return {@code rag.llm.bedrock.temperature.enabled} (default true).
     */
    protected boolean isTemperatureEnabled() {
        final String value = getConfigString(CONFIG_TEMPERATURE_ENABLED, "true");
        if (StringUtil.isBlank(value) || "true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        logger.warn("[LLM:BEDROCK] Invalid {}.{} value: {}. Using true.", getConfigPrefix(), CONFIG_TEMPERATURE_ENABLED, value);
        return true;
    }

    @Override
    protected int getContextMaxChars(final String promptType) {
        final int defaultValue = "answer".equals(promptType) || "summary".equals(promptType) ? 16000 : 10000;
        return getConfigInt(promptType + ".context.max.chars", defaultValue);
    }

    @Override
    protected int getEvaluationMaxRelevantDocs() {
        return getConfigInt("chat.evaluation.max.relevant.docs", 3);
    }

    @Override
    protected int getEvaluationDescriptionMaxChars() {
        return getConfigInt("chat.evaluation.description.max.chars", 500);
    }

    @Override
    protected int getHistoryMaxChars() {
        return getConfigInt("history.max.chars", 8000);
    }

    @Override
    protected int getIntentHistoryMaxMessages() {
        return getConfigInt("intent.history.max.messages", 8);
    }

    @Override
    protected int getIntentHistoryMaxChars() {
        return getConfigInt("intent.history.max.chars", 4000);
    }

    @Override
    public int getHistoryAssistantMaxChars() {
        return getConfigInt("history.assistant.max.chars", 800);
    }

    @Override
    public int getHistoryAssistantSummaryMaxChars() {
        return getConfigInt("history.assistant.summary.max.chars", 800);
    }

    /**
     * Creates the request authorizer. Called once, on first use.
     *
     * @return the authorizer.
     */
    protected BedrockAuth createAuth() {
        return new BedrockAuth();
    }

    /**
     * Returns the request authorizer, creating it on first use.
     *
     * @return the authorizer.
     */
    protected synchronized BedrockAuth getAuth() {
        if (auth == null) {
            auth = createAuth();
        }
        return auth;
    }

    @Override
    public void destroy() {
        super.destroy();
        synchronized (this) {
            if (auth != null) {
                auth.close();
                auth = null;
            }
        }
    }

    /** The configuration key named when the endpoint is refused or malformed. */
    private String endpointConfigKey() {
        return getConfigPrefix() + ".endpoint";
    }

    /**
     * Reports whether Bedrock can be called, without calling it: the model and region are set, the
     * endpoint is a usable URL, and either an API key is configured or AWS credentials resolve.
     * Never throws; a configuration that can never work is reported at ERROR every time it is
     * checked.
     */
    @Override
    protected boolean checkAvailabilityNow() {
        if (StringUtil.isBlank(getModel())) {
            if (logger.isDebugEnabled()) {
                logger.debug("[LLM:BEDROCK] Bedrock is not available. model is blank");
            }
            return false;
        }
        final String region = getRegion();
        if (StringUtil.isBlank(region)) {
            if (logger.isDebugEnabled()) {
                logger.debug("[LLM:BEDROCK] Bedrock is not available. region is blank");
            }
            return false;
        }
        if (!BedrockEndpoint.isValidRegion(region)) {
            logger.error("[LLM:BEDROCK] Bedrock is not available. {}.region is not an AWS region name: {}", getConfigPrefix(), region);
            return false;
        }
        try {
            BedrockEndpoint.toUri(BedrockEndpoint.modelUrl(BedrockEndpoint.resolveBaseUrl(getEndpoint(), region), getModel(), "converse"),
                    endpointConfigKey());
        } catch (final IllegalArgumentException e) {
            logger.error("[LLM:BEDROCK] Bedrock is not available. {}", e.getMessage());
            return false;
        }
        if (BedrockAuth.usesApiKey(getApiKey())) {
            return true;
        }
        final String credentialsProblem = getAuth().checkCredentials();
        if (credentialsProblem == null) {
            return true;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("[LLM:BEDROCK] Bedrock is not available. No api.key and AWS credentials could not be resolved. error={}",
                    credentialsProblem);
        }
        return false;
    }

    /**
     * Returns whether a Converse {@code stopReason} means something other than a normal finish:
     * everything except {@code end_turn} and {@code stop_sequence}, including {@code max_tokens},
     * {@code guardrail_intervened}, {@code content_filtered}, {@code tool_use} (Fess never offers
     * tools) and any value added later.
     *
     * @param reason the stop reason, may be {@code null}.
     * @return true when the reason should be reported.
     */
    static boolean isAbnormalFinishReason(final String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return !NORMAL_STOP_REASONS.contains(reason.trim());
    }

    /**
     * The model a request runs against: the per-request model when set, otherwise the configured
     * one.
     *
     * @param request the chat request.
     * @return the model ID.
     */
    protected String resolveModel(final LlmChatRequest request) {
        final String model = request.getModel();
        return StringUtil.isBlank(model) ? getModel() : model;
    }

    /**
     * Builds the URL of a model operation, refusing an endpoint with userinfo before any part of it
     * can be logged.
     *
     * @param model the model ID.
     * @param operation {@code converse} or {@code converse-stream}.
     * @return the URL.
     * @throws LlmException ({@code connection_error}) when the endpoint carries userinfo.
     */
    protected String buildModelUrl(final String model, final String operation) {
        final String baseUrl = BedrockEndpoint.resolveBaseUrl(getEndpoint(), getRegion());
        if (CredentialUrlUtil.hasUserInfo(baseUrl)) {
            throw new LlmException(BedrockEndpoint.userInfoRejectedMessage(endpointConfigKey()), LlmException.ERROR_CONNECTION);
        }
        return BedrockEndpoint.modelUrl(baseUrl, model, operation);
    }

    /**
     * Builds an authorized POST carrying {@code body}. Built anew for every attempt, so a retry
     * after a long backoff carries a fresh SigV4 date.
     *
     * @param url the request URL.
     * @param body the JSON body.
     * @return the request.
     * @throws LlmException ({@code auth_error}) when AWS credentials cannot be resolved.
     */
    protected HttpPost createSignedPost(final String url, final byte[] body) {
        final URI uri = BedrockEndpoint.toUri(url, endpointConfigKey());
        final HttpPost post = new HttpPost(uri);
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        try {
            getAuth().authorize(post, uri, body, getApiKey(), getRegion());
        } catch (final RuntimeException e) {
            logger.warn("[LLM:BEDROCK] AWS credentials could not be resolved. Set {}.api.key, or provide AWS credentials "
                    + "(environment variables, profile, or an instance/task role). error={}", getConfigPrefix(), e.getMessage());
            throw new LlmException("AWS credentials could not be resolved for Bedrock", LlmException.ERROR_AUTH, e);
        }
        return post;
    }

    @Override
    public LlmChatResponse chat(final LlmChatRequest request) {
        final String model = resolveModel(request);
        final String url = buildModelUrl(model, "converse");
        final String maskedUrl = CredentialUrlUtil.maskCredentialInUrl(url);
        final Map<String, Object> requestBody = buildRequestBody(request);
        final long startTime = System.currentTimeMillis();
        if (logger.isDebugEnabled()) {
            logger.debug("[LLM:BEDROCK] Sending chat request to Bedrock. url={}, model={}, messageCount={}", maskedUrl, model,
                    request.getMessages().size());
        }
        try {
            final byte[] json = objectMapper.writeValueAsBytes(requestBody);
            if (logger.isDebugEnabled()) {
                logger.debug("[LLM:BEDROCK] requestBody={}", new String(json, StandardCharsets.UTF_8));
            }
            return executeWithRetry("chat", () -> {
                final HttpPost httpRequest = createSignedPost(url, json);
                try (var response = getHttpClient().execute(httpRequest)) {
                    final int statusCode = response.getCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        throw toErrorException(response, maskedUrl, "API error");
                    }
                    final String responseBody =
                            response.getEntity() != null ? new String(EntityUtils.toByteArray(response.getEntity()), StandardCharsets.UTF_8)
                                    : "";
                    if (logger.isDebugEnabled()) {
                        logger.debug("[LLM:BEDROCK] responseBody={}", responseBody);
                    }
                    final LlmChatResponse chatResponse = parseConverseResponse(responseBody, model);
                    logger.info(
                            "[LLM:BEDROCK] Chat response received. model={}, inputTokens={}, outputTokens={}, totalTokens={}, "
                                    + "stopReason={}, contentLength={}, elapsedTime={}ms",
                            model, chatResponse.getPromptTokens(), chatResponse.getCompletionTokens(), chatResponse.getTotalTokens(),
                            chatResponse.getFinishReason(), chatResponse.getContent() != null ? chatResponse.getContent().length() : 0,
                            System.currentTimeMillis() - startTime);
                    if (isAbnormalFinishReason(chatResponse.getFinishReason())) {
                        logger.warn("[LLM:BEDROCK] Chat finished abnormally. stopReason={}, outputTokens={}, contentLength={}, model={}",
                                chatResponse.getFinishReason(), chatResponse.getCompletionTokens(),
                                chatResponse.getContent() != null ? chatResponse.getContent().length() : 0, model);
                    }
                    return chatResponse;
                }
            }, null);
        } catch (final LlmException e) {
            throw e;
        } catch (final Exception e) {
            logger.warn("[LLM:BEDROCK] Failed to call Bedrock API. url={}, error={}", maskedUrl, e.getMessage(), e);
            throw new LlmException("Failed to call Bedrock API", LlmException.ERROR_CONNECTION, e);
        }
    }

    @Override
    public void streamChat(final LlmChatRequest request, final LlmStreamCallback callback) {
        final String model = resolveModel(request);
        final String url;
        try {
            url = buildModelUrl(model, "converse-stream");
        } catch (final LlmException e) {
            callback.onError(e);
            throw e;
        }
        final String maskedUrl = CredentialUrlUtil.maskCredentialInUrl(url);
        final Map<String, Object> requestBody = buildRequestBody(request);
        final long startTime = System.currentTimeMillis();
        if (logger.isDebugEnabled()) {
            logger.debug("[LLM:BEDROCK] Starting streaming chat request to Bedrock. url={}, model={}, messageCount={}", maskedUrl, model,
                    request.getMessages().size());
        }
        try {
            final byte[] json = objectMapper.writeValueAsBytes(requestBody);
            if (logger.isDebugEnabled()) {
                logger.debug("[LLM:BEDROCK] requestBody={}", new String(json, StandardCharsets.UTF_8));
            }
            executeWithRetry("streamChat", () -> {
                final HttpPost httpRequest = createSignedPost(url, json);
                try (var response = getHttpClient().execute(httpRequest)) {
                    final int statusCode = response.getCode();
                    if (logger.isDebugEnabled()) {
                        final Header contentType = response.getFirstHeader("Content-Type");
                        logger.debug("[LLM:BEDROCK] Stream response received. statusCode={}, contentType={}", statusCode,
                                contentType != null ? contentType.getValue() : null);
                    }
                    if (statusCode < 200 || statusCode >= 300) {
                        throw toErrorException(response, maskedUrl, "Streaming API error");
                    }
                    if (response.getEntity() == null) {
                        logger.warn("[LLM:BEDROCK] Empty response from Bedrock streaming API. url={}", maskedUrl);
                        throw new LlmException("Empty response from Bedrock", LlmException.ERROR_INVALID_RESPONSE);
                    }
                    // From here on the body is flowing and chunks may already have reached the
                    // callback, so no failure below may be retried: another attempt would replay
                    // the answer from its start.
                    try {
                        consumeStream(model, httpRequest, response.getEntity().getContent(), callback, startTime);
                    } catch (final EventStreamReader.MalformedFrameException e) {
                        logger.warn("[LLM:BEDROCK] Stream carried a malformed frame. url={}, error={}", maskedUrl, e.getMessage());
                        throw new LlmException("Bedrock stream carried a malformed frame", LlmException.ERROR_INVALID_RESPONSE, e);
                    } catch (final IOException e) {
                        logger.warn("[LLM:BEDROCK] Stream interrupted after the response body started. url={}, error={}", maskedUrl,
                                e.getMessage(), e);
                        throw new LlmException("Bedrock stream was interrupted", LlmException.ERROR_CONNECTION, e);
                    }
                    return null;
                }
            }, callback);
        } catch (final LlmException e) {
            callback.onError(e);
            throw e;
        } catch (final IOException | RuntimeException e) {
            // RuntimeException covers a callback that throws (e.g. the client went away) and
            // unexpected runtime errors; the callback is notified before the failure propagates.
            logger.warn("[LLM:BEDROCK] Failed to stream from Bedrock API. url={}, error={}", maskedUrl, e.getMessage(), e);
            final LlmException llmException = new LlmException("Failed to stream from Bedrock API", LlmException.ERROR_CONNECTION, e);
            callback.onError(llmException);
            throw llmException;
        }
    }

    /**
     * Reads a ConverseStream body and forwards its text to {@code callback}. Text deltas become
     * {@code onChunk(text, false)}; reasoning deltas are counted and dropped; {@code messageStop}
     * records the stop reason and {@code metadata} the token usage, reported through
     * {@link LlmStreamCallback#onUsage(LlmUsage)} after the terminal chunk. An {@code exception} or
     * {@code error} frame - which Bedrock can send after HTTP 200 - fails the call, and so does a
     * body that ends without {@code messageStop}. Only after a complete stream is
     * {@code onChunk("", true)} sent.
     *
     * <p>When reading stops early the request is cancelled before the body is closed: closing an
     * unfinished entity makes HttpCore read the rest of it to reuse the connection, which would keep
     * this thread, its concurrency permit and the model busy on an answer nobody reads.
     *
     * @param model the model ID, for log lines.
     * @param httpRequest the request, cancelled when reading stops early.
     * @param body the response body.
     * @param callback the stream callback.
     * @param startTime when the request started, for the timing fields.
     * @throws IOException when reading fails or a frame is corrupt.
     */
    private void consumeStream(final String model, final HttpPost httpRequest, final InputStream body, final LlmStreamCallback callback,
            final long startTime) throws IOException {
        int chunkCount = 0;
        int frameCount = 0;
        int reasoningDeltaCount = 0;
        int parseErrorCount = 0;
        long firstChunkMs = 0L;
        boolean stopReceived = false;
        String stopReason = null;
        Integer inputTokens = null;
        Integer outputTokens = null;
        Integer totalTokens = null;
        try (InputStream in = body) {
            try {
                final EventStreamReader reader = new EventStreamReader(in);
                Message frame;
                while ((frame = reader.next()) != null) {
                    frameCount++;
                    final String messageType = EventStreamReader.header(frame, EventStreamReader.MESSAGE_TYPE);
                    if ("exception".equals(messageType)) {
                        throw streamException(frame, chunkCount, model);
                    }
                    if ("error".equals(messageType)) {
                        final String code = EventStreamReader.header(frame, EventStreamReader.ERROR_CODE);
                        logger.warn("[LLM:BEDROCK] Stream error received. errorCode={}, message={}, chunkCount={}, model={}", code,
                                EventStreamReader.header(frame, EventStreamReader.ERROR_MESSAGE), chunkCount, model);
                        throw new LlmException("Bedrock stream error: " + code, LlmException.ERROR_UNKNOWN);
                    }
                    final String eventType = EventStreamReader.header(frame, EventStreamReader.EVENT_TYPE);
                    final JsonNode payload;
                    try {
                        payload = objectMapper.readTree(frame.getPayload());
                    } catch (final JacksonException e) {
                        parseErrorCount++;
                        logger.warn("[LLM:BEDROCK] Failed to parse a stream event. eventType={}, error={}", eventType,
                                e.getOriginalMessage());
                        continue;
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("[LLM:BEDROCK] streamEvent#{} eventType={} payload={}", frameCount, eventType, payload);
                    }
                    if ("contentBlockDelta".equals(eventType)) {
                        final JsonNode delta = payload.path("delta");
                        final JsonNode text = delta.get("text");
                        if (text != null && text.isString()) {
                            final String chunk = text.asString();
                            if (!chunk.isEmpty()) {
                                callback.onChunk(chunk, false);
                                if (chunkCount == 0) {
                                    firstChunkMs = System.currentTimeMillis() - startTime;
                                }
                                chunkCount++;
                            }
                        } else if (delta.has("reasoningContent")) {
                            reasoningDeltaCount++;
                        }
                    } else if ("messageStop".equals(eventType)) {
                        stopReceived = true;
                        final JsonNode reason = payload.get("stopReason");
                        stopReason = reason != null && reason.isString() ? reason.asString() : null;
                    } else if ("metadata".equals(eventType)) {
                        final JsonNode usage = payload.path("usage");
                        if (usage.has("inputTokens")) {
                            inputTokens = usage.get("inputTokens").asInt();
                        }
                        if (usage.has("outputTokens")) {
                            outputTokens = usage.get("outputTokens").asInt();
                        }
                        if (usage.has("totalTokens")) {
                            totalTokens = usage.get("totalTokens").asInt();
                        }
                    }
                }
            } catch (final IOException | RuntimeException e) {
                httpRequest.cancel();
                throw e;
            }
        }
        logger.info(
                "[LLM:BEDROCK] Stream completed. chunkCount={}, frameCount={}, firstChunkMs={}, elapsedTime={}ms, stopReason={}, "
                        + "inputTokens={}, outputTokens={}, totalTokens={}, reasoningDeltaCount={}, parseErrorCount={}",
                chunkCount, frameCount, firstChunkMs, System.currentTimeMillis() - startTime, stopReason, inputTokens, outputTokens,
                totalTokens, reasoningDeltaCount, parseErrorCount);
        if (!stopReceived) {
            logger.warn("[LLM:BEDROCK] Stream ended without a messageStop event. chunkCount={}, frameCount={}, model={}", chunkCount,
                    frameCount, model);
            throw new LlmException("Bedrock stream ended without a messageStop event", LlmException.ERROR_INVALID_RESPONSE);
        }
        if (isAbnormalFinishReason(stopReason)) {
            logger.warn("[LLM:BEDROCK] Stream finished abnormally. stopReason={}, outputTokens={}, chunkCount={}, model={}", stopReason,
                    outputTokens, chunkCount, model);
        }
        callback.onChunk("", true);
        // The metadata event carries the totals of the whole call; without this the caller would count the
        // call but none of its tokens (the synchronous chat() reports them through its response).
        callback.onUsage(new LlmUsage(inputTokens, outputTokens, totalTokens, model));
    }

    /**
     * Builds the exception for an {@code exception} frame and logs it.
     *
     * @param frame the frame.
     * @param chunkCount chunks delivered before it.
     * @param model the model ID.
     * @return the exception to throw.
     */
    private LlmException streamException(final Message frame, final int chunkCount, final String model) {
        final String exceptionType = EventStreamReader.header(frame, EventStreamReader.EXCEPTION_TYPE);
        final String message = BedrockErrorBody.message(new String(frame.getPayload(), StandardCharsets.UTF_8));
        logger.warn("[LLM:BEDROCK] Stream exception received. exceptionType={}, message={}, chunkCount={}, model={}", exceptionType,
                message, chunkCount, model);
        return new LlmException("Bedrock stream exception: " + exceptionType, streamExceptionErrorCode(exceptionType, message));
    }

    /**
     * Maps a ConverseStream exception frame to an error code: {@code throttlingException} to
     * {@code rate_limit}, {@code serviceUnavailableException} to {@code service_unavailable},
     * {@code validationException} to {@code context_length_exceeded} when its message says the
     * input is too long and to {@code invalid_response} otherwise, anything else to
     * {@code unknown}.
     *
     * @param exceptionType the {@code :exception-type} header.
     * @param message the exception message.
     * @return the error code.
     */
    static String streamExceptionErrorCode(final String exceptionType, final String message) {
        if ("throttlingException".equals(exceptionType)) {
            return LlmException.ERROR_RATE_LIMIT;
        }
        if ("serviceUnavailableException".equals(exceptionType)) {
            return LlmException.ERROR_SERVICE_UNAVAILABLE;
        }
        if ("validationException".equals(exceptionType)) {
            return BedrockErrorBody.indicatesInputTooLong(message) ? LlmException.ERROR_CONTEXT_LENGTH_EXCEEDED
                    : LlmException.ERROR_INVALID_RESPONSE;
        }
        return LlmException.ERROR_UNKNOWN;
    }

    /**
     * Parses a Converse response: the text blocks of {@code output.message.content} concatenated
     * (reasoning blocks are not part of the answer), {@code stopReason}, and {@code usage}.
     *
     * @param responseBody the response body.
     * @param model the model ID the request was sent to.
     * @return the response.
     * @throws LlmException ({@code invalid_response}) when the body is not JSON.
     */
    protected LlmChatResponse parseConverseResponse(final String responseBody, final String model) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (final JacksonException e) {
            throw new LlmException("Bedrock returned a response that is not JSON", LlmException.ERROR_INVALID_RESPONSE, e);
        }
        final LlmChatResponse chatResponse = new LlmChatResponse();
        final JsonNode content = root.path("output").path("message").path("content");
        if (content.isArray()) {
            StringBuilder text = null;
            for (final JsonNode block : content) {
                final JsonNode textNode = block.get("text");
                if (textNode != null && textNode.isString()) {
                    if (text == null) {
                        text = new StringBuilder();
                    }
                    text.append(textNode.asString());
                }
            }
            if (text != null) {
                chatResponse.setContent(text.toString());
            }
        }
        final JsonNode stopReason = root.get("stopReason");
        if (stopReason != null && stopReason.isString()) {
            chatResponse.setFinishReason(stopReason.asString());
        }
        final JsonNode usage = root.path("usage");
        if (usage.has("inputTokens")) {
            chatResponse.setPromptTokens(usage.get("inputTokens").asInt());
        }
        if (usage.has("outputTokens")) {
            chatResponse.setCompletionTokens(usage.get("outputTokens").asInt());
        }
        if (usage.has("totalTokens")) {
            chatResponse.setTotalTokens(usage.get("totalTokens").asInt());
        }
        chatResponse.setModel(model);
        return chatResponse;
    }

    /**
     * Turns a non-2xx response into the exception to throw: a retry signal for a retryable status,
     * otherwise an {@link LlmException} whose code follows the status ({@code 400} reporting an
     * over-long input is {@code context_length_exceeded}). Logs one WARN carrying the rendered
     * error.
     *
     * @param response the response.
     * @param maskedUrl the request URL, safe to log.
     * @param label the WARN label.
     * @return the exception to throw.
     */
    protected RuntimeException toErrorException(final ClassicHttpResponse response, final String maskedUrl, final String label) {
        final int statusCode = response.getCode();
        String errorBody = "";
        if (response.getEntity() != null) {
            try {
                errorBody = new String(EntityUtils.toByteArray(response.getEntity()), StandardCharsets.UTF_8);
            } catch (final IOException e) {
                // Reading the error body must not change the status-based classification below.
            }
        }
        final Header typeHeader = response.getFirstHeader(BedrockErrorBody.ERROR_TYPE_HEADER);
        final String errorType = typeHeader != null ? typeHeader.getValue() : null;
        logger.warn("[LLM:BEDROCK] {}. url={}, statusCode={}, error={}", label, maskedUrl, statusCode,
                BedrockErrorBody.render(errorBody, errorType));
        if (BedrockRetry.isRetryableStatus(statusCode)) {
            final Header retryAfter = response.getFirstHeader("Retry-After");
            return new BedrockRetry.RetryableHttpException(statusCode, response.getReasonPhrase(),
                    BedrockRetry.parseRetryAfterSeconds(retryAfter != null ? retryAfter.getValue() : null));
        }
        final String errorCode = statusCode == 400 && BedrockErrorBody.indicatesInputTooLong(BedrockErrorBody.message(errorBody))
                ? LlmException.ERROR_CONTEXT_LENGTH_EXCEEDED
                : resolveErrorCode(statusCode);
        return new LlmException("Bedrock API error: " + statusCode + " " + response.getReasonPhrase(), errorCode);
    }

    /**
     * Builds the Converse request body: {@code system} from every system message,
     * {@code messages} from the user and assistant messages (see {@link #buildMessages(List)}),
     * {@code inferenceConfig} from the request's max tokens and temperature, and
     * {@code additionalModelRequestFields} from configuration. The model ID is part of the URL, not
     * of the body.
     *
     * @param request the chat request.
     * @return the body.
     */
    protected Map<String, Object> buildRequestBody(final LlmChatRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", buildMessages(request.getMessages()));
        final List<Map<String, String>> system = new ArrayList<>();
        for (final LlmMessage message : request.getMessages()) {
            if (LlmMessage.ROLE_SYSTEM.equals(message.getRole()) && StringUtil.isNotBlank(message.getContent())) {
                system.add(Map.of("text", message.getContent()));
            }
        }
        if (!system.isEmpty()) {
            body.put("system", system);
        }
        final Map<String, Object> inferenceConfig = new LinkedHashMap<>();
        if (request.getMaxTokens() != null) {
            inferenceConfig.put("maxTokens", request.getMaxTokens());
        }
        if (request.getTemperature() != null && isTemperatureEnabled()) {
            inferenceConfig.put("temperature", request.getTemperature());
        }
        if (!inferenceConfig.isEmpty()) {
            body.put("inferenceConfig", inferenceConfig);
        }
        final JsonNode additionalFields = resolveAdditionalModelRequestFields(request);
        if (additionalFields != null) {
            body.put("additionalModelRequestFields", additionalFields);
        }
        if (request.getThinkingBudget() != null) {
            logger.warn("[LLM:BEDROCK] thinking.budget is not supported by Bedrock and was not sent. thinkingBudget={}. "
                    + "Remove {}.<promptType>.thinking.budget and configure the model's reasoning with " + "{}.<promptType>.{} instead.",
                    request.getThinkingBudget(), getConfigPrefix(), getConfigPrefix(), CONFIG_ADDITIONAL_FIELDS);
        }
        return body;
    }

    /**
     * Converts the user and assistant messages to Converse {@code messages}. Converse rejects a
     * conversation that does not start with a user message, that has two consecutive messages of
     * the same role, or that has a blank text block, and Fess history can produce all three (the
     * intent prompt keeps the last N history messages, which can start on an assistant turn). So:
     * blank messages are skipped, leading assistant messages are dropped, and consecutive messages
     * of one role are merged into one message with several text blocks. A role other than
     * {@code assistant} is sent as {@code user}.
     *
     * @param messages the request messages, system messages included (they are skipped here).
     * @return the Converse messages.
     */
    protected List<Map<String, Object>> buildMessages(final List<LlmMessage> messages) {
        final List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, String>> lastContent = null;
        String lastRole = null;
        int skippedBlank = 0;
        int droppedLeading = 0;
        for (final LlmMessage message : messages) {
            if (LlmMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            if (StringUtil.isBlank(message.getContent())) {
                skippedBlank++;
                continue;
            }
            final String role = LlmMessage.ROLE_ASSISTANT.equals(message.getRole()) ? ROLE_ASSISTANT : ROLE_USER;
            if (result.isEmpty() && ROLE_ASSISTANT.equals(role)) {
                droppedLeading++;
                continue;
            }
            if (role.equals(lastRole)) {
                lastContent.add(Map.of("text", message.getContent()));
                continue;
            }
            lastContent = new ArrayList<>();
            lastContent.add(Map.of("text", message.getContent()));
            final Map<String, Object> converseMessage = new LinkedHashMap<>();
            converseMessage.put("role", role);
            converseMessage.put("content", lastContent);
            result.add(converseMessage);
            lastRole = role;
        }
        if ((skippedBlank > 0 || droppedLeading > 0) && logger.isDebugEnabled()) {
            logger.debug("[LLM:BEDROCK] Normalized messages for Converse. skippedBlank={}, droppedLeadingAssistant={}", skippedBlank,
                    droppedLeading);
        }
        return result;
    }

    /**
     * Resolves {@code additionalModelRequestFields}: the per-prompt-type value placed by
     * {@link #applyPromptTypeParams(LlmChatRequest, String)} when there is one, otherwise
     * {@code rag.llm.bedrock.additional.model.request.fields}. A value that is not a JSON object is
     * reported at WARN, naming its key, every time it is read, and is not sent.
     *
     * @param request the chat request.
     * @return the JSON object, or {@code null} when there is nothing valid to send.
     */
    protected JsonNode resolveAdditionalModelRequestFields(final LlmChatRequest request) {
        final String perType = request.getExtraParam(EXTRA_ADDITIONAL_FIELDS);
        final String raw;
        final String key;
        if (perType != null) {
            raw = perType;
            key = request.getExtraParam(EXTRA_ADDITIONAL_FIELDS_KEY);
        } else {
            raw = getConfigString(CONFIG_ADDITIONAL_FIELDS, "");
            key = getConfigPrefix() + "." + CONFIG_ADDITIONAL_FIELDS;
        }
        if (StringUtil.isBlank(raw)) {
            return null;
        }
        try {
            final JsonNode node = objectMapper.readTree(raw);
            if (node != null && node.isObject()) {
                return node;
            }
            logger.warn("[LLM:BEDROCK] {} is not a JSON object and was not sent.", key);
        } catch (final JacksonException e) {
            logger.warn("[LLM:BEDROCK] {} is not valid JSON and was not sent. error={}", key, e.getOriginalMessage());
        }
        return null;
    }

    @Override
    protected void applyPromptTypeParams(final LlmChatRequest request, final String promptType) {
        super.applyPromptTypeParams(request, promptType);
        final String suffix = promptType + "." + CONFIG_ADDITIONAL_FIELDS;
        final String perType = getConfigString(suffix, null);
        if (StringUtil.isNotBlank(perType)) {
            request.putExtraParam(EXTRA_ADDITIONAL_FIELDS, perType);
            request.putExtraParam(EXTRA_ADDITIONAL_FIELDS_KEY, getConfigPrefix() + "." + suffix);
        }
        applyDefaultParams(request, promptType);
    }

    /**
     * Applies the per-prompt-type defaults (the same table as fess-llm-openai) where the request
     * has no value yet. No default temperature is applied when {@link #isTemperatureEnabled()} is
     * false.
     *
     * @param request the chat request.
     * @param promptType the prompt type.
     */
    protected void applyDefaultParams(final LlmChatRequest request, final String promptType) {
        final double temperature;
        final int maxTokens;
        switch (promptType) {
        case "intent":
        case "evaluation":
            temperature = 0.1;
            maxTokens = 256;
            break;
        case "unclear":
        case "noresults":
            temperature = 0.7;
            maxTokens = 512;
            break;
        case "docnotfound":
            temperature = 0.7;
            maxTokens = 256;
            break;
        case "direct":
        case "faq":
            temperature = 0.7;
            maxTokens = 1024;
            break;
        case "answer":
            temperature = 0.5;
            maxTokens = 2048;
            break;
        case "summary":
            temperature = 0.3;
            maxTokens = 2048;
            break;
        case "queryregeneration":
            temperature = 0.3;
            maxTokens = 256;
            break;
        default:
            return;
        }
        if (request.getTemperature() == null && isTemperatureEnabled()) {
            request.setTemperature(temperature);
        }
        if (request.getMaxTokens() == null) {
            request.setMaxTokens(maxTokens);
        }
    }

    /**
     * Executes {@code call}, retrying a retryable HTTP status and an I/O failure that happened
     * before the request reached Bedrock (see {@link BedrockRetry#isConnectFailure(IOException)}).
     * Any other {@link IOException} and every {@link RuntimeException} propagate at once. A
     * {@code Retry-After} hint overrides the exponential backoff.
     *
     * @param operation log label.
     * @param call one attempt.
     * @param callback notified between attempts, may be {@code null}.
     * @param <T> the result type.
     * @return the result.
     * @throws IOException the last I/O failure.
     */
    <T> T executeWithRetry(final String operation, final BedrockRetry.HttpCall<T> call, final LlmStreamCallback callback)
            throws IOException {
        final int maxAttempts = Math.max(1, getRetryMaxAttempts());
        final long baseDelay = Math.max(0L, getRetryBaseDelayMs());
        for (int attempt = 1;; attempt++) {
            try {
                return call.call();
            } catch (final BedrockRetry.RetryableHttpException e) {
                if (attempt >= maxAttempts) {
                    logger.warn("[LLM:BEDROCK] {} retry exhausted. attempts={}, lastStatus={}, retryAfter={}s", operation, attempt,
                            e.statusCode, e.retryAfterSeconds);
                    throw new LlmException("Bedrock API retryable error: " + e.statusCode + " " + e.reason,
                            exhaustedErrorCode(e.statusCode), e);
                }
                sleepBackoff(operation, attempt, maxAttempts,
                        BedrockRetry.computeBackoffMs(attempt, baseDelay, Long.MAX_VALUE, e.retryAfterSeconds), "status=" + e.statusCode, e,
                        callback);
            } catch (final IOException e) {
                if (attempt >= maxAttempts || !BedrockRetry.isConnectFailure(e)) {
                    throw e;
                }
                sleepBackoff(operation, attempt, maxAttempts, BedrockRetry.computeBackoffMs(attempt, baseDelay, Long.MAX_VALUE, -1L),
                        "exception=" + e.getClass().getSimpleName(), e, callback);
            }
        }
    }

    /**
     * The error code reported when the retry budget runs out on a retryable status.
     *
     * @param statusCode the last status.
     * @return {@code rate_limit} for 429, otherwise {@code service_unavailable}.
     */
    static String exhaustedErrorCode(final int statusCode) {
        return statusCode == 429 ? LlmException.ERROR_RATE_LIMIT : LlmException.ERROR_SERVICE_UNAVAILABLE;
    }

    private void sleepBackoff(final String operation, final int attempt, final int maxAttempts, final long sleepMs, final String cause,
            final Throwable error, final LlmStreamCallback callback) throws IOException {
        logger.info("[LLM:BEDROCK] {} retrying. attempt={}/{}, {}, sleepMs={}", operation, attempt, maxAttempts, cause, sleepMs);
        if (callback != null) {
            try {
                callback.onRetry(operation, attempt, maxAttempts, sleepMs, error);
            } catch (final Exception cbEx) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[LLM:BEDROCK] onRetry callback threw. error={}", cbEx.getMessage());
                }
            }
        }
        try {
            Thread.sleep(sleepMs);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry interrupted", ie);
        }
    }
}
