package org.liquor.liquormq.raft.node;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.liquor.liquormq.grpc.RaftServiceGrpc;

import java.util.concurrent.atomic.AtomicInteger;

public class RaftPeer {
    private final int id;
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub stub;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public RaftPeer(int id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = RaftServiceGrpc.newBlockingStub(channel);
        // 激进连接策略：在对象创建时立即请求建立连接
        // 这样在 Raft 算法正式启动前（Spring 启动过程），底层 TCP 连接有充分时间完成三次握手
        // 避免了首次 RPC 调用因建立连接而超时，导致无谓的选举失败
        this.channel.getState(true);
    }

    public int getId() {
        return id;
    }

    public RaftServiceGrpc.RaftServiceBlockingStub getStub() {
        return stub;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int incrementAndGetFailures() {
        return consecutiveFailures.incrementAndGet();
    }

    public void resetFailures() {
        consecutiveFailures.set(0);
    }

    /**
     * 尝试建立连接（预热），避免首次 RPC 延迟过高导致选举超时
     */
    public void warmUp() {
        channel.getState(true);
    }

    public void shutdown() {
        channel.shutdown();
    }
}
