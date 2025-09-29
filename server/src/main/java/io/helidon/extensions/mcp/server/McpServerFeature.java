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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.mapper.OptionalValue;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.http.ServerRequestHeaders;
import io.helidon.http.Status;
import io.helidon.http.sse.SseEvent;
import io.helidon.jsonrpc.core.JsonRpcError;
import io.helidon.jsonrpc.core.JsonRpcParams;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRequest;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.jsonrpc.JsonRpcHandlers;
import io.helidon.webserver.jsonrpc.JsonRpcRequest;
import io.helidon.webserver.jsonrpc.JsonRpcResponse;
import io.helidon.webserver.jsonrpc.JsonRpcRouting;
import io.helidon.webserver.sse.SseSink;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_COMPLETION_COMPLETE;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_INITIALIZE;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_LOGGING_SET_LEVEL;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_NOTIFICATION_CANCELED;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_NOTIFICATION_INITIALIZED;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_PING;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_PROMPT_GET;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_PROMPT_LIST;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_RESOURCES_LIST;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_RESOURCES_READ;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_RESOURCES_SUBSCRIBE;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_RESOURCES_TEMPLATES_LIST;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_RESOURCES_UNSUBSCRIBE;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_SESSION_DISCONNECT;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_TOOLS_CALL;
import static io.helidon.extensions.mcp.server.McpJsonRpc.METHOD_TOOLS_LIST;
import static io.helidon.extensions.mcp.server.McpJsonRpc.isResponse;
import static io.helidon.extensions.mcp.server.McpJsonRpc.listPrompts;
import static io.helidon.extensions.mcp.server.McpJsonRpc.listResourceTemplates;
import static io.helidon.extensions.mcp.server.McpJsonRpc.listResources;
import static io.helidon.extensions.mcp.server.McpJsonRpc.listTools;
import static io.helidon.extensions.mcp.server.McpJsonRpc.readResource;
import static io.helidon.extensions.mcp.server.McpJsonRpc.toJson;
import static io.helidon.extensions.mcp.server.McpJsonRpc.toolCall;
import static io.helidon.extensions.mcp.server.McpSession.State.INITIALIZING;
import static io.helidon.extensions.mcp.server.McpSession.State.UNINITIALIZED;
import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_PARAMS;
import static io.helidon.jsonrpc.core.JsonRpcError.INVALID_REQUEST;

/**
 * Mcp http feature is the actual MCP server as a Helidon {@link io.helidon.webserver.http.HttpFeature}.
 */
@RuntimeType.PrototypedBy(McpServerConfig.class)
public final class McpServerFeature implements HttpFeature, RuntimeType.Api<McpServerConfig> {
    private static final int SESSION_CACHE_SIZE = 1000;
    private static final List<String> PROTOCOL_VERSION = List.of("2024-11-05", "2025-03-26");
    private static final HeaderName SESSION_ID_HEADER = HeaderNames.create("Mcp-Session-Id");
    private static final Logger LOGGER = Logger.getLogger(McpServerFeature.class.getName());

    private final String endpoint;
    private final McpServerConfig config;
    private final JsonRpcHandlers jsonRpcHandlers;
    private final McpPagination<McpTool> tools;
    private final McpPagination<McpPrompt> prompts;
    private final McpPagination<McpResource> resources;
    private final McpPagination<McpResourceTemplate> resourceTemplates;
    private final Set<McpCapability> capabilities = new HashSet<>();
    private final McpSessions sessions = new McpSessions(SESSION_CACHE_SIZE);
    private final Map<String, McpCompletion> promptCompletions = new ConcurrentHashMap<>();
    private final Map<String, McpCompletion> resourceCompletions = new ConcurrentHashMap<>();

    private McpServerFeature(McpServerConfig config) {
        String path = config.path();
        List<McpTool> tools = new CopyOnWriteArrayList<>(config.tools());
        List<McpPrompt> prompts = new CopyOnWriteArrayList<>(config.prompts());
        List<McpResource> resources = new CopyOnWriteArrayList<>();
        List<McpResourceTemplate> templates = new CopyOnWriteArrayList<>();
        JsonRpcHandlers.Builder builder = JsonRpcHandlers.builder();

        // process tool suppliers, add instance if only supplier provided
        processToolSuppliers(config, tools);

        this.config = config;
        this.endpoint = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (McpResource resource : config.resources()) {
            if (isTemplate(resource)) {
                templates.add(new McpResourceTemplate(resource));
            } else {
                resources.add(resource);
            }
        }
        for (McpCompletion completion : config.completions()) {
            switch (completion.referenceType()) {
            case PROMPT -> promptCompletions.put(completion.reference(), completion);
            case RESOURCE -> resourceCompletions.put(completion.reference(), completion);
            default -> throw new IllegalStateException("Unknown reference type: " + completion.referenceType());
            }
        }

        this.tools = new McpPagination<>(tools, config.toolsPageSize());
        this.prompts = new McpPagination<>(prompts, config.promptsPageSize());
        this.resources = new McpPagination<>(resources, config.resourcesPageSize());
        this.resourceTemplates = new McpPagination<>(templates, config.resourceTemplatesPageSize());

        builder.method(METHOD_PING, this::pingRpc);
        builder.method(METHOD_INITIALIZE, this::initializeRpc);

        if (!config.tools().isEmpty()) {
            capabilities.add(McpCapability.TOOL_LIST_CHANGED);
            builder.method(METHOD_TOOLS_LIST, this::toolsListRpc);
            builder.method(METHOD_TOOLS_CALL, this::toolsCallRpc);
        }

        if (!config.resources().isEmpty()) {
            capabilities.add(McpCapability.RESOURCE_LIST_CHANGED);
            capabilities.add(McpCapability.RESOURCE_SUBSCRIBE);
            builder.method(METHOD_RESOURCES_LIST, this::resourcesListRpc);
            builder.method(METHOD_RESOURCES_READ, this::resourcesReadRpc);
            builder.method(METHOD_RESOURCES_SUBSCRIBE, this::resourceSubscribeRpc);
            builder.method(METHOD_RESOURCES_UNSUBSCRIBE, this::resourceUnsubscribeRpc);
            builder.method(METHOD_RESOURCES_TEMPLATES_LIST, this::resourceTemplateListRpc);
        }

        if (!config.prompts().isEmpty()) {
            capabilities.add(McpCapability.PROMPT_LIST_CHANGED);
            builder.method(METHOD_PROMPT_LIST, this::promptsListRpc);
            builder.method(METHOD_PROMPT_GET, this::promptsGetRpc);
        }

        capabilities.add(McpCapability.LOGGING);
        builder.method(METHOD_LOGGING_SET_LEVEL, this::loggingLogLevelRpc);

        capabilities.add(McpCapability.COMPLETION);
        builder.method(METHOD_COMPLETION_COMPLETE, this::completionRpc);

        builder.method(METHOD_SESSION_DISCONNECT, this::disconnect);
        builder.method(METHOD_NOTIFICATION_CANCELED, this::notificationCancelRpc);
        builder.method(METHOD_NOTIFICATION_INITIALIZED, this::notificationInitRpc);

        builder.errorHandler(this::handleErrorRequest);

        jsonRpcHandlers = builder.build();
    }

    static McpServerFeature create(McpServerConfig config) {
        return new McpServerFeature(config);
    }

    static McpServerFeature create(Consumer<McpServerConfig.Builder> consumer) {
        McpServerConfig.Builder builder = McpServerConfig.builder();
        consumer.accept(builder);
        return builder.build();
    }

    /**
     * Create a server configuration builder instance.
     *
     * @return McpServer builder
     */
    public static McpServerConfig.Builder builder() {
        return McpServerConfig.builder();
    }

    @Override
    public void setup(HttpRouting.Builder routing) {
        // add all the JSON-RPC routes first
        JsonRpcRouting jsonRpcRouting = JsonRpcRouting.builder()
                .register(endpoint + "/message", jsonRpcHandlers)
                .register(endpoint, jsonRpcHandlers)        // streamable HTTP
                .build();
        jsonRpcRouting.routing(routing);

        // additional HTTP routes for SSE and session disconnect
        routing.get(endpoint, this::sse)
                .delete(endpoint, this::disconnect);
    }

    @Override
    public McpServerConfig prototype() {
        return config;
    }

    /**
     * Checks if using SSE or streamable HTTP.
     *
     * @param headers the request headers
     * @return outcome of test
     */
    static boolean isStreamableHttp(ServerRequestHeaders headers) {
        return headers.contains(SESSION_ID_HEADER);
    }

    private void disconnect(ServerRequest request, ServerResponse response) {
        disconnectSession(request, response);
        if (isStreamableHttp(request.headers())) {
            response.status(Status.ACCEPTED_202).send();
        }
    }

    private void disconnect(JsonRpcRequest request, JsonRpcResponse response) {
        disconnectSession(request, response);
    }

    private void disconnectSession(HttpRequest request, ServerResponse response) {
        Optional<McpSession> foundSession = findSession(request);
        if (foundSession.isEmpty()) {
            response.status(Status.NOT_FOUND_404).send();
            return;
        }
        foundSession.get().disconnect();
    }

    private void sse(ServerRequest request, ServerResponse response) {
        if (isStreamableHttp(request.headers())) {
            Optional<McpSession> session = findSession(request);
            if (session.isEmpty()) {
                response.status(Status.NOT_FOUND_404).send();
                return;
            }
            // streamable HTTP and active session
            response.status(Status.METHOD_NOT_ALLOWED_405).send();
        } else {
            String sessionId = UUID.randomUUID().toString();
            McpSession session = new McpSession(sessions, capabilities, config);
            sessions.put(sessionId, session);

            try (SseSink sink = response.sink(SseSink.TYPE)) {
                sink.emit(SseEvent.builder()
                                  .name("endpoint")
                                  .data(endpoint + "/message?sessionId=" + sessionId)
                                  .build());
                session.poll(message -> sink.emit(SseEvent.builder()
                                                          .name("message")
                                                          .data(message)
                                                          .build()));
            } catch (McpException e) {
                sessions.remove(sessionId);
            }
        }
    }

    /**
     * Finds session by either looking for header (streamable HTTP) or query
     * param (SSE).
     *
     * @param req the request
     * @return the optional session
     */
    private Optional<McpSession> findSession(HttpRequest req) {
        try {
            String sessionId;
            if (req.headers().contains(SESSION_ID_HEADER)) {
                sessionId = req.headers().get(SESSION_ID_HEADER).values();
            } else {
                sessionId = req.query().get("sessionId");
            }
            return sessions.get(sessionId);
        } catch (NoSuchElementException e) {
            return Optional.empty();
        }
    }

    /**
     * If we receive what looks like a response on the error handler,
     * pass it to the session.
     *
     * @param req    the HTTP request
     * @param object the invalid JSON-RPC request
     * @return whether error was handled or not
     */
    private Optional<JsonRpcError> handleErrorRequest(ServerRequest req, JsonObject object) {
        if (isResponse(object)) {
            Optional<McpSession> session = findSession(req);
            if (session.isPresent()) {
                session.get().send(object);
                return Optional.empty();
            }
        }
        return Optional.of(JsonRpcError.create(INVALID_REQUEST, "Invalid request"));
    }

    private void notificationInitRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> session = findSession(req);
        if (session.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        session.get().state(McpSession.State.INITIALIZED);
    }

    private void notificationCancelRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            LOGGER.log(Level.FINEST, () -> "No session found for cancellation request: %s".formatted(req.asJsonObject()));
            return;
        }
        McpSession session = foundSession.get();
        Optional<JsonValue> reason = req.params().find("reason");
        Optional<JsonValue> requestId = req.params().find("requestId");
        // Ignore malformed request
        if (requestId.isEmpty()
                || reason.isEmpty()
                || !JsonValue.ValueType.STRING.equals(reason.get().getValueType())) {
            LOGGER.log(Level.FINEST, () -> "Malformed cancellation request: %s".formatted(req.asJsonObject()));
            return;
        }
        String cancelReason = ((JsonString) reason.get()).getString();
        session.features(requestId.get()).ifPresent(feature -> feature.cancellation().cancel(cancelReason, requestId.get()));
    }

    private void initializeRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);

        // is this streamable HTTP?
        if (foundSession.isEmpty()) {
            // create a new session
            String sessionId = UUID.randomUUID().toString();
            McpSession session = new McpSession(sessions, config);
            sessions.put(sessionId, session);

            // parse capabilities and update response
            McpParameters params = new McpParameters(req.params(), req.params().asJsonObject());
            parseClientCapabilities(session, params);
            if (session.state() == UNINITIALIZED) {
                session.state(INITIALIZING);
            }
            String protocolVersion = parseClientVersion(params);
            session.protocolVersion(protocolVersion);
            res.header(SESSION_ID_HEADER, sessionId);
            res.result(toJson(protocolVersion, capabilities, config));
            LOGGER.log(Level.FINEST, () -> String.format("Streamable HTTP: %s", res.asJsonObject()));
            res.send();
        } else {
            McpSession session = foundSession.get();
            McpParameters params = new McpParameters(req.params(), req.params().asJsonObject());
            String protocolVersion = parseClientVersion(params);
            session.protocolVersion(protocolVersion);
            parseClientCapabilities(session, params);
            if (session.state() == UNINITIALIZED) {
                session.state(INITIALIZING);
            }
            res.result(toJson(protocolVersion, capabilities, config));
            LOGGER.log(Level.FINEST, () -> String.format("SSE: %s", res.asJsonObject()));
            session.send(res);
        }
    }

    private String parseClientVersion(McpParameters parameters) {
        McpParameters protocolVersion = parameters.get("protocolVersion");
        if (protocolVersion.isPresent()) {
            String version = protocolVersion.asString().get();
            if (PROTOCOL_VERSION.contains(version)) {
                return version;
            }
        }
        return PROTOCOL_VERSION.getLast();
    }

    private void parseClientCapabilities(McpSession session, McpParameters parameters) {
        var capabilities = parameters.get("capabilities");
        if (capabilities.get(McpCapability.SAMPLING.text()).isPresent()) {
            session.capabilities(McpCapability.SAMPLING);
        }
        capabilities.get("roots")
                .get("listChanged")
                .asBoolean()
                .ifPresent(listChanged -> {
                    if (listChanged) {
                        session.capabilities(McpCapability.ROOT);
                    }
                });
    }

    private void pingRpc(JsonRpcRequest req, JsonRpcResponse res) {
        processSimpleCall(req, res, session -> res.result(JsonValue.EMPTY_JSON_OBJECT));
    }

    private void toolsListRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpPage<McpTool> page = page(tools, req.params());
        if (page == null) {
            res.error(INVALID_PARAMS, "Wrong cursor provided.");
            sendResponse(req, res, session);
            return;
        }
        res.result(listTools(page, session.protocolVersion()));
        sendResponse(req, res, session);
    }

    private void toolsCallRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        McpFeatures features = mcpFeatures(req, res, session, requestId);
        enableProgress(session, parameters, features);

        String name = parameters.get("name").asString().orElse("");
        Optional<McpTool> tool = tools.content().stream()
                .filter(t -> name.equals(t.name()))
                .findFirst();

        if (tool.isEmpty()) {
            res.error(INVALID_PARAMS, "Tool with name %s is not available".formatted(name));
            sendResponse(req, res, session);
            return;
        }
        List<McpToolContent> contents = tool.get()
                .tool()
                .apply(McpRequest.builder()
                               .parameters(parameters.get("arguments"))
                               .features(features)
                               .protocolVersion(session.protocolVersion())
                               .context(session.context())
                               .build());
        features.progress().stopSending();
        res.result(toolCall(contents));
        sendResponse(req, res, session, features, requestId);
    }

    private void resourcesListRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpPage<McpResource> page = page(resources, req.params());
        if (page == null) {
            res.error(INVALID_PARAMS, "Wrong cursor provided.");
            sendResponse(req, res, session);
            return;
        }
        var resourceList = listResources(page);
        res.result(resourceList);
        sendResponse(req, res, session);
    }

    private void resourcesReadRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();

        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        String resourceUri = parameters.get("uri").asString().orElse("");
        Optional<McpResource> resource = resources.content().stream()
                .filter(r -> resourceUri.equals(r.uri()))
                .findFirst();

        // Fall back on resource template processing if resource is not found
        if (resource.isEmpty()) {
            var templates = resourceTemplates.content().stream()
                    .filter(template -> template.matches(resourceUri))
                    .findFirst();

            if (templates.isEmpty()) {
                res.error(INVALID_REQUEST, "Resource does not exist");
                sendResponse(req, res, session);
                return;
            }

            McpResourceTemplate template = templates.get();
            parameters = template.parameters(req.params(), resourceUri);
            resource = templates.map(Function.identity());
        }

        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        McpFeatures features = mcpFeatures(req, res, session, requestId);
        enableProgress(session, parameters, features);
        List<McpResourceContent> contents = resource.get()
                .resource()
                .apply(McpRequest.builder()
                               .parameters(parameters)
                               .features(features)
                               .protocolVersion(session.protocolVersion())
                               .context(session.context())
                               .build());
        features.progress().stopSending();
        res.result(readResource(resourceUri, contents));
        sendResponse(req, res, session, features, requestId);
    }

    private void resourceSubscribeRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        String resourceUri = parameters.get("uri").asString()
                .orElseThrow(() -> new McpException("uri is required"));

        // check if resource exists
        Optional<McpResource> resource = config.resources().stream()
                .filter(r -> resourceUri.equals(r.uri()))
                .findFirst();
        if (resource.isEmpty()) {
            res.error(INVALID_PARAMS, "Unable to find resource");
            sendResponse(req, res, session);
            return;
        }

        // update session with new subscription
        Optional<SseSink> sseSink = session.subscribe(req, res, resourceUri);

        // if SSE, then respond to request since subscriber can block
        if (!isStreamableHttp(req.headers())) {
            res.result(JsonValue.EMPTY_JSON_OBJECT);
            res.status(Status.OK_200).send();
        }

        // if subscriber exists then call it
        Optional<McpResourceSubscriber> subscriber = config.resourceSubscribers().stream()
                .filter(r -> resourceUri.equals(r.uri()))
                .findFirst();
        if (subscriber.isPresent()) {
            // invoke user method for a new subscription that may block thread
            McpFeatures features = mcpFeatures(req, res, session, requestId);
            subscriber.get()
                    .subscribe()
                    .accept(McpRequest.builder()
                                    .parameters(parameters)
                                    .features(features)
                                    .protocolVersion(session.protocolVersion())
                                    .context(session.context())
                                    .build());

            // send final response using active SSE sink
            res.result(JsonValue.EMPTY_JSON_OBJECT);
            sendResponse(req, res, session, requestId, sseSink.orElse(null));
            return;
        }

        // otherwise, we need to block on streamable HTTP
        if (sseSink.isPresent()) {
            try {
                session.blockSubscribe(resourceUri);
            } catch (InterruptedException e) {
                LOGGER.log(Level.FINEST, "Subscriber thread interrupted");
            }

            // send final response after unblocking
            res.result(JsonValue.EMPTY_JSON_OBJECT);
            sendResponse(req, res, session, requestId, sseSink.get());
        }
    }

    private void resourceUnsubscribeRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        String resourceUri = parameters.get("uri").asString()
                .orElseThrow(() -> new McpException("uri is required"));
        McpFeatures features = mcpFeatures(req, res, session, requestId);

        // update session with unsubscription
        Optional<SseSink> sseSink = session.unsubscribe(req, resourceUri);

        // if unsubscriber exists then call it
        Optional<McpResourceUnsubscriber> unsubscriber = config.resourceUnsubscribers().stream()
                .filter(r -> resourceUri.equals(r.uri()))
                .findFirst();
        if (unsubscriber.isPresent()) {
            // invoke user method to unsubscribe
            unsubscriber.get()
                    .unsubscribe()
                    .accept(McpRequest.builder()
                                    .parameters(parameters)
                                    .features(features)
                                    .protocolVersion(session.protocolVersion())
                                    .context(session.context())
                                    .build());

            // response to unsubscription request
            res.result(JsonValue.EMPTY_JSON_OBJECT);
            sendResponse(req, res, session);
            return;
        }

        // otherwise, we need to unblock subscriber with streamable HTTP
        if (sseSink.isPresent()) {
            session.unblockSubscribe(resourceUri);
        }

        // send final response
        res.result(JsonValue.EMPTY_JSON_OBJECT);
        sendResponse(req, res, session, features, requestId);
    }

    private void resourceTemplateListRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpPage<McpResourceTemplate> page = page(resourceTemplates, req.params());
        if (page == null) {
            res.error(INVALID_PARAMS, "Wrong cursor provided.");
            sendResponse(req, res, session);
            return;
        }
        res.result(listResourceTemplates(page));
        sendResponse(req, res, session);
    }

    private void promptsListRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpPage<McpPrompt> page = page(prompts, req.params());
        if (page == null) {
            res.error(INVALID_PARAMS, "Wrong cursor provided.");
            sendResponse(req, res, session);
            return;
        }
        res.result(listPrompts(page));
        sendResponse(req, res, session);
    }

    private void promptsGetRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        McpFeatures features = mcpFeatures(req, res, session, requestId);
        enableProgress(session, parameters, features);

        String name = parameters.get("name").asString().orElse(null);
        if (name == null) {
            res.error(INVALID_REQUEST, "Prompt name is missing from request " + req.id());
            sendResponse(req, res, session);
            return;
        }

        Optional<McpPrompt> prompt = prompts.content().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst();
        if (prompt.isEmpty()) {
            features.progress().stopSending();
            res.error(INVALID_PARAMS, "Wrong prompt name: " + name);
            sendResponse(req, res, session);
            return;
        }

        List<McpPromptContent> contents = prompt.get()
                .prompt()
                .apply(McpRequest.builder()
                               .parameters(parameters.get("arguments"))
                               .features(features)
                               .protocolVersion(session.protocolVersion())
                               .context(session.context())
                               .build());
        features.progress().stopSending();
        res.result(toJson(contents, prompt.get().description()));
        sendResponse(req, res, session, features, requestId);
    }

    private void loggingLogLevelRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }

        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        OptionalValue<String> level = parameters.get("level").asString();
        if (level.isPresent()) {
            try {
                McpLogger.Level logLevel = McpLogger.Level.valueOf(level.get().toUpperCase());
                JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
                McpFeatures features = mcpFeatures(req, res, session, requestId);
                features.logger().setLevel(logLevel);
                res.result(JsonValue.EMPTY_JSON_OBJECT);
                sendResponse(req, res, session, features, requestId);
                return;

            } catch (IllegalArgumentException e) {
                // falls through
            }
        }
        res.error(INVALID_PARAMS, "Invalid log level");
    }

    private void completionRpc(JsonRpcRequest req, JsonRpcResponse res) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        McpParameters parameters = new McpParameters(req.params(), req.params().asJsonObject());
        McpParameters ref = parameters.get("ref");
        String referenceType = ref.get("type").asString().orElse(null);
        JsonValue requestId = req.rpcId().orElseThrow(() -> new McpException("request id is required"));
        McpFeatures features = mcpFeatures(req, res, session, requestId);
        if (referenceType != null) {
            try {
                McpCompletionType type = McpCompletionType.fromString(referenceType);
                McpCompletion completion = switch (type) {
                    case PROMPT -> {
                        String name = ref.get("name").asString().orElse(null);
                        yield name != null ? promptCompletions.get(name) : null;
                    }
                    case RESOURCE -> {
                        String uri = ref.get("uri").asString().orElse(null);
                        yield uri != null ? resourceCompletions.get(uri) : null;
                    }
                };
                if (completion != null) {
                    McpCompletionContent result = completion.completion()
                            .apply(McpRequest.builder()
                                           .parameters(parameters.get("argument"))
                                           .features(features)
                                           .protocolVersion(session.protocolVersion())
                                           .context(session.context())
                                           .build());
                    res.result(toJson(result));
                    sendResponse(req, res, session, features, requestId);
                    return;
                }
            } catch (IllegalArgumentException e) {      // invalid reference type
                // falls through
            }
        }

        // unable to process completion request
        res.error(INVALID_PARAMS, "Invalid completion request");
        sendResponse(req, res, session, features, requestId);
    }

    private void enableProgress(McpSession session, McpParameters parameters, McpFeatures features) {
        var progressToken = parameters.get("_meta").get("progressToken");
        if (progressToken.isEmpty()) {
            return;
        }
        if (progressToken.isNumber()) {
            features.progress().token(progressToken.asInteger().get());
        }
        if (progressToken.isString()) {
            features.progress().token(progressToken.asString().get());
        }
    }

    private boolean isTemplate(McpResource resource) {
        String uri = resource.uri();
        return uri.contains("{") || uri.contains("}");
    }

    private <T> McpPage<T> page(McpPagination<T> pagination, JsonRpcParams params) {
        Optional<JsonValue> cursor = params.find("cursor");
        if (cursor.isPresent()) {
            String cursorString = cursor.map(JsonString.class::cast)
                    .get()
                    .getString();
            return pagination.page(cursorString);
        }
        return pagination.firstPage();
    }

    /**
     * Process call by checking that session exists, executing logic by calling consumer
     * and returning a result according to the protocol in use.
     *
     * @param req the request
     * @param res the response
     * @param consumer a consumer implementing logic
     */
    private void processSimpleCall(JsonRpcRequest req, JsonRpcResponse res, Consumer<McpSession> consumer) {
        Optional<McpSession> foundSession = findSession(req);
        if (foundSession.isEmpty()) {
            res.status(Status.NOT_FOUND_404).send();
            return;
        }
        McpSession session = foundSession.get();
        consumer.accept(session);
        sendResponse(req, res, session);
    }

    /**
     * Sends response according to the protocol in use.
     *
     * @param req the request
     * @param res the response
     * @param session the active session
     */
    private void sendResponse(JsonRpcRequest req, JsonRpcResponse res, McpSession session) {
        if (isStreamableHttp(req.headers())) {
            LOGGER.log(Level.FINEST,
                       () -> String.format("Streamable HTTP: %s", res.asJsonObject()));
            res.send();
        } else {
            LOGGER.log(Level.FINEST,
                       () -> String.format("SSE: %s", res.asJsonObject()));
            session.send(res);
        }
    }

    /**
     * Sends response according to the protocol in use.
     *
     * @param req the request
     * @param res the response
     * @param session the active session
     * @param features the MCP features
     */
    private void sendResponse(JsonRpcRequest req,
                              JsonRpcResponse res,
                              McpSession session,
                              McpFeatures features,
                              JsonValue requestId) {
        sendResponse(req, res, session, requestId, features.sseSink().orElse(null));
    }

    /**
     * Sends response according to the protocol in use.
     *
     * @param req the request
     * @param res the response
     * @param session the active session
     * @param sseSink the active SseSink
     */
    private void sendResponse(JsonRpcRequest req,
                              JsonRpcResponse res,
                              McpSession session,
                              JsonValue requestId,
                              SseSink sseSink) {
        // send response as HTTP or SSE with streamable HTTP
        if (isStreamableHttp(req.headers())) {
            if (sseSink != null) {
                try (sseSink) {        // closes sink
                    JsonObject jsonObject = res.asJsonObject();
                    LOGGER.log(Level.FINEST,
                               () -> String.format("Streamable HTTP: %s", jsonObject));
                    sseSink.emit(SseEvent.builder()
                                      .name("message")
                                      .data(jsonObject)
                                      .build());
                }
            } else {
                LOGGER.log(Level.FINEST,
                           () -> String.format("HTTP: %s", res.asJsonObject()));
                res.send();
            }
        } else {
            LOGGER.log(Level.FINEST,
                       () -> String.format("SSE: %s", res.asJsonObject()));
            session.send(res);
        }
        session.clearRequest(requestId);
    }

    /**
     * Get or create MCP features based on transport.
     *
     * @param req the request
     * @param res the response
     * @param session the session
     * @return instance of MCP features
     */
    private McpFeatures mcpFeatures(JsonRpcRequest req,
                                    JsonRpcResponse res,
                                    McpSession session,
                                    JsonValue requestId) {
        return isStreamableHttp(req.headers())
                ? session.createFeatures(res, requestId)
                : session.createFeatures(requestId);
    }

    private void processToolSuppliers(McpServerConfig config, List<McpTool> tools) {
        List<McpSuppliers.McpToolSupplier> toolSuppliers = new CopyOnWriteArrayList<>(config.toolSuppliers());
        toolSuppliers.forEach(supplier -> {
            McpTool tool1 = supplier.get();
            boolean found = false;
            for (McpTool tool2 : tools) {
                if (tool1.name().equals(tool2.name())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tools.add(tool1);
            }
        });
    }
}
