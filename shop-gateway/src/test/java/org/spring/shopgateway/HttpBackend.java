package org.spring.shopgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopcommon.result.Result;

final class HttpBackend implements AutoCloseable {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger permissionCalls = new AtomicInteger();
    final Map<String, UserPermissionSnapshot> grants = new ConcurrentHashMap<>();
    volatile int permissionStatus = 200;
    volatile String permissionRequestId;
    volatile String permissionTraceId;
    volatile CountDownLatch loadStarted;
    volatile CountDownLatch continueLoad;
    private final HttpServer server;

    HttpBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/users/", exchange -> {
                try (exchange) {
                    try {
                        var verifier = new org.spring.shopcommon.internalauth.InternalJwtVerifier((java.security.interfaces.RSAPublicKey)org.spring.shopcommon.internalauth.InternalTestTokens.key(false), "shop-user", java.time.Clock.systemUTC());
                        verifier.verify(exchange.getRequestHeaders().getFirst(PermissionCacheKeys.INTERNAL_TOKEN_HEADER), "GET", exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery());
                    } catch (Exception invalid) { exchange.sendResponseHeaders(401,-1); return; }
                    permissionCalls.incrementAndGet();
                    permissionRequestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                    permissionTraceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
                    String id = exchange.getRequestURI().getPath().substring("/internal/v1/users/".length()).split("/")[0];
                    var snapshot = grants.getOrDefault(id, new UserPermissionSnapshot(id, false, java.util.Set.of()));
                    if (loadStarted != null) loadStarted.countDown();
                    try { if (continueLoad != null) continueLoad.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    byte[] json = new ObjectMapper().writeValueAsBytes(Result.success(snapshot));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(permissionStatus, json.length);
                    exchange.getResponseBody().write(json);
                }
            });
            server.createContext("/", exchange -> {
                try (exchange) {
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    calls.incrementAndGet();
                    byte[] body = new ObjectMapper().writeValueAsBytes(Map.of(
                            "code", "BACKEND_OK", "path", exchange.getRequestURI().getRawPath(),
                            "requestId", exchange.getRequestHeaders().getFirst("X-Request-Id"),
                            "forwardedFor", exchange.getRequestHeaders().getFirst("X-Forwarded-For"),
                            "userId", java.util.Objects.toString(exchange.getRequestHeaders().getFirst("X-User-Id"), ""),
                            "userIdCount", exchange.getRequestHeaders().getOrDefault("X-User-Id", java.util.List.of()).size(),
                            "roles", java.util.Objects.toString(exchange.getRequestHeaders().getFirst("X-User-Roles"), ""),
                            "permissions", java.util.Objects.toString(exchange.getRequestHeaders().getFirst("X-User-Permissions"), ""),
                            "requestBody", requestBody,
                            "internalTokenPresent", exchange.getRequestHeaders().containsKey(PermissionCacheKeys.INTERNAL_TOKEN_HEADER)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Request-Id", "backend-supplied-id");
                    exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/backend-error") ? 502 : 200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
