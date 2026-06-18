package interview.guide.common.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 小范围事务执行器，用于避免同类内部调用导致事务注解失效。
 */
@Service
public class TransactionalExecutor {

    @Transactional(rollbackFor = Exception.class)
    public void run(Runnable action) {
        action.run();
    }

    @Transactional(rollbackFor = Exception.class)
    public <T> T call(Supplier<T> action) {
        return action.get();
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void runRequiresNew(Runnable action) {
        action.run();
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public <T> T callRequiresNew(Supplier<T> action) {
        return action.get();
    }
}
