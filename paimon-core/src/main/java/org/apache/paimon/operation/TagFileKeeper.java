/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.operation;

import org.apache.paimon.Snapshot;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.utils.TagManager;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.apache.paimon.operation.DeletionUtils.addMergedDataFiles;
import static org.apache.paimon.operation.DeletionUtils.containsDataFile;

/** Util class to provide methods to prevent tag files to be deleted when expiring snapshots. */
public class TagFileKeeper {

    private final ManifestList manifestList;
    private final ManifestFile manifestFile;
    private final TagManager tagManager;
    @Nullable private final Integer scanManifestParallelism;

    private long cachedTag = -1;
    private final Map<BinaryRow, Map<Integer, Set<String>>> cachedTagDataFiles;

    private List<Snapshot> taggedSnapshots;

    public TagFileKeeper(
            ManifestList manifestList,
            ManifestFile manifestFile,
            TagManager tagManager,
            @Nullable Integer scanManifestParallelism) {
        this.manifestList = manifestList;
        this.manifestFile = manifestFile;
        this.tagManager = tagManager;
        this.scanManifestParallelism = scanManifestParallelism;

        this.cachedTagDataFiles = new HashMap<>();
    }

    /** Caller should determine whether to reload. */
    public void reloadTags() {
        taggedSnapshots = tagManager.taggedSnapshots();
    }

    public Predicate<ManifestEntry> tagDataFileSkipper(long expiringSnapshotId) {
        int index = findPreviousTag(expiringSnapshotId, taggedSnapshots);
        if (index >= 0) {
            tryRefresh(taggedSnapshots.get(index));
        }
        return entry -> index >= 0 && containsDataFile(cachedTagDataFiles, entry);
    }

    public List<Snapshot> findOverlappedSnapshots(long beginInclusive, long endExclusive) {
        List<Snapshot> snapshots = new ArrayList<>();
        int right = findPreviousTag(endExclusive, taggedSnapshots);
        if (right >= 0) {
            int left = Math.max(findPreviousOrEqualTag(beginInclusive, taggedSnapshots), 0);
            for (int i = left; i <= right; i++) {
                snapshots.add(taggedSnapshots.get(i));
            }
        }
        return snapshots;
    }

    private void tryRefresh(Snapshot taggedSnapshot) {
        if (cachedTag != taggedSnapshot.id()) {
            refresh(taggedSnapshot);
            cachedTag = taggedSnapshot.id();
        }
    }

    private void refresh(Snapshot taggedSnapshot) {
        cachedTagDataFiles.clear();
        addMergedDataFiles(
                cachedTagDataFiles,
                taggedSnapshot,
                manifestList,
                manifestFile,
                scanManifestParallelism);
    }

    private int findPreviousTag(long targetSnapshotId, List<Snapshot> taggedSnapshots) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() < targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }

    private int findPreviousOrEqualTag(long targetSnapshotId, List<Snapshot> taggedSnapshots) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() <= targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }
}
