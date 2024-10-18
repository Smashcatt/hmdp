package com.hmdp.utils;

/**
 * @author Shane
 * @create 2024/10/13 - 17:34
 */
public interface ILock {
    //尝试获取锁
    boolean tryLock(long timeoutSec);
    //释放锁
    void unlock();
}
