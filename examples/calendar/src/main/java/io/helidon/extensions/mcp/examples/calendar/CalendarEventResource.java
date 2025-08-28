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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.mcp.server.McpException;
import io.helidon.extensions.mcp.server.McpRequest;
import io.helidon.extensions.mcp.server.McpResource;
import io.helidon.extensions.mcp.server.McpResourceContent;
import io.helidon.extensions.mcp.server.McpResourceContents;

/**
 * Resource that represent a calendar event registry.
 */
final class CalendarEventResource implements McpResource {
    private static final Path EVENTS_FILE = createEventsFile();

    @Override
    public String uri() {
        return "file://calendar/events";
    }

    @Override
    public String name() {
        return "calendar-events";
    }

    @Override
    public String description() {
        return "List of calendar events created";
    }

    @Override
    public MediaType mediaType() {
        return MediaTypes.TEXT_PLAIN;
    }

    @Override
    public Function<McpRequest, List<McpResourceContent>> resource() {
        return this::readRegistry;
    }

    static Path eventsFile() {
        return EVENTS_FILE;
    }

    private List<McpResourceContent> readRegistry(McpRequest request) {
        try {
            request.features().logger().info("Reading calendar events from registry...");
            var content = Files.readString(EVENTS_FILE);
            return List.of(McpResourceContents.textContent(content));
        } catch (IOException e) {
            throw new McpException("Error when reading calendar event registry", e);
        }
    }

    private static Path createEventsFile() {
        try {
            return Files.createTempFile("calendar", "events");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
