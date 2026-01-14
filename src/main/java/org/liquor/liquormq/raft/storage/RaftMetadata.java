package org.liquor.liquormq.raft.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RaftMetadata {
    private long currentTerm;
    private int votedFor;
}

