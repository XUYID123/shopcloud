package org.spring.shopcache;

/** 缓存保护拒绝、依赖故障或超时的统一异常；上层可映射为 503，不代表业务数据不存在。 */
public class CacheUnavailableException extends RuntimeException {
    /** 保留根因用于日志排查；不要直接把根因信息作为外部 HTTP 响应。 */
    public CacheUnavailableException(String message, Throwable cause) { super(message, cause); }
    /** 锁竞争超时/锁已失效等明确拒绝，无需伪造数据库异常。 */
    public CacheUnavailableException(String message) { super(message); }
}
