/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.common.concurrent;

import com.google.common.annotations.VisibleForTesting;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ConcurrentMaps {

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int INVERSE_CONTENTION_PROBABILITY = 8;

    /**
     * Returns a new mutable {@link ConcurrentMap} with capacity to
     * Increase the initial capacity to reduce contention at startup when concurrently adding elements.
     * <p>
     * See <a href="https://github.com/ben-manes/caffeine/pull/218">Caffeine PR 218</a>,
     * <a href="https://github.com/ben-manes/caffeine/wiki/Faq">Caffeine FAQ</a>,
     * <a href="https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java#L348-L349">ConcurrentHashMap comments</a>:
     * <p>
     * <bq> Lock contention probability for two threads accessing distinct elements is roughly 1 / (8 * # of elements)
     * under random hashes.</bq>
     *
     * @param expectedEntries number of expected entries
     */
    public static <K, V> ConcurrentMap<K, V> newWithExpectedEntries(int expectedEntries) {
        int concurrencyLevel = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new ConcurrentHashMap<>(initialCapacity(expectedEntries), DEFAULT_LOAD_FACTOR, concurrencyLevel);
    }

    @VisibleForTesting
    static int initialCapacity(int expectedEntries) {
        return Math.max(0, (int) ((expectedEntries * (INVERSE_CONTENTION_PROBABILITY * DEFAULT_LOAD_FACTOR)) - 1));
    }

    @VisibleForTesting
    static int expectedArraySize(int initialCapacity) {
        return Math.max(1, (-1 >>> Integer.numberOfLeadingZeros(initialCapacity - 1)) + 1);
    }

    private ConcurrentMaps() {}
}
