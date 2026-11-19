package org.spring.shopcommon.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 提交任务时复制整个 MDC，在执行线程安装快照，结束后清理并恢复执行前上下文。
 * 恢复步骤兼容嵌套任务/同线程执行；不会让子任务清除提交线程正在使用的 MDC。
 */
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.clear();
                if (submitted != null) MDC.setContextMap(submitted);
                task.run();
            } finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        };
    }
}
