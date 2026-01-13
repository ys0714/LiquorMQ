package org.liquor.liquormq.raft;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MqStateMachine implements StateMachine {

    @Override
    public void apply(byte[] command) {
        log.info("Applying command to state machine: {}", new String(command));
        // TODO: 在此处实现实际的消息存储逻辑
    }
}

