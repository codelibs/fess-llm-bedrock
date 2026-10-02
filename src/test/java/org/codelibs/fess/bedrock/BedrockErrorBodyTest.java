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

import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;

public class BedrockErrorBodyTest extends UnitFessTestCase {

    @Test
    public void test_render_jsonBodyAndNamespacedHeader() {
        assertEquals("type=ThrottlingException,message=Too many requests, please wait before trying again.",
                BedrockErrorBody.render("{\"message\":\"Too many requests, please wait before trying again.\"}",
                        "ThrottlingException:http://internal.amazon.com/coral/com.amazon.bedrock/"));
    }

    @Test
    public void test_render_capitalizedMessageFieldAndPlainHeader() {
        assertEquals("type=AccessDeniedException,message=denied",
                BedrockErrorBody.render("{\"Message\":\"denied\"}", "AccessDeniedException"));
    }

    @Test
    public void test_render_withoutHeaderOrBody() {
        assertEquals("type=null,message=", BedrockErrorBody.render(null, null));
        assertEquals("type=null,message=", BedrockErrorBody.render("  ", " "));
    }

    @Test
    public void test_message_nonJsonBodyIsClipped() {
        assertEquals("<html>Bad Gateway</html>", BedrockErrorBody.message("  <html>Bad Gateway</html>\n"));
        final String longBody = "x".repeat(2000);
        final String clipped = BedrockErrorBody.message(longBody);
        assertEquals(1024 + "...(truncated)".length(), clipped.length());
    }

    @Test
    public void test_indicatesInputTooLong() {
        assertTrue(BedrockErrorBody.indicatesInputTooLong("Input is too long for requested model."));
        assertTrue(BedrockErrorBody.indicatesInputTooLong("The input exceeds the model's context window."));
        assertTrue(BedrockErrorBody.indicatesInputTooLong("prompt is too long: 210000 tokens > 200000 maximum"));
        assertFalse(BedrockErrorBody.indicatesInputTooLong("Malformed input request: #: extraneous key [foo] is not permitted"));
        assertFalse(BedrockErrorBody.indicatesInputTooLong(null));
    }

    @Test
    public void test_message_dropsTheQuotedCanonicalRequest() {
        final String body = "{\"message\":\"The request signature we calculated does not match the signature you provided. "
                + "Check your AWS Secret Access Key and signing method. Consult the service documentation for details.\\n\\n"
                + "The Canonical String for this request should have been\\n'POST\\n/model/m/converse\\n\\n"
                + "host:bedrock-runtime.us-east-1.amazonaws.com\\nx-amz-security-token:example-session-token\\n'\\n\\n"
                + "The String-to-Sign should have been\\n'AWS4-HMAC-SHA256\\n20261002T000000Z\\n'\"}";
        final String message = BedrockErrorBody.message(body);
        assertEquals("The request signature we calculated does not match the signature you provided. "
                + "Check your AWS Secret Access Key and signing method. Consult the service documentation for details.", message);
        final String rendered = BedrockErrorBody.render(body, "InvalidSignatureException");
        assertFalse(rendered, rendered.contains("example-session-token"));
        assertFalse(rendered, rendered.contains("Canonical String"));
        assertEquals("plain text without markers", BedrockErrorBody.message("plain text without markers"));
        assertEquals("raw", BedrockErrorBody.message("raw The String-to-Sign should have been 'x'"));
    }
}
