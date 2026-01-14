package org.liquor.liquormq.raft.controller;

import org.liquor.liquormq.raft.node.RaftNode;
import org.liquor.liquormq.raft.node.RaftNodeStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/raft")
public class RaftController {

    @Autowired
    private RaftNode raftNode;

    @PostMapping("/send")
    public ResponseEntity<String> sendCommand(@RequestBody String command) {
        boolean success = raftNode.propose(command);
        if (success) {
            return ResponseEntity.ok("Command accepted");
        } else {
            return ResponseEntity.status(503).body("Not leader or system busy");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<RaftNodeStatus> getStatus() {
        return ResponseEntity.ok(raftNode.getNodeStatus());
    }
}
