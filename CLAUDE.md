# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`fess-llm-bedrock` is a Fess plugin that makes Amazon Bedrock usable as the LLM of Fess's RAG chat
(`BedrockLlmClient`, an `AbstractLlmClient`) and as the content-chunk embedding provider
(`BedrockEmbeddingClient`, an `AbstractEmbeddingClient`). It mirrors `fess-llm-openai`,
`fess-llm-gemini` and `fess-llm-ollama` in structure, configuration channels, logging and retry.

## Build Commands

```bash
# Build (shaded jar in target/)
mvn clean package

# Run tests
mvn test

# Run one test class / method
mvn test -Dtest=BedrockLlmClientTest
mvn test -Dtest=BedrockLlmClientStreamTest#test_streamChat_missingMessageStopIsInvalidResponse

# Format and license headers (run before committing)
mvn formatter:format license:format

# Install fess-parent (required before the first build when it is not in ~/.m2)
cd ../fess-parent && mvn install -Dgpg.skip=true
```

## Architecture

- `org.codelibs.fess.llm.bedrock.BedrockLlmClient` - Converse (`/model/{id}/converse`) and
  ConverseStream (`/model/{id}/converse-stream`). Requests go through the HttpClient 5 of
  `AbstractLlmClient`; Jackson 3 builds and parses the JSON.
- `org.codelibs.fess.embedding.bedrock.BedrockEmbeddingClient` - InvokeModel
  (`/model/{id}/invoke`) for Titan Text Embeddings V2 (one text per request) and Cohere Embed v3/v4
  (batches of 96). The family is recognized by the base model name inside the ID
  (`amazon.titan-embed-text-v2`, `cohere.embed-english-v3`, `cohere.embed-multilingual-v3`,
  `cohere.embed-v4`), so cross-region inference profile IDs and ARNs containing them work;
  application inference profiles (opaque IDs) and every other model are unsupported.
- `org.codelibs.fess.bedrock.BedrockAuth` - `Authorization: Bearer <api.key>`, or SigV4 with the
  SDK's `AwsV4HttpSigner` and `DefaultCredentialsProvider` (created on first use, closed by
  `destroy()`). Only host and `x-amz-*` headers are signed; every signed header, `Host` included,
  is copied onto the HttpClient request, and the entity is the exact signed byte array.
  `checkCredentials()` serves availability checks and remembers a failed lookup for 60 s (off EC2
  the default chain ends with a slow instance-metadata probe); signing never uses that cache.
  `BedrockAuthOfficialClientTest` compares the signature with the official `BedrockRuntimeClient`
  (test-scope dependency, captured through an in-memory `SdkHttpClient`).
- `BedrockEndpoint` (region/endpoint resolution, model-ID path encoding, userinfo refusal,
  malformed-URL messages that name only the config key), `BedrockRetry` (retryable statuses,
  `Retry-After`, connect-failure test, backoff), `BedrockErrorBody` (`{"message"}` +
  `x-amzn-ErrorType` as one log field; a `SignatureDoesNotMatch` message is cut before the quoted
  canonical request, which can carry the session token), `EventStreamReader` (frames via
  `software.amazon.eventstream.MessageDecoder`).

The AWS SDK is used only for credentials, signing and the event-stream decoder. It is shaded
unrelocated at `${s3.version}` like `fess-storage-s3`, so the two plugins carry identical classes.
`org.apache.httpcomponents.*` and `org.slf4j` are excluded from the shade because the war ships them.
Keep `corelib`, `lastaflute`, `dbflute-runtime` and `slf4j-api` at `provided` scope: anything
compile-scoped ends up in the shaded jar.

### Configuration channels

- `rag.llm.bedrock.*` - `FessConfig.getOrDefault` (`fess_config.properties` / `-Dfess.config.*`).
  Every read in this plugin goes through `BedrockLlmClient#getConfigString`; `getConfigInt` is
  overridden to use it. Core's `AbstractLlmClient` reads `max.concurrent.requests`,
  `concurrency.wait.timeout` and `<promptType>.temperature|max.tokens|thinking.budget` itself via
  `getOrDefault` (same channel, but not through `getConfigString`, so the map override of
  `TestableBedrockLlmClient` does not cover them).
- `content_chunker.embedding.bedrock.*` - `FessConfig.getSystemProperty`
  (`conf/system.properties` / `-Dfess.system.*`), through `AbstractEmbeddingClient`'s getters.

`BedrockLlmClientConfigChannelTest` and `BedrockEmbeddingClientConfigChannelTest` pin both; the
other test classes replace the reads with a map and would not notice a channel change.

### Request mapping

- System messages become `system`; user and assistant messages become `messages`. Converse rejects
  a conversation that starts with an assistant message, two consecutive messages of one role, or a
  blank text block, and Fess history can produce all three, so `buildMessages` drops leading
  assistant messages, merges consecutive same-role messages into one message with several text
  blocks, and skips blank messages.
- `inferenceConfig.maxTokens` / `temperature`; `temperature.enabled=false` keeps temperature out.
- `additionalModelRequestFields` from `<promptType>.additional.model.request.fields` (replaces) or
  `additional.model.request.fields`. A non-object or invalid value WARNs every time and is not sent.
- `thinkingBudget` (set by core from `<promptType>.thinking.budget`) is never sent; it WARNs.

### Streaming

Retry happens only until the response status is known. Once the body flows, nothing is retried:
`contentBlockDelta.delta.text` becomes `onChunk(text, false)`, reasoning deltas are dropped,
`messageStop` records the stop reason, `metadata` the usage. An `exception` frame (mapped:
throttling -> `rate_limit`, serviceUnavailable -> `service_unavailable`, validation ->
`context_length_exceeded` when the message says the input is too long, else `invalid_response`,
others -> `unknown`), an `error` frame, a corrupt frame (`invalid_response`), a read failure
(`connection_error`) or a body without `messageStop` (`invalid_response`) fails the call. When
reading stops early the request is cancelled before the body is closed. `onError` is always called
before the exception propagates, and `onChunk("", true)` is sent only after a complete stream.

### Logging keys

- `[LLM:BEDROCK] Chat response received.` (INFO): `model`, `inputTokens`, `outputTokens`,
  `totalTokens`, `stopReason`, `contentLength`, `elapsedTime`.
- `[LLM:BEDROCK] Stream completed.` (INFO): `chunkCount`, `frameCount`, `firstChunkMs`,
  `elapsedTime`, `stopReason`, `inputTokens`, `outputTokens`, `totalTokens`,
  `reasoningDeltaCount`, `parseErrorCount`.
- `Chat finished abnormally` / `Stream finished abnormally` (WARN) for any stop reason other than
  `end_turn` / `stop_sequence`.
- `[Embedding:BEDROCK] Embed completed.` (INFO): `model`, `inputType`, `count`, `requests`,
  `elapsedTime`.
- Configuration that can never work (bad region, endpoint with userinfo or malformed, unsupported
  embedding model or dimension) is logged at ERROR on every availability check, never latched.

Credentials, API keys, signatures and `Authorization` values must never be logged, at any level.
The `test_credentialsNeverLogged` tests (one per client) enable DEBUG and check every captured
line. Two loggers outside this plugin do write them at DEBUG: Apache HttpClient's wire log
(`Authorization`) and the SDK signer
`software.amazon.awssdk.http.auth.aws.internal.signer.DefaultV4RequestSigner` (canonical request,
including `x-amz-security-token`); the README tells users to keep both at INFO.

## Testing

Tests extend `UnitFessTestCase` (utflute `WebContainerTestCase`) and use OkHttp `MockWebServer`.
`TestableBedrockLlmClient` / `TestableBedrockEmbeddingClient` serve configuration from a map and
credentials from a fixed provider with a fixed signing clock. ConverseStream bodies are built with
`EventStreamFrames`, which encodes frames with AWS's own `software.amazon.eventstream.Message`.
Test credentials are only the AWS documentation examples (`AKIAIOSFODNN7EXAMPLE` /
`wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`); nothing calls AWS.

## Coding Conventions

- Java 21; `formatter-maven-plugin` and `license-maven-plugin` from fess-parent
- `final` on local variables and parameters
- Log prefixes `[LLM:BEDROCK]` and `[Embedding:BEDROCK]`; guard DEBUG with `logger.isDebugEnabled()`
- Prompts come from `fess_llm++.xml`, a copy of the sibling plugins' prompts; keep them in step
