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

import org.codelibs.fess.unit.UnitFessTestCase;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;

/**
 * Pins that every {@code content_chunker.embedding.bedrock.*} read goes through
 * {@code getSystemProperty} ({@code conf/system.properties} / {@code -Dfess.system.*}), the channel of
 * every {@code content_chunker.*} key, and not {@code fess_config.properties}. Each test uses a plain
 * {@code new BedrockEmbeddingClient()} and plants a value in the real {@code systemProperties}
 * component.
 *
 * <p>{@link #isUseOneTimeContainer()} is true because {@code systemProperties} is otherwise shared
 * across test classes.
 */
public class BedrockEmbeddingClientConfigChannelTest extends UnitFessTestCase {

    @Override
    protected boolean isUseOneTimeContainer() {
        return true;
    }

    private static void withSystemProperty(final String key, final String value, final Runnable assertion) {
        ComponentUtil.getSystemProperties().setProperty(key, value);
        try {
            assertion.run();
        } finally {
            ComponentUtil.getSystemProperties().remove(key);
        }
    }

    @Test
    public void test_stringKeysReadFromSystemProperties() {
        withSystemProperty("content_chunker.embedding.bedrock.api.key", "configured-api-key",
                () -> assertEquals("configured-api-key", new BedrockEmbeddingClient().getApiKey()));
        withSystemProperty("content_chunker.embedding.bedrock.region", "eu-central-1",
                () -> assertEquals("eu-central-1", new BedrockEmbeddingClient().getRegion()));
        withSystemProperty("content_chunker.embedding.bedrock.endpoint", "https://bedrock.example.com",
                () -> assertEquals("https://bedrock.example.com", new BedrockEmbeddingClient().getEndpoint()));
        withSystemProperty("content_chunker.embedding.bedrock.model", "cohere.embed-v4:0",
                () -> assertEquals("cohere.embed-v4:0", new BedrockEmbeddingClient().getModel()));
        withSystemProperty("content_chunker.embedding.bedrock.truncate", "RIGHT",
                () -> assertEquals("RIGHT", new BedrockEmbeddingClient().getTruncate()));
        withSystemProperty("content_chunker.embedding.bedrock.normalize", "false",
                () -> assertFalse(new BedrockEmbeddingClient().isNormalize()));
    }

    @Test
    public void test_numericKeysReadFromSystemProperties() {
        withSystemProperty("content_chunker.embedding.bedrock.retry.base.delay.ms", "12345",
                () -> assertEquals(12345L, new BedrockEmbeddingClient().getRetryBaseDelayMs()));
        withSystemProperty("content_chunker.embedding.bedrock.retry.max.delay.ms", "4321",
                () -> assertEquals(4321L, new BedrockEmbeddingClient().getRetryMaxDelayMs()));
        withSystemProperty("content_chunker.embedding.bedrock.retry.max", "3",
                () -> assertEquals(3, new BedrockEmbeddingClient().getRetryMaxAttempts()));
        withSystemProperty("content_chunker.embedding.bedrock.timeout", "9999",
                () -> assertEquals(9999, new BedrockEmbeddingClient().getTimeout()));
        withSystemProperty("content_chunker.embedding.dimension", "512",
                () -> assertEquals(512, new BedrockEmbeddingClient().getDimension()));
    }

    @Test
    public void test_fessConfigChannelIsNotRead() {
        System.setProperty("fess.config.content_chunker.embedding.bedrock.model", "wrong-channel-model");
        try {
            assertEquals("amazon.titan-embed-text-v2:0", new BedrockEmbeddingClient().getModel());
        } finally {
            System.clearProperty("fess.config.content_chunker.embedding.bedrock.model");
        }
    }
}
