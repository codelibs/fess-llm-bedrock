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

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.eventstream.MessageDecoder;

/**
 * Pins the three SDK entry points this plugin is built on, so a dependency change that drops one
 * of them fails here rather than at run time inside Fess.
 */
public class AwsSdkClasspathTest extends UnitFessTestCase {

    @Test
    public void test_sdkEntryPointsResolve() {
        assertNotNull(AwsV4HttpSigner.create());
        assertNotNull(new MessageDecoder());
        try (DefaultCredentialsProvider provider = DefaultCredentialsProvider.builder().build()) {
            assertNotNull(provider);
        }
    }
}
