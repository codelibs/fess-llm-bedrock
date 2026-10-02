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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

import software.amazon.eventstream.HeaderValue;
import software.amazon.eventstream.Message;
import software.amazon.eventstream.MessageDecoder;

/**
 * Reads {@code application/vnd.amazon.eventstream} frames from an {@link InputStream}, the
 * encoding of a ConverseStream response body, using AWS's own decoder.
 *
 * <p>Frames may arrive split across reads; the decoder buffers partial frames and this reader hands
 * out each frame once it is complete. A frame whose checksum does not match is reported as an
 * {@link MalformedFrameException}. Bytes left over after the last complete frame at end of stream are ignored:
 * a stream cut inside a frame never delivers its {@code messageStop}, and the caller reports that.
 */
public class EventStreamReader {

    /** Header naming the frame kind: {@code event}, {@code exception} or {@code error}. */
    public static final String MESSAGE_TYPE = ":message-type";

    /** Header naming the event of an {@code event} frame, e.g. {@code contentBlockDelta}. */
    public static final String EVENT_TYPE = ":event-type";

    /** Header naming the exception of an {@code exception} frame, e.g. {@code throttlingException}. */
    public static final String EXCEPTION_TYPE = ":exception-type";

    /** Header carrying the code of an {@code error} frame. */
    public static final String ERROR_CODE = ":error-code";

    /** Header carrying the message of an {@code error} frame. */
    public static final String ERROR_MESSAGE = ":error-message";

    private final InputStream in;

    private final byte[] buffer;

    private final MessageDecoder decoder = new MessageDecoder();

    private final Deque<Message> pending = new ArrayDeque<>();

    /**
     * Creates a reader with an 8 KiB read buffer.
     *
     * @param in the response body.
     */
    public EventStreamReader(final InputStream in) {
        this(in, 8192);
    }

    /**
     * Creates a reader with the given read buffer size.
     *
     * @param in the response body.
     * @param bufferSize bytes requested per read.
     */
    public EventStreamReader(final InputStream in, final int bufferSize) {
        this.in = in;
        this.buffer = new byte[bufferSize];
    }

    /**
     * Returns the next complete frame.
     *
     * @return the frame, or {@code null} at end of stream.
     * @throws MalformedFrameException when a frame is corrupt.
     * @throws IOException when reading fails.
     */
    public Message next() throws IOException {
        while (pending.isEmpty()) {
            final int read = in.read(buffer);
            if (read < 0) {
                return null;
            }
            try {
                decoder.feed(buffer, 0, read);
            } catch (final RuntimeException e) {
                throw new MalformedFrameException("Malformed event stream frame: " + e.getMessage(), e);
            }
            pending.addAll(decoder.getDecodedMessages());
        }
        return pending.poll();
    }

    /**
     * Returns a string header of a frame.
     *
     * @param message the frame.
     * @param name the header name.
     * @return the header value, or {@code null} when absent or not a string.
     */
    public static String header(final Message message, final String name) {
        final HeaderValue value = message.getHeaders().get(name);
        if (value == null) {
            return null;
        }
        try {
            return value.getString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A frame whose prelude or message checksum does not match: the body is not a valid event
     * stream, as opposed to a connection that failed while it was being read.
     */
    public static class MalformedFrameException extends IOException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the decoder's description.
         * @param cause the decoder's exception.
         */
        public MalformedFrameException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
