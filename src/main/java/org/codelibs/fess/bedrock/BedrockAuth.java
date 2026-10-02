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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.hc.core5.http.HttpRequest;
import org.codelibs.core.lang.StringUtil;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Authorizes a request to Bedrock Runtime, either with a Bedrock API key or with AWS SigV4.
 *
 * <p>A non-blank API key is sent as {@code Authorization: Bearer <key>}. Without one the request is
 * signed with {@link AwsV4HttpSigner} (signing name {@value #SIGNING_NAME}, payload signed) using
 * credentials from the SDK's {@link DefaultCredentialsProvider}: the {@code aws.accessKeyId} /
 * {@code aws.secretAccessKey} / {@code aws.sessionToken} system properties, the
 * {@code AWS_*} environment variables, web identity, the {@code ~/.aws} profile, the container
 * credentials endpoint and the EC2 instance metadata service, in that order. The provider is created
 * on first use and kept, so its credential cache and refresh survive across requests;
 * {@link #close()} releases it.
 *
 * <p>Only the host and the {@code x-amz-*} headers are signed, not {@code Content-Type}: HttpClient
 * renders the entity's content type itself, and a signed header whose sent value differs by so much
 * as a charset parameter fails the signature. Every header the signer produces, {@code Host}
 * included, is copied onto the outgoing request so the request sent is the request signed.
 *
 * <p>Nothing here logs. A credential, a signature or an {@code Authorization} value must not reach a
 * log line at any level.
 */
public class BedrockAuth {

    /** The SigV4 signing name of Bedrock Runtime. */
    public static final String SIGNING_NAME = "bedrock";

    /** How long {@link #checkCredentials()} remembers a failed credential lookup, in milliseconds. */
    public static final long CREDENTIALS_FAILURE_CACHE_MS = 60_000L;

    private final Supplier<AwsCredentialsProvider> providerFactory;

    private final Clock clock;

    private AwsCredentialsProvider provider;

    private volatile long credentialsFailedAt;

    private volatile String credentialsFailure;

    /**
     * Creates an authorizer backed by the SDK's default credentials provider chain and the system
     * clock.
     */
    public BedrockAuth() {
        this(() -> DefaultCredentialsProvider.builder().build(), Clock.systemUTC());
    }

    /**
     * Creates an authorizer with an explicit credentials source and signing clock.
     *
     * @param providerFactory creates the credentials provider on first use.
     * @param clock the clock the signature's date is taken from.
     */
    public BedrockAuth(final Supplier<AwsCredentialsProvider> providerFactory, final Clock clock) {
        this.providerFactory = providerFactory;
        this.clock = clock;
    }

    /**
     * Returns whether requests are authorized with an API key rather than SigV4.
     *
     * @param apiKey the configured API key.
     * @return true when {@code apiKey} is not blank.
     */
    public static boolean usesApiKey(final String apiKey) {
        return StringUtil.isNotBlank(apiKey);
    }

    /**
     * Resolves the AWS credentials SigV4 would sign with, creating the provider on first use.
     *
     * @return the credentials.
     * @throws RuntimeException when no source in the chain yields credentials (the SDK's
     *             {@code SdkClientException}); its message names the sources tried and carries no
     *             secret.
     */
    public AwsCredentialsIdentity resolveCredentials() {
        return getProvider().resolveCredentials();
    }

    /**
     * Adds authorization to {@code request}. The request's URI and entity must already be final:
     * the signature covers {@code uri} and {@code body}.
     *
     * @param request the outgoing request.
     * @param uri the request URI, exactly as it will be sent.
     * @param body the exact entity bytes that will be sent.
     * @param apiKey the configured API key; blank selects SigV4.
     * @param region the region for the SigV4 credential scope.
     */
    public void authorize(final HttpRequest request, final URI uri, final byte[] body, final String apiKey, final String region) {
        if (usesApiKey(apiKey)) {
            request.setHeader("Authorization", "Bearer " + apiKey.trim());
            return;
        }
        final SdkHttpRequest unsigned = SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(request.getMethod())).uri(uri).build();
        final SignedRequest signed = sign(unsigned, body, resolveCredentials(), region);
        for (final Map.Entry<String, List<String>> header : signed.request().headers().entrySet()) {
            if (!header.getValue().isEmpty()) {
                request.setHeader(header.getKey(), header.getValue().get(0));
            }
        }
    }

    /**
     * Signs {@code unsigned} with SigV4 for Bedrock Runtime: signing name {@value #SIGNING_NAME},
     * {@code region}, payload signed, path double-encoded (the signer's defaults for every service
     * but S3), date from this authorizer's clock. The headers of {@code unsigned} are signed too.
     *
     * @param unsigned the request to sign.
     * @param body the payload.
     * @param credentials the credentials.
     * @param region the region for the credential scope.
     * @return the signed request.
     */
    SignedRequest sign(final SdkHttpRequest unsigned, final byte[] body, final AwsCredentialsIdentity credentials, final String region) {
        return AwsV4HttpSigner.create()
                .sign(r -> r.request(unsigned)
                        .payload(ContentStreamProvider.fromByteArray(body))
                        .identity(credentials)
                        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_NAME)
                        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                        .putProperty(HttpSigner.SIGNING_CLOCK, clock));
    }

    /**
     * Reports whether AWS credentials can be resolved, for availability checks. A failure is
     * remembered for {@value #CREDENTIALS_FAILURE_CACHE_MS} ms: without an API key or credentials the
     * default chain ends with the EC2 instance metadata service, and off EC2 that probe is slow
     * - on every check and every {@code isAvailable()} call that is not served from the
     * cached availability. Signing a request never uses this cache.
     *
     * @return {@code null} when credentials resolve, otherwise why not (no secret in it).
     */
    public String checkCredentials() {
        final long now = clock.millis();
        final String cached = credentialsFailure;
        if (cached != null && now - credentialsFailedAt < CREDENTIALS_FAILURE_CACHE_MS) {
            return cached;
        }
        try {
            resolveCredentials();
            credentialsFailure = null;
            return null;
        } catch (final RuntimeException e) {
            final String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            credentialsFailedAt = now;
            credentialsFailure = reason;
            return reason;
        }
    }

    /**
     * Releases the credentials provider, if one was created, and forgets a remembered credential
     * failure. Safe to call more than once.
     */
    public synchronized void close() {
        if (provider instanceof final SdkAutoCloseable closeable) {
            closeable.close();
        }
        provider = null;
        credentialsFailure = null;
    }

    private synchronized AwsCredentialsProvider getProvider() {
        if (provider == null) {
            provider = providerFactory.get();
        }
        return provider;
    }
}
