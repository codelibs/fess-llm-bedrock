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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Compares {@link BedrockAuth} with the official {@code BedrockRuntimeClient} (a test-scope
 * dependency, never shaded). The official client's request is captured by an in-memory HTTP client,
 * so nothing leaves the JVM; the same request, body, credentials and date signed by
 * {@link BedrockAuth} must produce the same {@code Authorization} value, and the model path must be
 * the one {@link BedrockEndpoint} builds. A wrong signing name, region, path encoding or payload
 * hashing would break the equality.
 */
public class BedrockAuthOfficialClientTest extends UnitFessTestCase {

    private static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final byte[] CONVERSE_OK = ("{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"ok\"}]}},"
            + "\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2},\"metrics\":{\"latencyMs\":1}}")
                    .getBytes(StandardCharsets.UTF_8);

    /** Captures every request and answers it with a canned Converse response. */
    private static final class CapturingHttpClient implements SdkHttpClient {
        final List<HttpExecuteRequest> requests = new ArrayList<>();

        @Override
        public ExecutableHttpRequest prepareRequest(final HttpExecuteRequest request) {
            requests.add(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder().statusCode(200).putHeader("Content-Type", "application/json").build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(CONVERSE_OK)))
                            .build();
                }

                @Override
                public void abort() {
                    // nothing to abort
                }
            };
        }

        @Override
        public void close() {
            // nothing to close
        }
    }

    private void assertSameSignature(final String modelId) throws IOException {
        final CapturingHttpClient httpClient = new CapturingHttpClient();
        try (BedrockRuntimeClient official = BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
                .httpClient(httpClient)
                .build()) {
            official.converse(r -> r.modelId(modelId)
                    .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText("Hi")).build()));
        }
        final HttpExecuteRequest captured = httpClient.requests.get(0);
        final SdkHttpRequest signedByAws = captured.httpRequest();
        final byte[] body;
        try (InputStream in = captured.contentStreamProvider().orElseThrow().newStream()) {
            body = in.readAllBytes();
        }

        assertEquals(BedrockEndpoint.modelUrl("https://bedrock-runtime.us-east-1.amazonaws.com", modelId, "converse"),
                signedByAws.getUri().toString());

        final Instant date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .parse(signedByAws.firstMatchingHeader("X-Amz-Date").orElseThrow(), Instant::from);
        final BedrockAuth auth =
                new BedrockAuth(() -> StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY)),
                        Clock.fixed(date, ZoneOffset.UTC));
        final SdkHttpRequest unsigned = signedByAws.toBuilder().removeHeader("Authorization").build();
        final String ours = auth.sign(unsigned, body, auth.resolveCredentials(), "us-east-1")
                .request()
                .firstMatchingHeader("Authorization")
                .orElseThrow();
        assertEquals(signedByAws.firstMatchingHeader("Authorization").orElseThrow(), ours);
    }

    @Test
    public void test_sameSignatureAsTheOfficialClient_modelId() throws IOException {
        assertSameSignature("us.amazon.nova-2-lite-v1:0");
    }

    @Test
    public void test_sameSignatureAsTheOfficialClient_inferenceProfileArn() throws IOException {
        assertSameSignature("arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.amazon.nova-2-lite-v1:0");
    }
}
