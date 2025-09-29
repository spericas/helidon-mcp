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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Supplier;

/**
 * MCP Suppliers.
 */
public class McpSuppliers {

    public static abstract class McpSupplier<T> implements Supplier<T> {
        private final Class<T> type;

        @SuppressWarnings("unchecked")
        public McpSupplier() {
            Type superClass = getClass().getGenericSuperclass();
            if (superClass instanceof ParameterizedType ptype) {
                Type[] actualTypeArguments = ptype.getActualTypeArguments();
                if (actualTypeArguments.length > 0 && actualTypeArguments[0] instanceof Class) {
                    this.type = (Class<T>) actualTypeArguments[0];
                    return;
                }
            }
            throw new IllegalStateException("Cannot determine type parameter");
        }

        public Class<T> type() {
            return type;
        }
    }

    public static abstract class McpToolSupplier extends McpSupplier<McpTool> {

        public abstract String name();
    }
}

