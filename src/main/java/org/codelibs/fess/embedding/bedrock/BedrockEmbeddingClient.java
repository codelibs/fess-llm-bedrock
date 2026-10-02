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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
import org.codelibs.fess.embedding.AbstractEmbeddingClient;
import org.codelibs.fess.embedding.EmbeddingException;
import org.codelibs.fess.util.CredentialUrlUtil;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Embedding client for Amazon Bedrock, through the InvokeModel API of Bedrock Runtime.
 *
 * <p>Supports Amazon Titan Text Embeddings V2 (one text per request, {@code dimensions} 256, 512 or
 * 1024) and Cohere Embed v3 (English and multilingual, 1024 dimensions) and v4 ({@code output_dimension}
 * 256, 512, 1024 or 1536), the Cohere models in batches of up to {@value #COHERE_MAX_BATCH} texts. The
 * family is recognized from the base model name inside the model ID (see {@link #detectFamily(String)}), so
 * cross-region inference profile IDs ({@code us.}, {@code global.}) and ARNs that contain the model ID work. Configuration is read from {@code conf/system.properties} under
 * {@value #CONFIG_PREFIX}; the vector dimension is {@code content_chunker.embedding.dimension}.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModel.html">InvokeModel</a>
 */
public class BedrockEmbeddingClient extends AbstractEmbeddingClient {

    private static final Logger logger = LogManager.getLogger(BedrockEmbeddingClient.class);

    /** Shared ObjectMapper instance for JSON processing. */
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /** The name identifier of this client, matched against {@code content_chunker.embedding.name}. */
    protected static final String NAME = "bedrock";

    /** The configuration key prefix. */
    protected static final String CONFIG_PREFIX = "content_chunker.embedding.bedrock";

    /** The model used when none is configured. */
    protected static final String DEFAULT_MODEL = "amazon.titan-embed-text-v2:0";

    /** Cohere Embed's maximum number of texts per request. */
    static final int COHERE_MAX_BATCH = 96;

    /** Default cap on one backoff sleep. */
    static final long DEFAULT_MAX_BACKOFF_MS = 60_000L;

    private static final Set<Integer> TITAN_DIMENSIONS = Set.of(256, 512, 1024);

    private static final Set<Integer> COHERE_V4_DIMENSIONS = Set.of(256, 512, 1024, 1536);

    private static final int COHERE_V3_DIMENSION = 1024;

    // Query-syntax patterns, identical to fess-llm-openai / fess-llm-gemini / fess-llm-ollama.
    private static final Pattern QUERY_TERM_PREFIX = Pattern.compile("(^|\\s)[+\\-](?=\\S)");
    private static final Pattern QUERY_FIELD_PREFIX = Pattern.compile("\\b\\w+:");
    private static final Pattern QUERY_BOOST_OR_FUZZY = Pattern.compile("[\\^~]\\d*(?:\\.\\d+)?");
    private static final Pattern QUERY_SYNTAX_CHARS = Pattern.compile("[\"()\\[\\]{}*?\\\\]|&&|\\|\\|");
    private static final Pattern QUERY_KEYWORDS = Pattern.compile("\\b(?:AND|OR|NOT|TO)\\b");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** The embedding model families this client can call. */
    enum ModelFamily {
        /** Amazon Titan Text Embeddings V2. */
        TITAN_V2,
        /** Cohere Embed English / Multilingual v3. */
        COHERE_V3,
        /** Cohere Embed v4. */
        COHERE_V4,
        /** Anything else. */
        UNSUPPORTED
    }

    private BedrockAuth auth;

    /**
     * Default constructor.
     */
    public BedrockEmbeddingClient() {
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

    @Override
    protected int getTimeout() {
        return getConfigInt("timeout", 120000);
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
     * Gets the endpoint override.
     *
     * @return the endpoint, or {@code ""} to derive it from the region.
     */
    protected String getEndpoint() {
        return getConfigString("endpoint", "");
    }

    /**
     * Gets the embedding model ID.
     *
     * @return the model ID, trimmed (default {@value #DEFAULT_MODEL}).
     */
    protected String getModel() {
        final String model = getConfigString("model", DEFAULT_MODEL);
        return model == null ? null : model.trim();
    }

    /**
     * Whether Titan normalizes its vectors.
     *
     * @return {@code content_chunker.embedding.bedrock.normalize} (default true).
     */
    protected boolean isNormalize() {
        final String value = getConfigString("normalize", "true");
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * Gets the Cohere {@code truncate} value.
     *
     * @return the value, or {@code ""} to leave it to the model's default.
     */
    protected String getTruncate() {
        final String value = getConfigString("truncate", "");
        return value == null ? "" : value.trim();
    }

    /**
     * Maximum attempts (initial call plus retries) for one HTTP call.
     *
     * @return {@code content_chunker.embedding.bedrock.retry.max} (default 10).
     */
    protected int getRetryMaxAttempts() {
        return getConfigInt("retry.max", 10);
    }

    /**
     * Base delay of the exponential backoff.
     *
     * @return {@code content_chunker.embedding.bedrock.retry.base.delay.ms} (default 2000).
     */
    protected long getRetryBaseDelayMs() {
        return getConfigLong("retry.base.delay.ms", 2000L);
    }

    /**
     * Cap on one backoff sleep, {@code Retry-After} included, so a throttled endpoint cannot stall
     * the sequential chunk job indefinitely.
     *
     * @return {@code content_chunker.embedding.bedrock.retry.max.delay.ms} (default 60000).
     */
    protected long getRetryMaxDelayMs() {
        final long value = getConfigLong("retry.max.delay.ms", DEFAULT_MAX_BACKOFF_MS);
        if (value > 0L) {
            return value;
        }
        logger.warn("[Embedding:BEDROCK] {}.retry.max.delay.ms must be positive: {}. Using default {}.", getConfigPrefix(), value,
                DEFAULT_MAX_BACKOFF_MS);
        return DEFAULT_MAX_BACKOFF_MS;
    }

    /**
     * Recognizes the model family by the base model name inside the configured ID: a base model ID
     * ({@code cohere.embed-v4:0}), a cross-region inference profile ID ({@code global.cohere.embed-v4:0})
     * or an ARN that contains one of those. An application inference profile, whose ID or ARN is
     * opaque, cannot be recognized and is unsupported.
     *
     * @param model the configured model.
     * @return the family.
     */
    static ModelFamily detectFamily(final String model) {
        if (StringUtil.isBlank(model)) {
            return ModelFamily.UNSUPPORTED;
        }
        final String lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("amazon.titan-embed-text-v2")) {
            return ModelFamily.TITAN_V2;
        }
        if (lower.contains("cohere.embed-v4")) {
            return ModelFamily.COHERE_V4;
        }
        if (lower.contains("cohere.embed-english-v3") || lower.contains("cohere.embed-multilingual-v3")) {
            return ModelFamily.COHERE_V3;
        }
        return ModelFamily.UNSUPPORTED;
    }

    /**
     * Returns why the configuration cannot work, or {@code null} when it can: a blank or
     * unsupported model, a region that is not an AWS region name, a dimension the model does not
     * produce, or an unusable endpoint. Nothing in the message comes from the endpoint URL.
     *
     * @return the problem, or {@code null}.
     */
    protected String validateConfiguration() {
        final String model = getModel();
        final ModelFamily family = detectFamily(model);
        if (family == ModelFamily.UNSUPPORTED) {
            return getConfigPrefix() + ".model is not a supported embedding model: '" + model
                    + "'. Use amazon.titan-embed-text-v2:0, cohere.embed-english-v3, cohere.embed-multilingual-v3 or cohere.embed-v4:0.";
        }
        final String region = getRegion();
        if (!BedrockEndpoint.isValidRegion(region)) {
            return getConfigPrefix() + ".region is not an AWS region name: '" + region + "'";
        }
        final int dimension;
        try {
            dimension = getDimension();
        } catch (final EmbeddingException e) {
            return e.getMessage();
        }
        if (family == ModelFamily.TITAN_V2 && !TITAN_DIMENSIONS.contains(dimension)) {
            return EMBEDDING_DIMENSION_PROPERTY + "=" + dimension + " is not supported by " + model + ". Use 256, 512 or 1024.";
        }
        if (family == ModelFamily.COHERE_V3 && dimension != COHERE_V3_DIMENSION) {
            return EMBEDDING_DIMENSION_PROPERTY + "=" + dimension + " is not supported by " + model + ". Cohere Embed v3 produces 1024.";
        }
        if (family == ModelFamily.COHERE_V4 && !COHERE_V4_DIMENSIONS.contains(dimension)) {
            return EMBEDDING_DIMENSION_PROPERTY + "=" + dimension + " is not supported by " + model + ". Use 256, 512, 1024 or 1536.";
        }
        try {
            BedrockEndpoint.toUri(BedrockEndpoint.modelUrl(BedrockEndpoint.resolveBaseUrl(getEndpoint(), region), model, "invoke"),
                    endpointConfigKey());
        } catch (final IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    /** The configuration key named when the endpoint is refused or malformed. */
    private String endpointConfigKey() {
        return getConfigPrefix() + ".endpoint";
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
    public synchronized void destroy() {
        super.destroy();
        if (auth != null) {
            auth.close();
            auth = null;
        }
    }

    /**
     * Reports whether Bedrock can be called, without calling it. A configuration that can never
     * work is reported at ERROR every time it is checked.
     */
    @Override
    protected boolean checkAvailabilityNow() {
        final String problem = validateConfiguration();
        if (problem != null) {
            logger.error("[Embedding:BEDROCK] Bedrock is not available. {}", problem);
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
            logger.debug("[Embedding:BEDROCK] Bedrock is not available. No api.key and AWS credentials could not be resolved. error={}",
                    credentialsProblem);
        }
        return false;
    }

    @Override
    public List<float[]> embedDocuments(final List<String> texts) {
        return embed(texts, "search_document");
    }

    @Override
    public List<float[]> embedQuery(final List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> plainTexts = new ArrayList<>(texts.size());
        for (final String text : texts) {
            final String plain = toPlainQuery(text);
            if (logger.isDebugEnabled() && plain != null && !plain.equals(text)) {
                logger.debug("[Embedding:BEDROCK] Removed query syntax before embedding. from={}, to={}", text, plain);
            }
            plainTexts.add(plain);
        }
        return embed(plainTexts, "search_query");
    }

    /**
     * Removes Fess/Lucene query syntax so what gets embedded is the terms the user asked about.
     * Copied from fess-llm-openai's {@code OpenAiEmbeddingClient#toPlainQuery}; keep the plugins in
     * step. A string that survives unchanged is returned as-is, and one left empty falls back to the
     * original.
     *
     * @param text the query text, may be null
     * @return the text with query syntax removed, or the original text
     */
    protected String toPlainQuery(final String text) {
        if (StringUtil.isBlank(text)) {
            return text;
        }
        String work = QUERY_TERM_PREFIX.matcher(text).replaceAll("$1");
        work = QUERY_FIELD_PREFIX.matcher(work).replaceAll(StringUtil.EMPTY);
        work = QUERY_BOOST_OR_FUZZY.matcher(work).replaceAll(StringUtil.EMPTY);
        work = QUERY_SYNTAX_CHARS.matcher(work).replaceAll(" ");
        work = QUERY_KEYWORDS.matcher(work).replaceAll(StringUtil.EMPTY);
        if (work.equals(text)) {
            return text;
        }
        final String plain = WHITESPACE_RUN.matcher(work).replaceAll(" ").trim();
        return plain.isEmpty() ? text : plain;
    }

    /**
     * Embeds {@code texts} in order: one request per text for Titan, batches of
     * {@value #COHERE_MAX_BATCH} for Cohere. All or nothing: any failed request fails the call.
     *
     * @param texts the texts.
     * @param inputType the Cohere {@code input_type}.
     * @return one vector per text.
     */
    private List<float[]> embed(final List<String> texts, final String inputType) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        final String problem = validateConfiguration();
        if (problem != null) {
            throw new EmbeddingException(problem);
        }
        final String model = getModel();
        final ModelFamily family = detectFamily(model);
        final int dimension = getDimension();
        final String baseUrl = BedrockEndpoint.resolveBaseUrl(getEndpoint(), getRegion());
        final String url = BedrockEndpoint.modelUrl(baseUrl, model, "invoke");
        final long startTime = System.currentTimeMillis();
        final List<float[]> vectors = new ArrayList<>(texts.size());
        int requests = 0;
        if (family == ModelFamily.TITAN_V2) {
            for (final String text : texts) {
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("inputText", text);
                body.put("dimensions", dimension);
                body.put("normalize", isNormalize());
                vectors.add(parseTitanResponse(invoke(url, body), dimension));
                requests++;
            }
        } else {
            for (int from = 0; from < texts.size(); from += COHERE_MAX_BATCH) {
                final List<String> batch = texts.subList(from, Math.min(texts.size(), from + COHERE_MAX_BATCH));
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("texts", batch);
                body.put("input_type", inputType);
                if (family == ModelFamily.COHERE_V4) {
                    body.put("output_dimension", dimension);
                }
                final String truncate = getTruncate();
                if (!truncate.isEmpty()) {
                    body.put("truncate", truncate);
                }
                vectors.addAll(parseCohereResponse(invoke(url, body), batch.size(), dimension));
                requests++;
            }
        }
        logger.info("[Embedding:BEDROCK] Embed completed. model={}, inputType={}, count={}, requests={}, elapsedTime={}ms", model,
                inputType, vectors.size(), requests, System.currentTimeMillis() - startTime);
        return vectors;
    }

    /**
     * Parses a Titan response, {@code {"embedding": [...], "inputTextTokenCount": n}}.
     *
     * @param responseBody the body.
     * @param dimension the expected vector length.
     * @return the vector.
     */
    protected float[] parseTitanResponse(final String responseBody, final int dimension) {
        final JsonNode root = readJson(responseBody);
        return toVector(root.get("embedding"), dimension, 0);
    }

    /**
     * Parses a Cohere response, whose {@code embeddings} is either an array of vectors
     * ({@code embeddings_floats}) or an object whose {@code float} member is that array
     * ({@code embeddings_by_type}).
     *
     * @param responseBody the body.
     * @param expectedCount the number of texts sent.
     * @param dimension the expected vector length.
     * @return the vectors, in request order.
     */
    protected List<float[]> parseCohereResponse(final String responseBody, final int expectedCount, final int dimension) {
        final JsonNode root = readJson(responseBody);
        JsonNode embeddings = root.get("embeddings");
        if (embeddings != null && embeddings.isObject()) {
            embeddings = embeddings.get("float");
        }
        if (embeddings == null || !embeddings.isArray()) {
            throw new EmbeddingException("Bedrock Cohere embed response has no float embeddings");
        }
        if (embeddings.size() != expectedCount) {
            throw new EmbeddingException(
                    "Bedrock Cohere embed response count mismatch: expected=" + expectedCount + ", actual=" + embeddings.size());
        }
        final List<float[]> vectors = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            vectors.add(toVector(embeddings.get(i), dimension, i));
        }
        return vectors;
    }

    private static JsonNode readJson(final String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (final JacksonException e) {
            throw new EmbeddingException("Bedrock embed response is not JSON", e);
        }
    }

    private static float[] toVector(final JsonNode node, final int dimension, final int index) {
        if (node == null || !node.isArray() || node.size() != dimension) {
            throw new EmbeddingException("Bedrock embed vector dimension mismatch: index=" + index + ", expected=" + dimension + ", actual="
                    + (node != null && node.isArray() ? node.size() : -1));
        }
        final float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            final JsonNode component = node.get(i);
            if (component == null || !component.isNumber()) {
                throw new EmbeddingException("Bedrock embed vector component is not numeric: index=" + index + ", position=" + i);
            }
            final float value = (float) component.asDouble();
            if (!Float.isFinite(value)) {
                throw new EmbeddingException("Bedrock embed vector component is not finite: index=" + index + ", position=" + i);
            }
            vector[i] = value;
        }
        return vector;
    }

    /**
     * Sends one InvokeModel request with retry and returns the response body.
     *
     * @param url the request URL.
     * @param requestBody the body.
     * @return the response body.
     */
    private String invoke(final String url, final Map<String, Object> requestBody) {
        final String maskedUrl = CredentialUrlUtil.maskCredentialInUrl(url);
        try {
            final byte[] json = objectMapper.writeValueAsBytes(requestBody);
            return executeWithRetry("embed", () -> {
                final HttpPost httpRequest = createSignedPost(url, json);
                try (var response = getHttpClient().execute(httpRequest)) {
                    final int statusCode = response.getCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        throw toErrorException(response, maskedUrl);
                    }
                    return response.getEntity() != null ? new String(EntityUtils.toByteArray(response.getEntity()), StandardCharsets.UTF_8)
                            : "";
                }
            });
        } catch (final EmbeddingException e) {
            throw e;
        } catch (final Exception e) {
            logger.warn("[Embedding:BEDROCK] Failed to call Bedrock embed API. url={}, error={}", maskedUrl, e.getMessage(), e);
            throw new EmbeddingException("Failed to call Bedrock embed API", e);
        }
    }

    /**
     * Builds an authorized POST carrying {@code body}, anew for every attempt.
     *
     * @param url the request URL.
     * @param body the JSON body.
     * @return the request.
     */
    protected HttpPost createSignedPost(final String url, final byte[] body) {
        final URI uri = BedrockEndpoint.toUri(url, endpointConfigKey());
        final HttpPost post = new HttpPost(uri);
        post.setHeader("Accept", "application/json");
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        try {
            getAuth().authorize(post, uri, body, getApiKey(), getRegion());
        } catch (final RuntimeException e) {
            logger.warn("[Embedding:BEDROCK] AWS credentials could not be resolved. Set {}.api.key, or provide AWS credentials "
                    + "(environment variables, profile, or an instance/task role). error={}", getConfigPrefix(), e.getMessage());
            throw new EmbeddingException("AWS credentials could not be resolved for Bedrock", e);
        }
        return post;
    }

    private RuntimeException toErrorException(final ClassicHttpResponse response, final String maskedUrl) {
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
        final String rendered = BedrockErrorBody.render(errorBody, typeHeader != null ? typeHeader.getValue() : null);
        logger.warn("[Embedding:BEDROCK] API error. url={}, statusCode={}, error={}", maskedUrl, statusCode, rendered);
        if (BedrockRetry.isRetryableStatus(statusCode)) {
            final Header retryAfter = response.getFirstHeader("Retry-After");
            return new BedrockRetry.RetryableHttpException(statusCode, response.getReasonPhrase(),
                    BedrockRetry.parseRetryAfterSeconds(retryAfter != null ? retryAfter.getValue() : null));
        }
        return new EmbeddingException("Bedrock API error: " + statusCode + " " + response.getReasonPhrase() + " (" + rendered + ")");
    }

    /**
     * Executes {@code call}, retrying a retryable status and a connect-time I/O failure, each sleep
     * capped at {@link #getRetryMaxDelayMs()}.
     *
     * @param operation log label.
     * @param call one attempt.
     * @param <T> the result type.
     * @return the result.
     * @throws IOException the last I/O failure.
     */
    <T> T executeWithRetry(final String operation, final BedrockRetry.HttpCall<T> call) throws IOException {
        final int maxAttempts = Math.max(1, getRetryMaxAttempts());
        final long baseDelay = Math.max(0L, getRetryBaseDelayMs());
        final long maxDelay = getRetryMaxDelayMs();
        for (int attempt = 1;; attempt++) {
            try {
                return call.call();
            } catch (final BedrockRetry.RetryableHttpException e) {
                if (attempt >= maxAttempts) {
                    logger.warn("[Embedding:BEDROCK] {} retry exhausted. attempts={}, lastStatus={}, retryAfter={}s", operation, attempt,
                            e.statusCode, e.retryAfterSeconds);
                    throw new EmbeddingException("Bedrock API retryable error: " + e.statusCode + " " + e.reason, e);
                }
                sleep(operation, attempt, maxAttempts, BedrockRetry.computeBackoffMs(attempt, baseDelay, maxDelay, e.retryAfterSeconds),
                        "status=" + e.statusCode);
            } catch (final IOException e) {
                if (attempt >= maxAttempts || !BedrockRetry.isConnectFailure(e)) {
                    throw e;
                }
                sleep(operation, attempt, maxAttempts, BedrockRetry.computeBackoffMs(attempt, baseDelay, maxDelay, -1L),
                        "exception=" + e.getClass().getSimpleName());
            }
        }
    }

    private void sleep(final String operation, final int attempt, final int maxAttempts, final long sleepMs, final String cause)
            throws IOException {
        logger.info("[Embedding:BEDROCK] {} retrying. attempt={}/{}, {}, sleepMs={}", operation, attempt, maxAttempts, cause, sleepMs);
        try {
            Thread.sleep(sleepMs);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry interrupted", ie);
        }
    }
}
