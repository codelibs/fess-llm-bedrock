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
package org.codelibs.fess.unit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Log4j2 appender that captures a target class's log events so a test can assert on them.
 *
 * <p>Shared by the LLM-client and embedding-client test suites: both assert that a configured
 * credential never reaches a log line, and a second copy of the capture machinery is a second place
 * for that assertion's foundation to drift.
 */
public final class LogCapturingAppender extends AbstractAppender {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private final Logger boundLogger;

    private LogCapturingAppender(final Logger logger) {
        super("LogCapturingAppender-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
        this.boundLogger = logger;
    }

    /**
     * Starts capturing events logged by {@code targetClass}'s logger.
     *
     * @param targetClass the class whose logger should be captured.
     * @return the started, attached appender; call {@link #detach()} when done.
     */
    public static LogCapturingAppender attach(final Class<?> targetClass) {
        final Logger logger = (Logger) LogManager.getLogger(targetClass);
        final LogCapturingAppender appender = new LogCapturingAppender(logger);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Stops capturing and removes this appender from the logger it was attached to. */
    public void detach() {
        boundLogger.removeAppender(this);
        stop();
    }

    @Override
    public void append(final LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * Returns the formatted messages captured at {@code level}.
     *
     * @param level the level to collect.
     * @return one entry per event, in order.
     */
    public List<String> messagesAt(final Level level) {
        return events.stream().filter(e -> e.getLevel() == level).map(e -> e.getMessage().getFormattedMessage()).toList();
    }

    /**
     * Like {@link #messagesAt(Level)} but appends the rendered stack trace of any attached
     * throwable. {@link #messagesAt(Level)} alone cannot see a value that reaches the log file only
     * through a throwable, so an assertion built on it goes green while the rendered log still
     * leaks.
     *
     * @param level the level to collect.
     * @return one entry per event: formatted message plus any throwable's stack trace.
     */
    public List<String> renderedAt(final Level level) {
        return events.stream().filter(e -> e.getLevel() == level).map(e -> {
            final String message = e.getMessage().getFormattedMessage();
            final Throwable thrown = e.getThrown();
            if (thrown == null) {
                return message;
            }
            return message + System.lineSeparator() + renderThrowable(thrown);
        }).toList();
    }

    /**
     * Shorthand for {@code renderedAt(Level.WARN)}.
     *
     * @return the rendered WARN events.
     */
    public List<String> renderedWarnings() {
        return renderedAt(Level.WARN);
    }

    /**
     * Shorthand for {@code messagesAt(Level.WARN)}.
     *
     * @return the WARN messages.
     */
    public List<String> warnings() {
        return messagesAt(Level.WARN);
    }

    /**
     * Shorthand for {@code messagesAt(Level.INFO)}.
     *
     * @return the INFO messages.
     */
    public List<String> infos() {
        return messagesAt(Level.INFO);
    }

    /**
     * Shorthand for {@code messagesAt(Level.ERROR)}.
     *
     * @return the ERROR messages.
     */
    public List<String> errors() {
        return messagesAt(Level.ERROR);
    }

    /**
     * Shorthand for {@code messagesAt(Level.DEBUG)}.
     *
     * @return the DEBUG messages.
     */
    public List<String> debugs() {
        return messagesAt(Level.DEBUG);
    }

    /**
     * Renders a throwable's full stack trace, including its cause chain, as a string. Used by
     * assertions that a credential appears nowhere in what a caller can observe.
     *
     * @param t the throwable to render.
     * @return the rendered stack trace.
     */
    public static String renderThrowable(final Throwable t) {
        final StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
