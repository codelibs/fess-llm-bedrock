Amazon Bedrock LLM Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-llm-bedrock/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-llm-bedrock/actions/workflows/maven.yml)
==================================

## Overview

This plugin makes [Amazon Bedrock](https://aws.amazon.com/bedrock/) usable from [Fess](https://github.com/codelibs/fess):

- as the LLM of Fess's RAG (Retrieval-Augmented Generation) chat - intent detection, answer
  generation, document summarization, FAQ handling and relevance evaluation - through the
  Bedrock Runtime **Converse** and **ConverseStream** APIs, and
- as the embedding provider for content chunks, through **InvokeModel** with Amazon Titan Text
  Embeddings V2 or Cohere Embed v3 / v4.

## Download

See [maven.codelibs.org](https://maven.codelibs.org/org/codelibs/fess/fess-llm-bedrock/).

## Requirements

- Fess 15.9 or later
- Java 21 or later
- Access to Amazon Bedrock in the AWS region you use, with either a Bedrock API key or AWS
  credentials

## Installation

```
$ bin/fess-setup install plugin fess-llm-bedrock
```

Or download the jar and put it in `app/WEB-INF/plugin`. Restart Fess afterwards.

Install the plugin version that matches your Fess version. This plugin and `fess-storage-s3` both
bundle the AWS SDK for Java unrelocated, at the same SDK version for the same Fess version; when
both are installed, upgrade them together.

## Configuration

This plugin reads properties from two independent Fess configuration channels. They are not
interchangeable: a value placed in the wrong file is ignored.

- **`rag.llm.bedrock.*` / `rag.chat.*`** - `fess_config.properties` (or a `-Dfess.config.<key>`
  JVM option). A change requires a restart.
- **`rag.llm.name`**, **`content_chunker.enabled`**, **`content_chunker.embedding.name`**,
  **`content_chunker.embedding.dimension`** and **`content_chunker.embedding.bedrock.*`** -
  `conf/system.properties` (or a `-Dfess.system.<key>` JVM option).

`rag.llm.bedrock.api.key` and `content_chunker.embedding.bedrock.api.key` are masked under System
Info in the admin UI when they are set in `fess_config.properties` or `conf/system.properties`.
Environment variables and JVM system properties (including `-Dfess.config.*` / `-Dfess.system.*`
options) are listed there unmasked, so do not pass an API key or AWS credential that way.

### Enabling the Plugin

| Property | Default | Description |
|----------|---------|-------------|
| `rag.llm.name` | `ollama` | Set to `bedrock` to use this plugin as the RAG LLM. A `conf/system.properties` key, also editable under System > General. |

### RAG Chat / LLM

Configure the following properties in `fess_config.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `rag.chat.enabled` | `false` | Enable RAG chat |
| `rag.llm.bedrock.api.key` | - | Bedrock API key. When blank, requests are signed with AWS credentials (see [Authentication](#authentication)) |
| `rag.llm.bedrock.region` | `us-east-1` | AWS region of the endpoint and of the SigV4 signature |
| `rag.llm.bedrock.endpoint` | `https://bedrock-runtime.<region>.amazonaws.com` | Endpoint override, e.g. a VPC interface endpoint |
| `rag.llm.bedrock.model` | `us.amazon.nova-2-lite-v1:0` | Model ID, inference profile ID or ARN of a model that supports the Converse API |
| `rag.llm.bedrock.timeout` | `120000` | HTTP request timeout in milliseconds |
| `rag.llm.bedrock.availability.check.interval` | `60` | Interval (seconds) of the availability check |
| `rag.llm.bedrock.temperature.enabled` | `true` | `false` keeps `temperature` out of every request, for models or modes that reject it |
| `rag.llm.bedrock.additional.model.request.fields` | - | JSON object sent as `additionalModelRequestFields` (model-specific parameters) |
| `rag.llm.bedrock.<promptType>.additional.model.request.fields` | - | Same, for one prompt type; replaces the global value |
| `rag.llm.bedrock.<promptType>.temperature` | per prompt type, below | Overrides the default temperature |
| `rag.llm.bedrock.<promptType>.max.tokens` | per prompt type, below | Overrides the default `maxTokens` |
| `rag.llm.bedrock.<promptType>.thinking.budget` | - | Not supported. A configured value is not sent and is logged at WARN; use `additional.model.request.fields` |
| `rag.llm.bedrock.answer.context.max.chars` | `16000` | Maximum characters of retrieved context for the `answer` prompt |
| `rag.llm.bedrock.summary.context.max.chars` | `16000` | Maximum characters of the document for the `summary` prompt |
| `rag.llm.bedrock.faq.context.max.chars` | `10000` | Maximum characters of retrieved context for the `faq` prompt |
| `rag.llm.bedrock.chat.evaluation.max.relevant.docs` | `3` | Maximum number of relevant documents for evaluation |
| `rag.llm.bedrock.chat.evaluation.description.max.chars` | `500` | Maximum characters of each document description during evaluation |
| `rag.llm.bedrock.history.max.chars` | `8000` | Maximum characters of conversation history |
| `rag.llm.bedrock.history.assistant.max.chars` | `800` | Maximum characters kept from each assistant turn |
| `rag.llm.bedrock.history.assistant.summary.max.chars` | `800` | Maximum characters kept from each assistant summary |
| `rag.llm.bedrock.intent.history.max.messages` | `8` | Maximum history messages passed to the intent prompt |
| `rag.llm.bedrock.intent.history.max.chars` | `4000` | Maximum history characters passed to the intent prompt |
| `rag.llm.bedrock.retry.max` | `10` | Maximum HTTP attempts per call (initial attempt plus retries) on `429`, `500`, `502`, `503`, `504` |
| `rag.llm.bedrock.retry.base.delay.ms` | `2000` | Base delay (ms) of the exponential backoff between attempts |
| `rag.llm.bedrock.max.concurrent.requests` | `5` | Maximum concurrent requests to Bedrock |
| `rag.llm.bedrock.concurrency.wait.timeout` | `30000` | Milliseconds a request waits for a concurrency slot |

`<promptType>` is one of `intent`, `evaluation`, `unclear`, `noresults`, `docnotfound`, `direct`,
`faq`, `answer`, `summary`, `queryregeneration`. Built-in defaults:

| Prompt type | temperature | maxTokens |
|-------------|-------------|-----------|
| `intent`, `evaluation` | 0.1 | 256 |
| `unclear`, `noresults` | 0.7 | 512 |
| `docnotfound` | 0.7 | 256 |
| `direct`, `faq` | 0.7 | 1024 |
| `answer` | 0.5 | 2048 |
| `summary` | 0.3 | 2048 |
| `queryregeneration` | 0.3 | 256 |

#### Model-specific parameters

`additional.model.request.fields` is passed to the model unchanged. A value that is not a JSON
object is not sent, and a WARN naming the property is logged every time it is read.

For example, extended thinking of an Anthropic Claude model for answer generation only:

```properties
rag.llm.bedrock.model=<a Claude model ID or inference profile ID>
rag.llm.bedrock.temperature.enabled=false
rag.llm.bedrock.answer.additional.model.request.fields={"thinking":{"type":"enabled","budget_tokens":2048}}
rag.llm.bedrock.answer.max.tokens=6144
```

Claude rejects `temperature` while thinking is enabled, and `max.tokens` must be larger than
`budget_tokens`. Reasoning text is never shown to users; only the answer text is.

### Content Chunk Embedding

Enable content chunking with `content_chunker.enabled=true` and select this plugin with
`content_chunker.embedding.name=bedrock`. Every property in this table is a
`conf/system.properties` key:

| Property | Default | Description |
|----------|---------|-------------|
| `content_chunker.embedding.bedrock.api.key` | - | Bedrock API key. When blank, AWS credentials are used |
| `content_chunker.embedding.bedrock.region` | `us-east-1` | AWS region |
| `content_chunker.embedding.bedrock.endpoint` | `https://bedrock-runtime.<region>.amazonaws.com` | Endpoint override |
| `content_chunker.embedding.bedrock.model` | `amazon.titan-embed-text-v2:0` | Embedding model, see the next table |
| `content_chunker.embedding.bedrock.normalize` | `true` | Titan only: normalize the vectors |
| `content_chunker.embedding.bedrock.truncate` | - | Cohere only: `NONE` / `START` / `END` (v3) or `NONE` / `LEFT` / `RIGHT` (v4). Not sent when blank |
| `content_chunker.embedding.bedrock.timeout` | `120000` | HTTP request timeout in milliseconds |
| `content_chunker.embedding.bedrock.connect.timeout` | `5000` | TCP connect timeout in milliseconds |
| `content_chunker.embedding.bedrock.availability.check.interval` | `60` | Interval (seconds) of the availability check |
| `content_chunker.embedding.bedrock.retry.max` | `10` | Maximum HTTP attempts per call |
| `content_chunker.embedding.bedrock.retry.base.delay.ms` | `2000` | Base delay (ms) of the exponential backoff |
| `content_chunker.embedding.bedrock.retry.max.delay.ms` | `60000` | Upper bound (ms) of one backoff sleep, `Retry-After` included |

`content_chunker.embedding.dimension` must be a size the model produces:

| Model | `content_chunker.embedding.dimension` | Texts per request |
|-------|---------------------------------------|-------------------|
| `amazon.titan-embed-text-v2:0` | 256, 512 or 1024 | 1 |
| `cohere.embed-english-v3`, `cohere.embed-multilingual-v3` | 1024 | up to 96, each at most 2048 characters |
| `cohere.embed-v4:0` (also `us.` / `eu.` / `global.` inference profiles) | 256, 512, 1024 or 1536 | up to 96 |

The model is recognized by the base model name inside the configured ID: a base model ID, a
cross-region inference profile ID (`us.`, `eu.`, `global.` ...), or an ARN that contains one of
those. Application inference profiles have opaque IDs and are not recognized. Any other model, or a
dimension the model does not produce, makes the client report itself unavailable with an ERROR log
line.

Cohere Embed v3 accepts at most 2048 characters per text. Bedrock rejects a longer text with
`400 ValidationException` whatever `truncate` says, and the plugin does not shorten or split
texts. `truncate` (default `END`) applies only to a text within 2048 characters that exceeds 512
tokens. Cohere receives `input_type=search_document` for documents and `search_query` for
queries; Fess query syntax (`+term`, `title:"x"^2`, `(a OR b)`) is removed from query text before
it is embedded.

### Authentication

With an API key set, requests carry `Authorization: Bearer <key>`. Short-term Bedrock API keys
expire and only work in the region they were created in.

Without an API key, requests are signed with AWS Signature Version 4, using the first credentials
found in this order:

1. JVM system properties `aws.accessKeyId`, `aws.secretAccessKey`, `aws.sessionToken`
2. environment variables `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`
3. web identity (`AWS_WEB_IDENTITY_TOKEN_FILE`, `AWS_ROLE_ARN`), as on Amazon EKS
4. the shared `~/.aws/credentials` / `~/.aws/config` files (`AWS_PROFILE`)
5. the container credentials endpoint (Amazon ECS)
6. the EC2 instance metadata service

JVM system properties (source 1) reach only the Fess web process. Content-chunk (document) embedding
runs in a separate child JVM, so for it use environment variables, a shared credentials/config
profile or an IAM role - or repeat the `-Daws.*` options in `jvm.chunk.options`.

The region always comes from the `region` property; `AWS_REGION` is not read. Profiles that use
IAM Identity Center (SSO) are not supported, and there are no access-key properties.

Prefer an IAM role (EC2 instance profile, ECS task role, EKS web identity) or the shared
credentials file of the user that runs Fess. Environment variables and JVM system properties are
listed unmasked under System Info in the admin UI, so keep long-lived keys out of them.

Credential lookups that call AWS endpoints (STS for web identity, the container credentials
endpoint, the instance metadata service) do not go through the `http.proxy.*` settings; requests to
Bedrock do. When neither an API key nor credentials are available, the availability check
remembers the failed lookup for 60 seconds instead of probing the instance metadata service every
time.

The IAM principal needs `bedrock:InvokeModel` (Converse, InvokeModel) and
`bedrock:InvokeModelWithResponseStream` (ConverseStream) on the models it calls. For an inference
profile, allow the profile and the foundation models it routes to.

> **Debug logging.** The plugin's own DEBUG output never contains the API key, the AWS credentials
> or the request signature. Two other loggers do, at DEBUG: Apache HttpClient's wire logging writes
> the `Authorization` header, and the AWS SDK's SigV4 signer
> (`software.amazon.awssdk.http.auth.aws.internal.signer`) writes the canonical request, which
> includes the `x-amz-security-token` of temporary credentials. Starting Fess with the root logger
> at DEBUG (for example `FESS_LOG_LEVEL=debug`) enables both; keep `org.apache.hc` and
> `software.amazon.awssdk` at INFO or above when you raise other loggers.

### Retries

HTTP `429`, `500`, `502`, `503` and `504` are retried with exponential backoff (+/-20% jitter);
a `Retry-After` header in seconds takes precedence. A connection that could not be established
is retried as well; any other I/O failure is not, because the request may already have reached
Bedrock. A streamed answer is never retried once its body has started: an exception event inside
the stream, a dropped connection, or a stream that ends without its `messageStop` event fails the
request.

## Bedrock API Endpoints Used

- `POST /model/{modelId}/converse` - chat completion
- `POST /model/{modelId}/converse-stream` - streaming chat completion (`application/vnd.amazon.eventstream`)
- `POST /model/{modelId}/invoke` - embeddings

No request is made to check availability: the client is available when its configuration is
valid and either an API key is set or AWS credentials can be resolved.

## Development

### Building from Source

```bash
mvn clean package
```

The jar bundles the parts of the AWS SDK for Java 2.x it uses (credentials and SigV4 signing),
unrelocated and at the same version as `fess-storage-s3`.

### Running Tests

```bash
mvn test
```

The tests run against a local mock server; they make no AWS calls.

## License

Apache License 2.0
