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
import java.util.ArrayList;
import java.util.List;

import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;

import software.amazon.eventstream.Message;

public class EventStreamReaderTest extends UnitFessTestCase {

    @Test
    public void test_next_readsFramesInOrderThenNull() throws IOException {
        final byte[] body = new EventStreamFrames().text("Hello").text(", world").stop("end_turn").toByteArray();
        final List<Message> frames = readAll(new EventStreamReader(new ByteArrayInputStream(body)));
        assertEquals(5, frames.size());
        assertEquals("contentBlockDelta", EventStreamReader.header(frames.get(0), EventStreamReader.EVENT_TYPE));
        assertEquals("event", EventStreamReader.header(frames.get(0), EventStreamReader.MESSAGE_TYPE));
        assertTrue(new String(frames.get(1).getPayload(), StandardCharsets.UTF_8).contains(", world"));
        assertEquals("messageStop", EventStreamReader.header(frames.get(3), EventStreamReader.EVENT_TYPE));
    }

    @Test
    public void test_next_reassemblesFramesSplitAcrossReads() throws IOException {
        // Multi-byte UTF-8 text inside frames that arrive three bytes at a time: no frame may be
        // handed out before it is complete, and none may be lost at a read boundary.
        final byte[] body = new EventStreamFrames().text("日本語の回答").text("です。").stop("end_turn").toByteArray();
        final InputStream trickle = new ByteArrayInputStream(body) {
            @Override
            public synchronized int read(final byte[] b, final int off, final int len) {
                return super.read(b, off, Math.min(3, len));
            }
        };
        final List<Message> frames = readAll(new EventStreamReader(trickle, 4096));
        assertEquals(5, frames.size());
        assertTrue(new String(frames.get(0).getPayload(), StandardCharsets.UTF_8).contains("日本語の回答"));
    }

    @Test
    public void test_next_corruptFrameIsMalformedFrameException() throws IOException {
        final byte[] body = new EventStreamFrames().text("Hello").toByteArray();
        body[body.length - 8] ^= 0x55;
        try {
            readAll(new EventStreamReader(new ByteArrayInputStream(body)));
            fail("expected MalformedFrameException");
        } catch (final EventStreamReader.MalformedFrameException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Malformed event stream frame"));
        }
    }

    @Test
    public void test_next_truncatedTrailingFrameIsIgnored() throws IOException {
        final byte[] whole = new EventStreamFrames().text("Hello").text("cut").toByteArray();
        final int firstFrameLength = new EventStreamFrames().text("Hello").toByteArray().length;
        final byte[] cut = java.util.Arrays.copyOf(whole, firstFrameLength + 10);
        final List<Message> frames = readAll(new EventStreamReader(new ByteArrayInputStream(cut)));
        assertEquals(1, frames.size());
    }

    @Test
    public void test_header_absentOrNonString() {
        final Message message =
                new Message(java.util.Map.of(":flag", software.amazon.eventstream.HeaderValue.fromBoolean(true)), new byte[0]);
        assertNull(EventStreamReader.header(message, ":event-type"));
        assertNull(EventStreamReader.header(message, ":flag"));
    }

    private static List<Message> readAll(final EventStreamReader reader) throws IOException {
        final List<Message> frames = new ArrayList<>();
        Message frame;
        while ((frame = reader.next()) != null) {
            frames.add(frame);
        }
        return frames;
    }
}
