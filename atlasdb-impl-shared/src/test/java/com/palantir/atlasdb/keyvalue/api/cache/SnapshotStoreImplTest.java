/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.keyvalue.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CellReference;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.watch.Sequence;
import com.palantir.atlasdb.keyvalue.api.watch.StartTimestamp;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import java.util.stream.Stream;
import org.assertj.core.api.OptionalAssert;
import org.junit.Before;
import org.junit.Test;

public final class SnapshotStoreImplTest {
    private static final Sequence SEQUENCE_1 = Sequence.of(1337L);
    private static final Sequence SEQUENCE_2 = Sequence.of(8284L);
    private static final Sequence SEQUENCE_3 = Sequence.of(9999L);
    private static final Sequence SEQUENCE_4 = Sequence.of(31337L);
    private static final Sequence SEQUENCE_5 = Sequence.of(88888L);
    private static final StartTimestamp TIMESTAMP_1 = StartTimestamp.of(42L);
    private static final StartTimestamp TIMESTAMP_2 = StartTimestamp.of(31415925635L);
    private static final StartTimestamp TIMESTAMP_3 = StartTimestamp.of(404L);
    private static final StartTimestamp TIMESTAMP_4 = StartTimestamp.of(10110101L);
    private static final StartTimestamp TIMESTAMP_5 = StartTimestamp.of(500);
    private static final ValueCacheSnapshot SNAPSHOT_1 =
            ValueCacheSnapshotImpl.of(HashMap.empty(), HashSet.empty(), ImmutableSet.of());
    private static final ValueCacheSnapshot SNAPSHOT_2 = createSnapshot(2);
    private static final ValueCacheSnapshot SNAPSHOT_3 = createSnapshot(3);
    private static final ValueCacheSnapshot SNAPSHOT_4 = createSnapshot(4);
    private static final ValueCacheSnapshot SNAPSHOT_5 = createSnapshot(5);

    private SnapshotStore snapshotStore;

    @Before
    public void before() {
        snapshotStore = SnapshotStoreImpl.create();
    }

    @Test
    public void singleSnapshotStoredForMultipleTimestamps() {
        snapshotStore.storeSnapshot(SEQUENCE_1, ImmutableSet.of(TIMESTAMP_1, TIMESTAMP_2, TIMESTAMP_3), SNAPSHOT_1);
        snapshotStore.storeSnapshot(SEQUENCE_2, ImmutableSet.of(TIMESTAMP_4), SNAPSHOT_2);

        assertSnapshotsEqualForTimestamp(SNAPSHOT_1, TIMESTAMP_1, TIMESTAMP_2, TIMESTAMP_3);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_2, TIMESTAMP_4);
    }

    @Test
    public void snapshotsOverwriteForSameSequence() {
        snapshotStore.storeSnapshot(SEQUENCE_1, ImmutableSet.of(TIMESTAMP_1), SNAPSHOT_1);
        snapshotStore.storeSnapshot(SEQUENCE_1, ImmutableSet.of(TIMESTAMP_2), SNAPSHOT_2);

        assertThat(snapshotStore.getSnapshot(TIMESTAMP_1).get())
                .isEqualTo(SNAPSHOT_2)
                .isNotEqualTo(SNAPSHOT_1);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_2, TIMESTAMP_1, TIMESTAMP_2);
    }

    @Test
    public void removeTimestampRemovesSnapshotWhenThereAreNoMoreLiveTimestampsForSequence() {
        snapshotStore = new SnapshotStoreImpl(0, 20_000);
        snapshotStore.storeSnapshot(SEQUENCE_1, ImmutableSet.of(TIMESTAMP_1, TIMESTAMP_2, TIMESTAMP_3), SNAPSHOT_1);
        snapshotStore.storeSnapshot(SEQUENCE_2, ImmutableSet.of(TIMESTAMP_4), SNAPSHOT_2);

        assertSnapshotsEqualForTimestamp(SNAPSHOT_1, TIMESTAMP_1, TIMESTAMP_2, TIMESTAMP_3);

        snapshotStore.removeTimestamp(TIMESTAMP_2);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_1, TIMESTAMP_1, TIMESTAMP_3);
        assertThat(snapshotStore.getSnapshot(TIMESTAMP_2)).isEmpty();

        snapshotStore.removeTimestamp(TIMESTAMP_1);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_1, TIMESTAMP_3);
        assertThat(snapshotStore.getSnapshot(TIMESTAMP_1)).isEmpty();

        snapshotStore.removeTimestamp(TIMESTAMP_3);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_2, TIMESTAMP_4);
        assertThat(snapshotStore.getSnapshot(TIMESTAMP_1)).isEmpty();

        assertThat(snapshotStore.getSnapshotForSequence(SEQUENCE_1)).isEmpty();
    }

    @Test
    public void removeTimestampOnlyRetentionsDownToMinimumSize() {
        snapshotStore = new SnapshotStoreImpl(2, 20_000);
        snapshotStore.storeSnapshot(SEQUENCE_1, ImmutableSet.of(TIMESTAMP_1), SNAPSHOT_1);
        snapshotStore.storeSnapshot(SEQUENCE_2, ImmutableSet.of(TIMESTAMP_2), SNAPSHOT_2);
        snapshotStore.storeSnapshot(SEQUENCE_3, ImmutableSet.of(TIMESTAMP_3), SNAPSHOT_3);
        snapshotStore.storeSnapshot(SEQUENCE_4, ImmutableSet.of(TIMESTAMP_4), SNAPSHOT_4);

        assertSnapshotsEqualForTimestamp(SNAPSHOT_1, TIMESTAMP_1);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_2, TIMESTAMP_2);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_3, TIMESTAMP_3);
        assertSnapshotsEqualForTimestamp(SNAPSHOT_4, TIMESTAMP_4);

        removeSnapshotAndAssert(TIMESTAMP_1, SEQUENCE_1).isEmpty();
        removeSnapshotAndAssert(TIMESTAMP_2, SEQUENCE_2).isEmpty();
        removeSnapshotAndAssert(TIMESTAMP_3, SEQUENCE_3).hasValue(SNAPSHOT_3);
        removeSnapshotAndAssert(TIMESTAMP_4, SEQUENCE_4).hasValue(SNAPSHOT_4);

        snapshotStore.storeSnapshot(SEQUENCE_5, ImmutableSet.of(TIMESTAMP_5), SNAPSHOT_5);
        removeSnapshotAndAssert(TIMESTAMP_5, SEQUENCE_5).hasValue(SNAPSHOT_5);
        assertThat(snapshotStore.getSnapshotForSequence(SEQUENCE_3)).isEmpty();
    }

    private OptionalAssert<ValueCacheSnapshot> removeSnapshotAndAssert(StartTimestamp timestamp, Sequence sequence) {
        snapshotStore.removeTimestamp(timestamp);
        assertThat(snapshotStore.getSnapshot(timestamp)).isEmpty();
        return assertThat(snapshotStore.getSnapshotForSequence(sequence));
    }

    private void assertSnapshotsEqualForTimestamp(ValueCacheSnapshot expectedValue, StartTimestamp... timestamps) {
        Stream.of(timestamps).map(snapshotStore::getSnapshot).forEach(snapshot -> assertThat(snapshot)
                .hasValue(expectedValue));
    }

    private static ValueCacheSnapshot createSnapshot(int value) {
        byte byteValue = (byte) value;
        return ValueCacheSnapshotImpl.of(
                HashMap.<CellReference, CacheEntry>empty()
                        .put(
                                CellReference.of(
                                        TableReference.createFromFullyQualifiedName("t.table"),
                                        Cell.create(new byte[] {byteValue}, new byte[] {byteValue})),
                                CacheEntry.locked()),
                HashSet.empty(),
                ImmutableSet.of());
    }
}
