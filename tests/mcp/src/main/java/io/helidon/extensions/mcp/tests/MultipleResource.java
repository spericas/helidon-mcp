/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.extensions.mcp.tests;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.mcp.server.McpRequest;
import io.helidon.extensions.mcp.server.McpResource;
import io.helidon.extensions.mcp.server.McpResourceContent;
import io.helidon.extensions.mcp.server.McpResourceContents;
import io.helidon.extensions.mcp.server.McpResourceSubscriber;
import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.webserver.http.HttpRouting;

import static io.helidon.extensions.mcp.server.McpResourceContents.binaryContent;
import static io.helidon.extensions.mcp.server.McpResourceContents.textContent;

class MultipleResource {
    private MultipleResource() {
    }

    static void setUpRoute(HttpRouting.Builder builder) {
        setUpRoute(builder, null);
    }

    static void setUpRoute(HttpRouting.Builder builder, CountDownLatch latch) {
        MyResource myResource = new MyResource(latch);
        builder.addFeature(McpServerFeature.builder()
                                   .path("/")
                                   .addResource(resource -> resource
                                           .name("resource1")
                                           .description("Resource 1")
                                           .uri("http://resource1")
                                           .mediaType(MediaTypes.TEXT_PLAIN)
                                           .resource(param -> List.of(textContent("text"))))

                                   .addResource(resource -> resource
                                           .name("resource2")
                                           .description("Resource 2")
                                           .uri("http://resource2")
                                           .mediaType(MediaTypes.APPLICATION_JSON)
                                           .resource(param -> List.of(
                                                   binaryContent("binary".getBytes(StandardCharsets.UTF_8),
                                                                 MediaTypes.APPLICATION_JSON))))

                                   .addResource(myResource)
                                   .addResourceSubscriber(new MyResourceSubscriber(myResource)));
    }

    private static final class MyResource implements McpResource {

        private final CountDownLatch latch;

        MyResource(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public String uri() {
            return "http://myresource";
        }

        @Override
        public String name() {
            return "myresource";
        }

        @Override
        public String description() {
            return "My resource";
        }

        @Override
        public MediaType mediaType() {
            return MediaTypes.APPLICATION_OCTET_STREAM;
        }

        @Override
        public Function<McpRequest, List<McpResourceContent>> resource() {
            return this::read;
        }

        List<McpResourceContent> read(McpRequest request) {
            if (latch != null) {
                latch.countDown();      // count number of reads
            }
            return List.of(McpResourceContents.textContent("text"),
                           McpResourceContents.binaryContent("binary".getBytes(StandardCharsets.UTF_8),
                                                             MediaTypes.APPLICATION_JSON));
        }
    }

    private static final class MyResourceSubscriber implements McpResourceSubscriber {

        private final MyResource resource;

        MyResourceSubscriber(MyResource resource) {
            this.resource = resource;
        }

        @Override
        public String uri() {
            return resource.uri();
        }

        @Override
        public Consumer<McpRequest> subscribe() {
            return request -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        request.features().updateSubscription(resource.uri());
                        Thread.sleep(100);      // simulate delay
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        }
    }
}
