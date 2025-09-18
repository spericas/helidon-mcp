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

import java.util.function.Function;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of an MCP Completion.
 */
@Prototype.Blueprint
interface McpCompletionBlueprint {

    /**
     * MCP completion reference type.
     */
    enum ReferenceType {
        PROMPT("ref/prompt"),
        RESOURCE("ref/resource");

        final String type;

        ReferenceType(String type) {
            this.type = type;
        }

        String type() {
            return type;
        }

        static ReferenceType fromString(String type) {
            for (ReferenceType b : ReferenceType.values()) {
                if (b.type.equals(type)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("Invalid completion reference type " + type);
        }
    }

    /**
     * Completion reference must be a {@link McpPromptArgument} name or a {@link McpResource} uri template.
     *
     * @return completion reference
     */
    String reference();

    /**
     * The reference type of this completion.
     *
     * @return reference type
     */
    @Option.Default("ref/prompt")
    default ReferenceType referenceType() {
        return ReferenceType.PROMPT;
    }

    /**
     * Complete the client argument accessible from parameters. The returned {@link McpCompletionContent}
     * can be instantiated using the {@link McpCompletionContents} factory.
     *
     * @return completion suggestion
     */
    Function<McpRequest, McpCompletionContent> completion();
}
