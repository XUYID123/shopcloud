package org.spring.shopcache;

/** 数据库只读加载函数；null 表示确实不存在，异常表示依赖/业务失败，二者不能混淆。 */
@FunctionalInterface
public interface CacheLoader<T> {
    /** 仅执行可重试的只读查询；不要在函数中修改数据库、操作缓存或依赖调用线程事务。 */
    T load() throws Exception;
}
