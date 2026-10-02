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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.codelibs.fess.bedrock.BedrockAuth;
import org.codelibs.fess.embedding.EmbeddingException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * {@link BedrockEmbeddingClient} with every {@code content_chunker.embedding.bedrock.*} read and the
 * vector dimension served from memory, and AWS credentials from a fixed provider. The real
 * {@code conf/system.properties} channel is pinned by {@code BedrockEmbeddingClientConfigChannelTest}.
 */
class TestableBedrockEmbeddingClient extends BedrockEmbeddingClient {

    /** The AWS documentation example key pair; never a real credential. */
    static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    static final String API_KEY = "test-bedrock-api-key";

    private final Map<String, String> config = new HashMap<>();

    private Integer dimension = 1024;

    private AwsCredentialsProvider credentialsProvider =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));

    TestableBedrockEmbeddingClient set(final String keySuffix, final String value) {
        config.put(keySuffix, value);
        return this;
    }

    TestableBedrockEmbeddingClient dimension(final Integer value) {
        dimension = value;
        return this;
    }

    TestableBedrockEmbeddingClient credentials(final AwsCredentialsProvider provider) {
        credentialsProvider = provider;
        return this;
    }

    TestableBedrockEmbeddingClient unresolvableCredentials() {
        credentialsProvider = new AwsCredentialsProvider() {
            @Override
            public AwsCredentials resolveCredentials() {
                throw new IllegalStateException("Unable to load credentials from any of the providers in the chain");
            }
        };
        return this;
    }

    /** Points the client at a mock server with no retry sleep and builds its HTTP client. */
    TestableBedrockEmbeddingClient endpoint(final String endpoint) {
        set("endpoint", endpoint);
        set("retry.base.delay.ms", "0");
        init();
        return this;
    }

    @Override
    protected String getConfigString(final String keySuffix, final String defaultValue) {
        return config.containsKey(keySuffix) ? config.get(keySuffix) : defaultValue;
    }

    @Override
    protected int getConfigInt(final String keySuffix, final int defaultValue) {
        final String value = config.get(keySuffix);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    @Override
    protected long getConfigLong(final String keySuffix, final long defaultValue) {
        final String value = config.get(keySuffix);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    @Override
    public int getDimension() {
        if (dimension == null) {
            throw new EmbeddingException(EMBEDDING_DIMENSION_PROPERTY + " is not configured");
        }
        return dimension;
    }

    @Override
    protected BedrockAuth createAuth() {
        final AwsCredentialsProvider provider = credentialsProvider;
        return new BedrockAuth(() -> provider, Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
    }

    @Override
    protected String getEmbeddingType() {
        return NAME;
    }

    @Override
    protected boolean isContentChunkerEnabled() {
        return false;
    }

    boolean testCheckAvailabilityNow() {
        return checkAvailabilityNow();
    }
}
