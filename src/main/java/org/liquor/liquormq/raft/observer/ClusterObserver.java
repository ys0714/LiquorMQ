package org.liquor.liquormq.raft.observer;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.liquor.liquormq.grpc.AppendEntriesRequest;
import org.liquor.liquormq.observer.grpc.ClusterEvent;
import org.liquor.liquormq.observer.grpc.ObserverServiceGrpc;
import org.liquor.liquormq.observer.grpc.ReportResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 上帝视角的集群观察者 (Cluster Observer)
 * <p>
 * 作为一个 gRPC Interceptor，它拦截进出的 Raft 协议包，并将其异步上报给 Observer Server。
 */
@Slf4j
@Component
@GrpcGlobalServerInterceptor
public class ClusterObserver implements ClientInterceptor, ServerInterceptor {

    @Value("${liquormq.raft.node-id:0}")
    private int myNodeId;

    @Value("${liquormq.observer.server.address:localhost:9099}")
    private String observerServerAddress;

    @Value("${liquormq.observer.enabled:true}")
    private boolean observerEnabled;

    private ManagedChannel observerChannel;
    private StreamObserver<ClusterEvent> reportStream;

    @PostConstruct
    public void init() {
        if (!observerEnabled) return;

        try {
            String[] parts = observerServerAddress.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            this.observerChannel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();

            ObserverServiceGrpc.ObserverServiceStub stub = ObserverServiceGrpc.newStub(observerChannel);

            // 建立长连接流
            this.reportStream = stub.report(new StreamObserver<ReportResponse>() {
                @Override
                public void onNext(ReportResponse value) {}
                @Override
                public void onError(Throwable t) {
                    // 连接断开，简单的重连逻辑或者静默失败
                    // 实际生产中需要断线重连机制，这里简化处理：置空，后续丢弃日志
                    reportStream = null;
                }
                @Override
                public void onCompleted() {}
            });

        } catch (Exception e) {
            log.warn("Failed to connect to Observer Server: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (reportStream != null) {
            reportStream.onCompleted();
        }
        if (observerChannel != null) {
            observerChannel.shutdown();
        }
    }

    // --- ServerInterceptor (观察收到的请求/发送的响应) --- //

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String methodName = extractMethodName(call.getMethodDescriptor());
        // 仅观察 Raft 协议相关的方法
        if (!isRaftMethod(methodName)) {
            return next.startCall(call, headers);
        }

        // 上下文：由于 ServerCall 和 Listener 是分离的，我们无法简单共享变量，
        // 除非我们利用 ServerCall 的属性或者闭包。
        // 这里利用 Java 闭包特性，isHeartbeat 数组作为引用传递
        final boolean[] isHeartbeat = {false};

        // 包装 ServerCall 以观察发送出去的响应
        ServerCall<ReqT, RespT> observingCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void sendMessage(RespT message) {
                if (!isHeartbeat[0]) {
                    logToServer("SEND_RESP", methodName, formatMessage(message));
                    logEvent("SEND_RESP", methodName, "To: [Client] | Content: " + formatMessage(message));
                }
                super.sendMessage(message);
            }
        };

        // 监听进入的请求
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                next.startCall(observingCall, headers)) {
            @Override
            public void onMessage(ReqT message) {
                if (isHeartbeatRequest(message)) {
                    isHeartbeat[0] = true;
                } else {
                    logToServer("RECV_REQ", methodName, formatMessage(message));
                    logEvent("RECV_REQ", methodName, "From: [Client] | Content: " + formatMessage(message));
                }
                super.onMessage(message);
            }
        };
    }

    // --- ClientInterceptor (观察发送的请求/收到的响应) --- //

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        String methodName = extractMethodName(method);

        // 如果不是 Raft 方法，直接放行
        if (!isRaftMethod(methodName)) {
            return next.newCall(method, callOptions);
        }

        final boolean[] isHeartbeat = {false};

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void sendMessage(ReqT message) {
                if (isHeartbeatRequest(message)) {
                    isHeartbeat[0] = true;
                } else {
                    logToServer("SEND_REQ", methodName, formatMessage(message));
                    logEvent("SEND_REQ", methodName, "Content: " + formatMessage(message));
                }
                super.sendMessage(message);
            }

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                Listener<RespT> observingListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    @Override
                    public void onMessage(RespT message) {
                        if (!isHeartbeat[0]) {
                            logToServer("RECV_RESP", methodName, formatMessage(message));
                            logEvent("RECV_RESP", methodName, "Content: " + formatMessage(message));
                        }
                        super.onMessage(message);
                    }
                };
                super.start(observingListener, headers);
            }
        };
    }

    // --- Helper Methods --- //

    private boolean isHeartbeatRequest(Object message) {
        if (message instanceof AppendEntriesRequest) {
            return ((AppendEntriesRequest) message).getEntriesCount() == 0;
        }
        return false;
    }

    private boolean isRaftMethod(String methodName) {
        return "RequestVote".equals(methodName) || "AppendEntries".equals(methodName);
    }

    private String extractMethodName(MethodDescriptor<?, ?> method) {
        String fullMethodName = method.getFullMethodName();
        // format: liquormq.RaftService/RequestVote -> RequestVote
        int index = fullMethodName.lastIndexOf('/');
        return index >= 0 ? fullMethodName.substring(index + 1) : fullMethodName;
    }

    private String formatMessage(Object message) {
        // Protobuf GeneratedMessageV3 的 toString() 已经可以直接输出非常易读的格式，
        // 且完全符合 proto 定义的变量名 (term, index, entries...)
        // 我们只需将其压缩为单行以节省日志空间，或者保持多行结构
        String raw = message.toString();
        // 截断过长消息，避免 gRPC RESOURCE_EXHAUSTED 错误 (默认 4MB)
        if (raw.length() > 1000) {
            return raw.substring(0, 1000).replace("\n", " ").trim() + "... (truncated)";
        }
        // 简单压缩：去掉换行，保留结构
        return raw.replace("\n", " ").trim();
    }

    private void logEvent(String direction, String method, String details) {
        // 特殊的前缀便于 grep 或 Kibana 过滤
//        log.info("[GOD_EYE] [Node-{}] [{}] [{}] {}", myNodeId, direction, method, details);
    }

    private void logToServer(String type, String method, String content) {
        if (reportStream == null) return;
        try {
            reportStream.onNext(ClusterEvent.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setNodeId(myNodeId)
                    .setType(type)
                    .setMethod(method)
                    .setContent(content)
                    .build());
        } catch (Exception e) {
            // 防止 stream 关闭后抛出异常导致业务中断
        }
    }
}
