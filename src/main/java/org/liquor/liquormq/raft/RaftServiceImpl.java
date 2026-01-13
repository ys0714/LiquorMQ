package org.liquor.liquormq.raft;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.liquor.liquormq.grpc.*;
import org.springframework.beans.factory.annotation.Autowired;

@GrpcService
public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    @Autowired
    private RaftNode raftNode;

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        VoteResponse response = raftNode.handleRequestVote(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        AppendEntriesResponse response = raftNode.handleAppendEntries(request);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}

