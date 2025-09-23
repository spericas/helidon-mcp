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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.helidon.faulttolerance.Async;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

/**
 * It's not clear that the client API supports resource update notifications, but it
 * does execute a resource read every time a notification is received. This test
 * verifies that 3 resource reads are executed based on 3 resource update notifications
 * received by the server.
 */
@ServerTest
class McpSdkStreamableSubscriptionTest extends AbstractMcpSdkTest {
    private static final CountDownLatch LATCH = new CountDownLatch(3);    // number of expected resources reads

    private final McpSyncClient client;

    McpSdkStreamableSubscriptionTest(WebServer server) {
        client = McpClient.sync(streamable(server.port())).build();
        client.initialize();
    }

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        MultipleResource.setUpRoute(builder, LATCH);
    }

    @Override
    McpSyncClient client() {
        return client;
    }

    @Test
    void testSubscription() throws InterruptedException {
        Async.create().invoke(() -> {
            client().subscribeResource(new McpSchema.SubscribeRequest("http://myresource"));
            return null;
        });
        assertThat(LATCH.await(10, TimeUnit.SECONDS), is(true));
    }
}
