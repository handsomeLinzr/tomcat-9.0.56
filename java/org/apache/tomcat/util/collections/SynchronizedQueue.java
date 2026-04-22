/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.collections;

/**
 * This is intended as a (mostly) GC-free alternative to
 * {@link java.util.concurrent.ConcurrentLinkedQueue} when the requirement is to
 * create an unbounded queue with no requirement to shrink the queue. The aim is
 * to provide the bare minimum of required functionality as quickly as possible
 * with minimum garbage.
 *
 * @param <T> The type of object managed by this queue
 */
public class SynchronizedQueue<T> {

    // 默认队列大小128
    public static final int DEFAULT_SIZE = 128;

    // 用于放置对象的数组
    private Object[] queue;
    private int size;
    // 下一个要放入的位置
    private int insert = 0;
    // 下一个要获取的位置
    private int remove = 0;

    public SynchronizedQueue() {
        // 默认 128
        this(DEFAULT_SIZE);
    }

    public SynchronizedQueue(int initialSize) {
        // 创建队列
        queue = new Object[initialSize];
        // 设置 size 大小
        size = initialSize;
    }

    public synchronized boolean offer(T t) {
        // 将对象放入到数组 queue 中
        queue[insert++] = t;

        // 如果下个要放置的位置，已经到了边界，则重置位置改为 0
        // Wrap
        if (insert == size) {
            insert = 0;
        }

        // 如果下个要放置的位置，已经到了 remove 位置，也就是说，下个要放置的位置已经追上即将被消费的位置了
        // 则说明数组已经不够了，则需要对数组进行扩容
        if (insert == remove) {
            expand();
        }
        return true;
    }

    public synchronized T poll() {
        if (insert == remove) {
            // empty
            return null;
        }

        @SuppressWarnings("unchecked")
        T result = (T) queue[remove];
        queue[remove] = null;
        remove++;

        // Wrap
        if (remove == size) {
            remove = 0;
        }

        return result;
    }

    // 扩容
    private void expand() {
        // 新数组大小为原来的两倍
        int newSize = size * 2;
        Object[] newQueue = new Object[newSize];

        // 从原来的 insert 后的位置到最大，先复制到新数组的0开始
        System.arraycopy(queue, insert, newQueue, 0, size - insert);
        // 再从原来的0开始到，复制到新数组
        System.arraycopy(queue, 0, newQueue, size - insert, insert);
        // 前边分两次复制，是为了保持顺序，从0开始往后

        insert = size;
        remove = 0;
        queue = newQueue;
        size = newSize;
    }

    public synchronized int size() {
        int result = insert - remove;
        if (result < 0) {
            result += size;
        }
        return result;
    }

    public synchronized void clear() {
        queue = new Object[size];
        insert = 0;
        remove = 0;
    }
}
