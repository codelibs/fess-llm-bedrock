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
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.util.CredentialUrlUtil;

/**
 * Resolves the Bedrock Runtime endpoint and builds request URIs from configuration without letting
 * a configured URL reach a log or an exception message.
 *
 * <p>{@code URI.create} quotes the whole URI in the {@link IllegalArgumentException} it raises, and
 * an endpoint can carry a credential in its userinfo. Every URI built here therefore either
 * succeeds or fails with a message that names only the configuration key (see
 * {@link CredentialUrlUtil#invalidUrlException(String, IllegalArgumentException)}), and a URL with
 * userinfo is refused outright: RFC 9110 forbids it in an http/https target and HttpClient rejects
 * it unconditionally.
 */
public final class BedrockEndpoint {

    /** The region used when none is configured. */
    public static final String DEFAULT_REGION = "us-east-1";

    /** A region name: lowercase letters, digits and hyphens, as in {@code us-east-1}. */
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private BedrockEndpoint() {
        // utility class
    }

    /**
     * Returns whether {@code region} is shaped like an AWS region name. It becomes part of the
     * default host name and of the SigV4 credential scope, so anything else cannot work.
     *
     * @param region the configured region.
     * @return true when the region is usable.
     */
    public static boolean isValidRegion(final String region) {
        return region != null && REGION_PATTERN.matcher(region).matches();
    }

    /**
     * Returns the base URL requests are sent to: the configured endpoint with a trailing
     * {@code /} removed, or {@code https://bedrock-runtime.<region>.amazonaws.com} when no endpoint
     * is configured.
     *
     * @param endpoint the configured endpoint, may be blank.
     * @param region the configured region.
     * @return the base URL, without a trailing slash.
     */
    public static String resolveBaseUrl(final String endpoint, final String region) {
        if (StringUtil.isBlank(endpoint)) {
            return "https://bedrock-runtime." + region + ".amazonaws.com";
        }
        String base = endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * Percent-encodes a model ID (or an inference profile ARN) as one path segment: every byte
     * other than an RFC 3986 unreserved character is encoded, so {@code :} becomes {@code %3A} and
     * the {@code /} of an ARN becomes {@code %2F}.
     *
     * @param modelId the model ID.
     * @return the encoded path segment.
     */
    public static String encodeModelId(final String modelId) {
        final byte[] bytes = modelId.getBytes(StandardCharsets.UTF_8);
        final StringBuilder buf = new StringBuilder(bytes.length * 3);
        for (final byte b : bytes) {
            final int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') {
                buf.append((char) c);
            } else {
                buf.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0f]);
            }
        }
        return buf.toString();
    }

    /**
     * Builds the URL of a model operation, {@code <base>/model/<encoded model id>/<operation>}.
     *
     * @param baseUrl the base URL from {@link #resolveBaseUrl(String, String)}.
     * @param modelId the model ID or inference profile ARN.
     * @param operation {@code converse}, {@code converse-stream} or {@code invoke}.
     * @return the URL.
     */
    public static String modelUrl(final String baseUrl, final String modelId, final String operation) {
        return baseUrl + "/model/" + encodeModelId(modelId) + "/" + operation;
    }

    /**
     * Builds the message reported when a configured endpoint carries userinfo. It names the key and
     * the supported alternatives, and no part of the URL.
     *
     * @param configKey the configuration key the endpoint was read from.
     * @return the message.
     */
    public static String userInfoRejectedMessage(final String configKey) {
        return "Refusing the URL configured in " + configKey
                + ": its authority carries a userinfo credential, which RFC 9110 forbids in an http/https target URI. "
                + "HttpClient rejects such a request URI unconditionally, so this URL can never issue a request. "
                + "Remove the credential from the URL: Bedrock authenticates with the configured api.key or with AWS "
                + "credentials (SigV4). If the endpoint sits behind an authenticating proxy, configure http.proxy.host, "
                + "http.proxy.port, http.proxy.username and http.proxy.password instead. "
                + "The URL itself is omitted here because it holds the credential.";
    }

    /**
     * Converts a URL to a {@link URI} for a request.
     *
     * @param url the URL.
     * @param configKey the configuration key the URL was derived from, named in a failure.
     * @return the URI.
     * @throws IllegalArgumentException when the URL carries userinfo or is not a valid URI; the
     *             message carries no part of the URL and there is no cause.
     */
    public static URI toUri(final String url, final String configKey) {
        if (CredentialUrlUtil.hasUserInfo(url)) {
            throw new IllegalArgumentException(userInfoRejectedMessage(configKey));
        }
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (final IllegalArgumentException e) {
            throw CredentialUrlUtil.invalidUrlException(configKey, e);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid URL configured in " + configKey + ": the URL has no host");
        }
        return uri;
    }
}
