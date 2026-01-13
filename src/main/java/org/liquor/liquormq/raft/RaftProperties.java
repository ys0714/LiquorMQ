package org.liquor.liquormq.raft;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "liquormq.raft")
public class RaftProperties {
    private int nodeId;
    private List<PeerConfig> peers = new ArrayList<>();

    @Data
    public static class PeerConfig {
        private int id;
        private String host;
        private int port;
    }
}

