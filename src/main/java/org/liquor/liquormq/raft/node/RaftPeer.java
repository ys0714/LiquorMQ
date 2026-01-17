package org.liquor.liquormq.raft.node;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.liquor.liquormq.grpc.RaftServiceGrpc;
import org.liquor.liquormq.raft.observer.ClusterObserver;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

public class RaftPeer {
    private final int id;
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub stub;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    // 静态上下文引用，用于获取 Observer Bean (临时方案，或者通过 Factory 创建 Peer)
    // 更好的方式是将 RaftPeer 变为 Prototype Bean，但这里为了最小侵入，
    // 我们假设 ApplicationContext 已经传递或者有一个静态 holder，或者我们手动实例化Interceptor
    // 但 ClusterObserver 是 Spring Bean。
    // 为了不破坏现有结构，我们在 RaftNode init 时将 Observer 注入给 Peer，或者 PeerConfig 中处理
    // 下面修改为允许传入 interceptor

    public RaftPeer(int id, String host, int port) {
        this(id, host, port, null);
    }

    public RaftPeer(int id, String host, int port, ClusterObserver observer) {
        this.id = id;
        this.host = host;
        this.port = port;
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext();

        if (observer != null) {
            builder.intercept(observer);
        }

        this.channel = builder.build();
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
