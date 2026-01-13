package org.liquor.liquormq.raft;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.liquor.liquormq.grpc.RaftServiceGrpc;

public class RaftPeer {
    private final int id;
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub stub;

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

    public void shutdown() {
        channel.shutdown();
    }
}

