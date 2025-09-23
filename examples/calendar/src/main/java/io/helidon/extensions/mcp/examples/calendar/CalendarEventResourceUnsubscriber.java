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

package io.helidon.extensions.mcp.examples.calendar;

import java.util.function.Consumer;

import io.helidon.extensions.mcp.server.McpRequest;
import io.helidon.extensions.mcp.server.McpResourceUnsubscriber;

import static io.helidon.extensions.mcp.examples.calendar.CalendarEventResource.subscribed;

/**
 * Resource unsubscriber for {@link io.helidon.extensions.mcp.examples.calendar.CalendarEventResource}.
 */
final class CalendarEventResourceUnsubscriber implements McpResourceUnsubscriber {

    private final Calendar calendar;

    CalendarEventResourceUnsubscriber(Calendar calendar) {
        this.calendar = calendar;
    }

    @Override
    public String uri() {
        return calendar.uri();
    }

    @Override
    public Consumer<McpRequest> unsubscribe() {
        return request -> subscribed(request).set(false);
    }
}
