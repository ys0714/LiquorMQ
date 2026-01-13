package org.liquor.liquormq.raft;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
}

