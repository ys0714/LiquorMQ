package org.liquor.liquormq.observer;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.liquor.liquormq.observer.grpc.ClusterEvent;
import org.liquor.liquormq.observer.grpc.ObserverServiceGrpc;
import org.liquor.liquormq.observer.grpc.ReportResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

@Slf4j
@GrpcService
@ConditionalOnProperty(name = "liquormq.observer.server.enabled", havingValue = "true")
public class ObserverServiceImpl extends ObserverServiceGrpc.ObserverServiceImplBase {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    // 可选：使用优先队列稍微缓冲以按时间排序（如果不同节点时间基本同步）
    // 这里简单起见直接打印，因为“实时”更重要

    @Override
    public StreamObserver<ClusterEvent> report(StreamObserver<ReportResponse> responseObserver) {
        return new StreamObserver<ClusterEvent>() {
            @Override
            public void onNext(ClusterEvent event) {
                String timeStr = formatter.format(Instant.ofEpochMilli(event.getTimestamp()));

                String method = event.getMethod();
                String type = event.getType();
                // 压缩多行 Protobuf 字符串为单行，方便日志聚合
                String rawContent = event.getContent().replace('\n', ' ').replaceAll("\\s+", " ").trim();

                String icon = "";
                String summary = rawContent;

                // 简单的日志增强处理，提取关键信息
                if ("RequestVote".equals(method)) {
                    icon = "🗳️";
                    if (summary.contains("vote_granted: true")) {
                        summary = wrapColor(summary, ANSI_GREEN);
                    } else if (summary.contains("vote_granted: false")) {
                        summary = wrapColor(summary, ANSI_RED);
                    }
                } else if ("AppendEntries".equals(method)) {
                    icon = "🪵";
                    // 尝试提取 entries 数量 (简单的字符串包含判断，精确解析需要反序列化)
                    if (rawContent.contains("entries {")) {
                         summary = wrapColor(summary, ANSI_BLUE);
                    }
                }

                String logMsg = String.format("[GLOBAL-LOG] %s | Node-%d | %-10s | %s %-15s | %s",
                        timeStr,
                        event.getNodeId(),
                        type,
                        icon,
                        method,
                        summary);
                log.info(logMsg);
            }

            // ANSI 颜色代码，用于控制台高亮
            private static final String ANSI_RESET = "\u001B[0m";
            private static final String ANSI_RED = "\u001B[31m";
            private static final String ANSI_GREEN = "\u001B[32m";
            private static final String ANSI_BLUE = "\u001B[34m";

            private String wrapColor(String text, String color) {
                return color + text + ANSI_RESET;
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Observer client disconnected with error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(ReportResponse.newBuilder().setSuccess(true).build());
                responseObserver.onCompleted();
            }
        };
    }
}
