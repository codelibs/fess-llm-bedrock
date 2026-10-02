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

import org.codelibs.fess.unit.UnitFessTestCase;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;

/**
 * Pins the configuration channel of every {@code rag.llm.bedrock.*} read: {@code getOrDefault},
 * i.e. {@code fess_config.properties} plus {@code -Dfess.config.*}. {@code BedrockLlmClientTest}
 * replaces {@code getConfigString} with a map, so nothing there notices a move to
 * {@code conf/system.properties}.
 *
 * <p>{@link #isUseOneTimeContainer()} is true because a value read through the real FessConfig is
 * memoized for the lifetime of the container; without it a planted value would leak into whatever
 * test class runs next.
 */
public class BedrockLlmClientConfigChannelTest extends UnitFessTestCase {

    private static final String SENTINEL = "__supplied_default_was_used__";

    @Override
    protected boolean isUseOneTimeContainer() {
        return true;
    }

    @Test
    public void test_getConfigString_composesTheKeyAndReadsFessConfigSystemProperty() {
        final BedrockLlmClient client = new BedrockLlmClient();
        assertEquals("fallback", client.getConfigString("channel.probe.absent", "fallback"));
        System.setProperty("fess.config.rag.llm.bedrock.channel.probe.planted", "planted");
        try {
            assertEquals("planted", client.getConfigString("channel.probe.planted", "fallback"));
        } finally {
            System.clearProperty("fess.config.rag.llm.bedrock.channel.probe.planted");
        }
    }

    @Test
    public void test_getConfigString_alsoResolvesFromFessConfigProperties() {
        // Nothing under rag.llm.bedrock.* is declared in the fess_config.properties Fess ships, so
        // the same getConfigString body is pointed at a prefix the shipped file does declare.
        final BedrockLlmClient client = new BedrockLlmClient() {
            @Override
            protected String getConfigPrefix() {
                return "rag.chat";
            }
        };
        assertFalse("rag.chat.enabled must come from fess_config.properties", SENTINEL.equals(client.getConfigString("enabled", SENTINEL)));
    }

    @Test
    public void test_temperatureEnabled_readFromTheFessConfigChannel() {
        System.setProperty("fess.config.rag.llm.bedrock.temperature.enabled", "false");
        try {
            assertFalse(new BedrockLlmClient().isTemperatureEnabled());
        } finally {
            System.clearProperty("fess.config.rag.llm.bedrock.temperature.enabled");
        }
    }

    @Test
    public void test_systemPropertiesChannelIsNotRead() {
        ComponentUtil.getSystemProperties().setProperty("rag.llm.bedrock.model", "wrong-channel-model");
        try {
            assertEquals("us.amazon.nova-2-lite-v1:0", new BedrockLlmClient().getModel());
        } finally {
            ComponentUtil.getSystemProperties().remove("rag.llm.bedrock.model");
        }
    }
}
