package interview.guide.modules.agent.service;

/** 同一面试已有 Agent 任务执行时抛出，供 SSE 返回可理解的业务错误。 */
public class AgentTaskBusyException extends RuntimeException {

    public AgentTaskBusyException() {
        super("当前面试已有一个 Agent 任务正在执行，请等待完成后再试");
    }
}
