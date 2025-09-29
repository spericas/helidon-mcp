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

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

class McpSupplierTest {

    @Test
    void testSupplier() {
        McpSuppliers.McpSupplier<MyTool> supplier = new McpSuppliers.McpSupplier<>() {
            @Override
            public MyTool get() {
                return new MyTool();
            }
        };
        assertThat(supplier.type(), equalTo(MyTool.class));
        assertThat(supplier.get(), instanceOf(MyTool.class));
    }

    static class MyTool implements McpTool {
        @Override
        public String name() {
            return "mytool";
        }

        @Override
        public String description() {
            return "My Tool";
        }

        @Override
        public String schema() {
            return "";
        }

        @Override
        public Function<McpRequest, List<McpToolContent>> tool() {
            return null;
        }
    }
}
