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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * MCP tool annotations.
 */
@Prototype.Blueprint
interface McpToolAnnotationsBlueprint {

    /**
     * Annotation title for the tool.
     *
     * @return the title
     */
    @Option.Default("")
    String title();

    /**
     * If true, the tool does not modify its environment.
     *
     * @return the hint
     */
    @Option.DefaultBoolean(false)
    boolean readOnlyHint();

    /**
     * If true, the tool may perform destructive updates to its environment.
     * If false, the tool performs only additive updates. This property is
     * meaningful only when {@link #readOnlyHint()} is false.
     *
     * @return the hint
     */
    @Option.DefaultBoolean(true)
    boolean destructiveHint();

    /**
     * If true, calling the tool repeatedly with the same arguments
     * will have no additional effect on its environment. This property
     * is meaningful only when {@link #readOnlyHint()} is false.
     *
     * @return the hint
     */
    @Option.DefaultBoolean(false)
    boolean idempotentHint();

    /**
     * If true, this tool may interact with an  open world of external
     * entities. If false, the tool's domain of interaction is closed.
     * For example, the world of a web search tool is open, whereas that
     * of a memory tool is not.
     *
     * @return the hint
     */
    @Option.DefaultBoolean(true)
    boolean openWorldHint();
}
