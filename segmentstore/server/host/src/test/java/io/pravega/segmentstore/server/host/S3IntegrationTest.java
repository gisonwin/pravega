/**
 * Copyright Pravega Authors.
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
package io.pravega.segmentstore.server.host;

import com.google.common.base.Preconditions;
import io.pravega.segmentstore.server.store.ServiceBuilder;
import io.pravega.segmentstore.server.store.ServiceBuilderConfig;
import io.pravega.segmentstore.storage.SimpleStorageFactory;
import io.pravega.segmentstore.storage.Storage;
import io.pravega.segmentstore.storage.chunklayer.ChunkStorage;
import io.pravega.segmentstore.storage.chunklayer.ChunkedSegmentStorage;
import io.pravega.segmentstore.storage.chunklayer.ChunkedSegmentStorageConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperLogFactory;
import io.pravega.segmentstore.storage.metadata.ChunkMetadataStore;
import io.pravega.storage.s3.S3ChunkStorage;
import io.pravega.storage.s3.S3ClientMock;
import io.pravega.storage.s3.S3Mock;
import io.pravega.storage.s3.S3StorageConfig;
import lombok.Getter;
import org.junit.After;
import org.junit.Before;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

/**
 * End-to-end tests for SegmentStore, with integrated AWS S3 Storage and DurableDataLog.
 */
public class S3IntegrationTest extends BookKeeperIntegrationTestBase {
    //region Test Configuration and Setup

    private String s3ConfigUri;
    private S3Mock s3Mock;

    @Getter
    private final ChunkedSegmentStorageConfig chunkedSegmentStorageConfig = ChunkedSegmentStorageConfig.DEFAULT_CONFIG.toBuilder()
            .journalSnapshotInfoUpdateFrequency(Duration.ofMillis(10))
            .maxJournalUpdatesPerSnapshot(5)
            .garbageCollectionDelay(Duration.ofMillis(10))
            .garbageCollectionSleep(Duration.ofMillis(10))
            .minSizeLimitForConcat(100)
            .maxSizeLimitForConcat(1000)
            .selfCheckEnabled(true)
            .build();

    /**
     * Starts BookKeeper.
     */
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        s3ConfigUri = "https://localhost";
        String bucketName = "test-bucket";
        String prefix = "Integration" + UUID.randomUUID();
        s3Mock = new S3Mock();
        this.configBuilder.include(S3StorageConfig.builder()
                .with(S3StorageConfig.CONFIGURI, s3ConfigUri)
                .with(S3StorageConfig.BUCKET, bucketName)
                .with(S3StorageConfig.PREFIX, prefix)
                .with(S3StorageConfig.ACCESS_KEY, "access")
                .with(S3StorageConfig.SECRET_KEY, "secret"));
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    //endregion

    //region StreamSegmentStoreTestBase Implementation

    @Override
    protected ServiceBuilder createBuilder(ServiceBuilderConfig.Builder configBuilder, int instanceId, boolean useChunkedSegmentStorage) {
        Preconditions.checkState(useChunkedSegmentStorage);
        ServiceBuilderConfig builderConfig = getBuilderConfig(configBuilder, instanceId);
        return ServiceBuilder
                .newInMemoryBuilder(builderConfig)
                .withStorageFactory(setup -> new LocalS3SimpleStorageFactory(setup.getConfig(S3StorageConfig::builder), setup.getStorageExecutor()))
                .withDataLogFactory(setup -> new BookKeeperLogFactory(setup.getConfig(BookKeeperConfig::builder),
                        getBookkeeper().getZkClient(), setup.getCoreExecutor()));
    }

    @Override
    public void testEndToEnd() {
    }

    @Override
    public void testFlushToStorage() {
    }

    @Override
    public void testEndToEndWithFencing() {
    }
    //endregion

    private class LocalS3SimpleStorageFactory implements SimpleStorageFactory {
        private final S3StorageConfig config;

        @Getter
        private final ChunkedSegmentStorageConfig chunkedSegmentStorageConfig = ChunkedSegmentStorageConfig.DEFAULT_CONFIG.toBuilder()
                .journalSnapshotInfoUpdateFrequency(Duration.ofMillis(10))
                .maxJournalUpdatesPerSnapshot(5)
                .garbageCollectionDelay(Duration.ofMillis(10))
                .garbageCollectionSleep(Duration.ofMillis(10))
                .selfCheckEnabled(true)
                .build();

        @Getter
        private final ScheduledExecutorService executor;

        LocalS3SimpleStorageFactory(S3StorageConfig config, ScheduledExecutorService executor) {
            this.config = Preconditions.checkNotNull(config, "config");
            this.executor = Preconditions.checkNotNull(executor, "executor");
        }

        @Override
        public Storage createStorageAdapter(int containerId, ChunkMetadataStore metadataStore) {
            return new ChunkedSegmentStorage(containerId,
                    createChunkStorage(),
                    metadataStore,
                    this.executor,
                    this.chunkedSegmentStorageConfig);
        }

        /**
         * Creates a new instance of a Storage adapter.
         */
        @Override
        public Storage createStorageAdapter() {
            throw new UnsupportedOperationException("SimpleStorageFactory requires ChunkMetadataStore");
        }

        @Override
        public ChunkStorage createChunkStorage() {
            URI uri = URI.create(s3ConfigUri);
            S3ClientMock client = new S3ClientMock(s3Mock);
            return new S3ChunkStorage(client, this.config, executorService(), true);
        }
    }
}
