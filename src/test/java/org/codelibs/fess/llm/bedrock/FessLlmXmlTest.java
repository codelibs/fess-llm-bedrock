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
package org.codelibs.fess.llm.bedrock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.codelibs.fess.unit.UnitFessTestCase;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Static check of {@code fess_llm++.xml}. The plugin's tests never load it into a container, and a
 * property without a matching setter stops Fess from starting, so every property name is checked
 * against a public {@code set<Name>(String)} here.
 */
public class FessLlmXmlTest extends UnitFessTestCase {

    private Document load() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = FessLlmXmlTest.class.getClassLoader().getResourceAsStream("fess_llm++.xml")) {
            assertNotNull(in, "fess_llm++.xml must be on the class path");
            return builder.parse(in);
        }
    }

    private static Element component(final Document document, final String name) {
        final NodeList components = document.getElementsByTagName("component");
        for (int i = 0; i < components.getLength(); i++) {
            final Element element = (Element) components.item(i);
            if (name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return null;
    }

    private static List<String> childValues(final Element component, final String tag, final String attribute) {
        final List<String> values = new ArrayList<>();
        final NodeList nodes = component.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(((Element) nodes.item(i)).getAttribute(attribute));
        }
        return values;
    }

    @Test
    public void test_llmComponent() throws Exception {
        final Element llm = component(load(), "bedrockLlmClient");
        assertNotNull(llm, "bedrockLlmClient must be declared");
        assertEquals(BedrockLlmClient.class.getName(), llm.getAttribute("class"));
        assertEquals(List.of("register", "init"), childValues(llm, "postConstruct", "name"));
        assertEquals(List.of("destroy"), childValues(llm, "preDestroy", "name"));
        final List<String> properties = childValues(llm, "property", "name");
        assertEquals(16, properties.size());
        for (final String property : properties) {
            final String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            assertNotNull(BedrockLlmClient.class.getMethod(setter, String.class), setter);
        }
    }

    @Test
    public void test_embeddingComponent() throws Exception {
        final Element embedding = component(load(), "bedrockEmbeddingClient");
        assertNotNull(embedding, "bedrockEmbeddingClient must be declared");
        assertEquals("org.codelibs.fess.embedding.bedrock.BedrockEmbeddingClient", embedding.getAttribute("class"));
        assertEquals(List.of("register", "init"), childValues(embedding, "postConstruct", "name"));
        assertEquals(List.of("destroy"), childValues(embedding, "preDestroy", "name"));
        assertTrue(childValues(embedding, "property", "name").isEmpty());
    }
}
