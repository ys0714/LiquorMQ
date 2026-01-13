package org.liquor.liquormq.raft;

public interface StateMachine {
    /**
     * 将已提交的命令应用到状态机。
     * @param command 命令字节数组。
     */
    void apply(byte[] command);
}

