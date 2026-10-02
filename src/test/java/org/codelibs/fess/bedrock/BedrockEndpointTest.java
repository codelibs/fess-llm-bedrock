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

import java.net.URI;

import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;

public class BedrockEndpointTest extends UnitFessTestCase {

    private static final String CONFIG_KEY = "rag.llm.bedrock.endpoint";

    @Test
    public void test_resolveBaseUrl_derivesFromRegion() {
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com", BedrockEndpoint.resolveBaseUrl("", "us-east-1"));
        assertEquals("https://bedrock-runtime.ap-northeast-1.amazonaws.com", BedrockEndpoint.resolveBaseUrl(null, "ap-northeast-1"));
    }

    @Test
    public void test_resolveBaseUrl_overrideTrimsTrailingSlashes() {
        assertEquals("https://vpce-0123.bedrock-runtime.us-east-1.vpce.amazonaws.com",
                BedrockEndpoint.resolveBaseUrl(" https://vpce-0123.bedrock-runtime.us-east-1.vpce.amazonaws.com// ", "us-east-1"));
    }

    @Test
    public void test_isValidRegion() {
        assertTrue(BedrockEndpoint.isValidRegion("us-east-1"));
        assertTrue(BedrockEndpoint.isValidRegion("ap-northeast-1"));
        assertFalse(BedrockEndpoint.isValidRegion(""));
        assertFalse(BedrockEndpoint.isValidRegion(null));
        assertFalse(BedrockEndpoint.isValidRegion("US-EAST-1"));
        assertFalse(BedrockEndpoint.isValidRegion("us-east-1.evil.example.com"));
        assertFalse(BedrockEndpoint.isValidRegion("us east 1"));
    }

    @Test
    public void test_encodeModelId_encodesColonAndSlash() {
        assertEquals("us.amazon.nova-2-lite-v1%3A0", BedrockEndpoint.encodeModelId("us.amazon.nova-2-lite-v1:0"));
        assertEquals("arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fglobal.cohere.embed-v4%3A0",
                BedrockEndpoint.encodeModelId("arn:aws:bedrock:us-east-1:123456789012:inference-profile/global.cohere.embed-v4:0"));
        assertEquals("a%20b%2Bc~_", BedrockEndpoint.encodeModelId("a b+c~_"));
    }

    @Test
    public void test_modelUrl() {
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/model/amazon.titan-embed-text-v2%3A0/invoke",
                BedrockEndpoint.modelUrl("https://bedrock-runtime.us-east-1.amazonaws.com", "amazon.titan-embed-text-v2:0", "invoke"));
    }

    @Test
    public void test_toUri_keepsEncodedPath() {
        final URI uri = BedrockEndpoint.toUri("http://localhost:8080/model/us.amazon.nova-2-lite-v1%3A0/converse", CONFIG_KEY);
        assertEquals("/model/us.amazon.nova-2-lite-v1%3A0/converse", uri.getRawPath());
    }

    @Test
    public void test_toUri_refusesUserInfoWithoutEchoingIt() {
        try {
            BedrockEndpoint.toUri("https://alice:s3cr3t@bedrock.example.com/model/m/converse", CONFIG_KEY);
            fail("expected IllegalArgumentException");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(CONFIG_KEY));
            assertFalse(e.getMessage(), e.getMessage().contains("s3cr3t"));
            assertFalse(e.getMessage(), e.getMessage().contains("bedrock.example.com"));
            assertNull(e.getCause(), "no cause may carry the URL");
        }
    }

    @Test
    public void test_toUri_malformedUrlIsNotEchoed() {
        try {
            BedrockEndpoint.toUri("https://bedrock.example.com/model/m/converse?token=a b", CONFIG_KEY);
            fail("expected IllegalArgumentException");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(CONFIG_KEY));
            assertFalse(e.getMessage(), e.getMessage().contains("bedrock.example.com"));
            assertFalse(e.getMessage(), e.getMessage().contains("a b"));
            assertNull(e.getCause(), "no cause may carry the URL");
        }
    }

    @Test
    public void test_toUri_refusesUrlWithoutHost() {
        try {
            BedrockEndpoint.toUri("bedrock-runtime.us-east-1.amazonaws.com/model/m/converse", CONFIG_KEY);
            fail("expected IllegalArgumentException");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(CONFIG_KEY));
            assertFalse(e.getMessage(), e.getMessage().contains("amazonaws.com"));
        }
    }
}
