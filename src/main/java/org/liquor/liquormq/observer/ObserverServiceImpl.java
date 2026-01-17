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
                // 使用 String.format 进行格式化，确保对齐生效，且 content 能正确显示
                String logMsg = String.format("[GLOBAL-LOG] %s | Node-%d | %-10s | %-15s | %s",
                        timeStr,
                        event.getNodeId(),
                        event.getType(),
                        event.getMethod(),
                        event.getContent());
                log.info(logMsg);
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
