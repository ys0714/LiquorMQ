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

    public void shutdown() {
        channel.shutdown();
    }
}
