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
package io.helidon.extensions.mcp.server;

/**
 * MCP completion reference type.
 */
public enum McpCompletionType {

    /**
     * A prompt completion type.
     */
    PROMPT("ref/prompt"),

    /**
     * A resource completion type.
     */
    RESOURCE("ref/resource");

    private final String type;

    McpCompletionType(String type) {
        this.type = type;
    }

    static McpCompletionType fromString(String type) {
        for (McpCompletionType b : McpCompletionType.values()) {
            if (b.type.equals(type)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Invalid completion reference type " + type);
    }
}
