/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.replication

import org.opensearch.replication.action.autofollow.AutoFollowMasterNodeAction
import org.opensearch.replication.action.autofollow.TransportAutoFollowMasterNodeAction
import org.opensearch.replication.action.autofollow.TransportUpdateAutoFollowPatternAction
import org.opensearch.replication.action.autofollow.UpdateAutoFollowPatternAction
import org.opensearch.replication.action.changes.GetChangesAction
import org.opensearch.replication.action.changes.TransportGetChangesAction
import org.opensearch.replication.action.index.ReplicateIndexAction
import org.opensearch.replication.action.index.ReplicateIndexMasterNodeAction
import org.opensearch.replication.action.index.TransportReplicateIndexAction
import org.opensearch.replication.action.index.TransportReplicateIndexMasterNodeAction
import org.opensearch.replication.action.index.block.TransportUpddateIndexBlockAction
import org.opensearch.replication.action.index.block.UpdateIndexBlockAction
import org.opensearch.replication.action.pause.PauseIndexReplicationAction
import org.opensearch.replication.action.pause.TransportPauseIndexReplicationAction
import org.opensearch.replication.action.replay.ReplayChangesAction
import org.opensearch.replication.action.replay.TransportReplayChangesAction
import org.opensearch.replication.action.replicationstatedetails.TransportUpdateReplicationStateDetails
import org.opensearch.replication.action.replicationstatedetails.UpdateReplicationStateAction
import org.opensearch.replication.action.repository.GetFileChunkAction
import org.opensearch.replication.action.repository.GetStoreMetadataAction
import org.opensearch.replication.action.repository.ReleaseLeaderResourcesAction
import org.opensearch.replication.action.repository.TransportGetFileChunkAction
import org.opensearch.replication.action.repository.TransportGetStoreMetadataAction
import org.opensearch.replication.action.repository.TransportReleaseLeaderResourcesAction
import org.opensearch.replication.action.resume.ResumeIndexReplicationAction
import org.opensearch.replication.action.resume.TransportResumeIndexReplicationAction
import org.opensearch.replication.action.setup.SetupChecksAction
import org.opensearch.replication.action.setup.TransportSetupChecksAction
import org.opensearch.replication.action.setup.TransportValidatePermissionsAction
import org.opensearch.replication.action.setup.ValidatePermissionsAction
import org.opensearch.replication.action.status.ReplicationStatusAction
import org.opensearch.replication.action.status.ShardsInfoAction
import org.opensearch.replication.action.status.TranportShardsInfoAction
import org.opensearch.replication.action.status.TransportReplicationStatusAction
import org.opensearch.replication.action.stop.StopIndexReplicationAction
import org.opensearch.replication.action.stop.TransportStopIndexReplicationAction
import org.opensearch.replication.action.update.TransportUpdateIndexReplicationAction
import org.opensearch.replication.action.update.UpdateIndexReplicationAction
import org.opensearch.replication.metadata.ReplicationMetadataManager
import org.opensearch.replication.metadata.TransportUpdateMetadataAction
import org.opensearch.replication.metadata.UpdateMetadataAction
import org.opensearch.replication.metadata.state.ReplicationStateMetadata
import org.opensearch.replication.metadata.store.ReplicationMetadataStore
import org.opensearch.replication.repository.REMOTE_REPOSITORY_TYPE
import org.opensearch.replication.repository.RemoteClusterRepositoriesService
import org.opensearch.replication.repository.RemoteClusterRepository
import org.opensearch.replication.repository.RemoteClusterRestoreLeaderService
import org.opensearch.replication.rest.PauseIndexReplicationHandler
import org.opensearch.replication.rest.ReplicateIndexHandler
import org.opensearch.replication.rest.ReplicationStatusHandler
import org.opensearch.replication.rest.ResumeIndexReplicationHandler
import org.opensearch.replication.rest.StopIndexReplicationHandler
import org.opensearch.replication.rest.UpdateAutoFollowPatternsHandler
import org.opensearch.replication.rest.UpdateIndexHandler
import org.opensearch.replication.seqno.RemoteClusterTranslogService
import org.opensearch.replication.task.IndexCloseListener
import org.opensearch.replication.task.autofollow.AutoFollowExecutor
import org.opensearch.replication.task.autofollow.AutoFollowParams
import org.opensearch.replication.task.index.IndexReplicationExecutor
import org.opensearch.replication.task.index.IndexReplicationParams
import org.opensearch.replication.task.index.IndexReplicationState
import org.opensearch.replication.task.shard.ShardReplicationExecutor
import org.opensearch.replication.task.shard.ShardReplicationParams
import org.opensearch.replication.task.shard.ShardReplicationState
import org.opensearch.replication.util.Injectables
import org.opensearch.action.ActionRequest
import org.opensearch.action.ActionResponse
import org.opensearch.client.Client
import org.opensearch.cluster.NamedDiff
import org.opensearch.cluster.metadata.IndexNameExpressionResolver
import org.opensearch.cluster.metadata.Metadata
import org.opensearch.cluster.metadata.RepositoryMetadata
import org.opensearch.cluster.node.DiscoveryNodes
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.CheckedFunction
import org.opensearch.common.ParseField
import org.opensearch.common.component.LifecycleComponent
import org.opensearch.common.io.stream.NamedWriteableRegistry
import org.opensearch.common.io.stream.Writeable
import org.opensearch.common.settings.ClusterSettings
import org.opensearch.common.settings.IndexScopedSettings
import org.opensearch.common.settings.Setting
import org.opensearch.common.settings.Settings
import org.opensearch.common.settings.SettingsFilter
import org.opensearch.common.settings.SettingsModule
import org.opensearch.common.unit.ByteSizeUnit
import org.opensearch.common.unit.ByteSizeValue
import org.opensearch.common.unit.TimeValue
import org.opensearch.common.util.concurrent.OpenSearchExecutors
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.env.Environment
import org.opensearch.env.NodeEnvironment
import org.opensearch.index.IndexModule
import org.opensearch.index.IndexSettings
import org.opensearch.index.engine.EngineFactory
import org.opensearch.indices.recovery.RecoverySettings
import org.opensearch.persistent.PersistentTaskParams
import org.opensearch.persistent.PersistentTaskState
import org.opensearch.persistent.PersistentTasksExecutor
import org.opensearch.plugins.ActionPlugin
import org.opensearch.plugins.ActionPlugin.ActionHandler
import org.opensearch.plugins.EnginePlugin
import org.opensearch.plugins.PersistentTaskPlugin
import org.opensearch.plugins.Plugin
import org.opensearch.plugins.RepositoryPlugin
import org.opensearch.repositories.RepositoriesService
import org.opensearch.repositories.Repository
import org.opensearch.rest.RestController
import org.opensearch.rest.RestHandler
import org.opensearch.script.ScriptService
import org.opensearch.threadpool.ExecutorBuilder
import org.opensearch.threadpool.FixedExecutorBuilder
import org.opensearch.threadpool.ScalingExecutorBuilder
import org.opensearch.threadpool.ThreadPool
import org.opensearch.watcher.ResourceWatcherService
import java.util.Optional
import java.util.function.Supplier

internal class ReplicationPlugin : Plugin(), ActionPlugin, PersistentTaskPlugin, RepositoryPlugin, EnginePlugin {

    private lateinit var client: Client
    private lateinit var threadPool: ThreadPool
    private lateinit var replicationMetadataManager: ReplicationMetadataManager
    private lateinit var replicationSettings: ReplicationSettings

    companion object {
        const val REPLICATION_EXECUTOR_NAME_LEADER = "replication_leader"
        const val REPLICATION_EXECUTOR_NAME_FOLLOWER = "replication_follower"
        val REPLICATED_INDEX_SETTING: Setting<String> = Setting.simpleString("index.plugins.replication.replicated",
            Setting.Property.InternalIndex, Setting.Property.IndexScope)
        val REPLICATION_CHANGE_BATCH_SIZE: Setting<Int> = Setting.intSetting("plugins.replication.ops_batch_size", 50000, 16,
            Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_LEADER_THREADPOOL_SIZE: Setting<Int> = Setting.intSetting("plugins.replication.leader.size", 0, 0,
            Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_LEADER_THREADPOOL_QUEUE_SIZE: Setting<Int> = Setting.intSetting("plugins.replication.leader.queue_size", 1000, 0,
            Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_FOLLOWER_RECOVERY_CHUNK_SIZE: Setting<ByteSizeValue> = Setting.byteSizeSetting("plugins.replication.index.recovery.chunk_size", ByteSizeValue(10, ByteSizeUnit.MB),
                ByteSizeValue(1, ByteSizeUnit.MB), ByteSizeValue(1, ByteSizeUnit.GB),
                Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_FOLLOWER_RECOVERY_PARALLEL_CHUNKS: Setting<Int> = Setting.intSetting("plugins.replication.index.recovery.max_concurrent_file_chunks", 5, 1,
                Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_PARALLEL_READ_PER_SHARD = Setting.intSetting("plugins.replication.parallel_reads_per_shard", 2, 1,
            Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_PARALLEL_READ_POLL_DURATION = Setting.timeSetting ("plugins.replication.parallel_reads_poll_duration", TimeValue.timeValueMillis(50), TimeValue.timeValueMillis(1),
            TimeValue.timeValueSeconds(1), Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_AUTOFOLLOW_REMOTE_INDICES_POLL_DURATION = Setting.timeSetting ("plugins.replication.autofollow.remote.fetch_poll_duration", TimeValue.timeValueSeconds(30), TimeValue.timeValueSeconds(30),
                TimeValue.timeValueHours(1), Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_AUTOFOLLOW_REMOTE_INDICES_RETRY_POLL_DURATION = Setting.timeSetting ("plugins.replication.autofollow.remote.retry_poll_duration", TimeValue.timeValueHours(1), TimeValue.timeValueMinutes(30),
                TimeValue.timeValueHours(4), Setting.Property.Dynamic, Setting.Property.NodeScope)
        val REPLICATION_METADATA_SYNC_INTERVAL = Setting.timeSetting("plugins.replication.metadata_sync",
                TimeValue.timeValueSeconds(60), TimeValue.timeValueSeconds(5),
                Setting.Property.Dynamic, Setting.Property.NodeScope)
    }

    override fun createComponents(client: Client, clusterService: ClusterService, threadPool: ThreadPool,
                                  resourceWatcherService: ResourceWatcherService, scriptService: ScriptService,
                                  xContentRegistry: NamedXContentRegistry, environment: Environment,
                                  nodeEnvironment: NodeEnvironment,
                                  namedWriteableRegistry: NamedWriteableRegistry,
                                  indexNameExpressionResolver: IndexNameExpressionResolver,
                                  repositoriesService: Supplier<RepositoriesService>): Collection<Any> {
        this.client = client
        this.threadPool = threadPool
        this.replicationMetadataManager = ReplicationMetadataManager(clusterService, client,
                ReplicationMetadataStore(client, clusterService, xContentRegistry))
        this.replicationSettings = ReplicationSettings(clusterService)
        return listOf(RemoteClusterRepositoriesService(repositoriesService, clusterService), replicationMetadataManager, replicationSettings)
    }

    override fun getGuiceServiceClasses(): Collection<Class<out LifecycleComponent>> {
        return listOf(Injectables::class.java,
                RemoteClusterRestoreLeaderService::class.java, RemoteClusterTranslogService::class.java)
    }

    override fun getActions(): List<ActionHandler<out ActionRequest, out ActionResponse>> {
        return listOf(ActionHandler(GetChangesAction.INSTANCE, TransportGetChangesAction::class.java),
            ActionHandler(ReplicateIndexAction.INSTANCE, TransportReplicateIndexAction::class.java),
            ActionHandler(ReplicateIndexMasterNodeAction.INSTANCE, TransportReplicateIndexMasterNodeAction::class.java),
            ActionHandler(ReplayChangesAction.INSTANCE, TransportReplayChangesAction::class.java),
            ActionHandler(GetStoreMetadataAction.INSTANCE, TransportGetStoreMetadataAction::class.java),
            ActionHandler(GetFileChunkAction.INSTANCE, TransportGetFileChunkAction::class.java),
            ActionHandler(UpdateAutoFollowPatternAction.INSTANCE, TransportUpdateAutoFollowPatternAction::class.java),
            ActionHandler(AutoFollowMasterNodeAction.INSTANCE, TransportAutoFollowMasterNodeAction::class.java),
            ActionHandler(StopIndexReplicationAction.INSTANCE, TransportStopIndexReplicationAction::class.java),
            ActionHandler(PauseIndexReplicationAction.INSTANCE, TransportPauseIndexReplicationAction::class.java),
            ActionHandler(ResumeIndexReplicationAction.INSTANCE, TransportResumeIndexReplicationAction::class.java),
            ActionHandler(UpdateIndexReplicationAction.INSTANCE, TransportUpdateIndexReplicationAction::class.java),
            ActionHandler(UpdateIndexBlockAction.INSTANCE, TransportUpddateIndexBlockAction::class.java),
            ActionHandler(ReleaseLeaderResourcesAction.INSTANCE, TransportReleaseLeaderResourcesAction::class.java),
            ActionHandler(UpdateMetadataAction.INSTANCE, TransportUpdateMetadataAction::class.java),
            ActionHandler(ValidatePermissionsAction.INSTANCE, TransportValidatePermissionsAction::class.java),
            ActionHandler(SetupChecksAction.INSTANCE, TransportSetupChecksAction::class.java),
            ActionHandler(UpdateReplicationStateAction.INSTANCE, TransportUpdateReplicationStateDetails::class.java),
            ActionHandler(ShardsInfoAction.INSTANCE, TranportShardsInfoAction::class.java),
            ActionHandler(ReplicationStatusAction.INSTANCE,TransportReplicationStatusAction::class.java)
        )
    }

    override fun getRestHandlers(settings: Settings, restController: RestController,
                                 clusterSettings: ClusterSettings?, indexScopedSettings: IndexScopedSettings,
                                 settingsFilter: SettingsFilter?,
                                 indexNameExpressionResolver: IndexNameExpressionResolver,
                                 nodesInCluster: Supplier<DiscoveryNodes>): List<RestHandler> {
        return listOf(ReplicateIndexHandler(),
            UpdateAutoFollowPatternsHandler(),
            PauseIndexReplicationHandler(),
            ResumeIndexReplicationHandler(),
            UpdateIndexHandler(),
            StopIndexReplicationHandler(),
            ReplicationStatusHandler())
    }

    override fun getExecutorBuilders(settings: Settings): List<ExecutorBuilder<*>> {
        return listOf(followerExecutorBuilder(settings), leaderExecutorBuilder(settings))
    }

    private fun followerExecutorBuilder(settings: Settings): ExecutorBuilder<*> {
        return ScalingExecutorBuilder(REPLICATION_EXECUTOR_NAME_FOLLOWER, 1, 10, TimeValue.timeValueMinutes(1), REPLICATION_EXECUTOR_NAME_FOLLOWER)
    }

    /**
     * Keeping the default configuration for threadpool in parity with search threadpool which is what we were using earlier.
     * https://github.com/opensearch-project/OpenSearch/blob/main/server/src/main/java/org/opensearch/threadpool/ThreadPool.java#L195
     */
    private fun leaderExecutorBuilder(settings: Settings): ExecutorBuilder<*> {
        val availableProcessors = OpenSearchExecutors.allocatedProcessors(settings)
        val leaderThreadPoolQueueSize = REPLICATION_LEADER_THREADPOOL_QUEUE_SIZE.get(settings)

        var leaderThreadPoolSize = REPLICATION_LEADER_THREADPOOL_SIZE.get(settings)
        leaderThreadPoolSize = if (leaderThreadPoolSize > 0) leaderThreadPoolSize else leaderThreadPoolSize(availableProcessors)

        return FixedExecutorBuilder(settings, REPLICATION_EXECUTOR_NAME_LEADER, leaderThreadPoolSize, leaderThreadPoolQueueSize, REPLICATION_EXECUTOR_NAME_LEADER)
    }

    fun leaderThreadPoolSize(allocatedProcessors: Int): Int {
        return allocatedProcessors * 3 / 2 + 1
    }

    override fun getPersistentTasksExecutor(clusterService: ClusterService, threadPool: ThreadPool, client: Client,
                                            settingsModule: SettingsModule,
                                            expressionResolver: IndexNameExpressionResolver)
        : List<PersistentTasksExecutor<*>> {
        return listOf(
            ShardReplicationExecutor(REPLICATION_EXECUTOR_NAME_FOLLOWER, clusterService, threadPool, client, replicationMetadataManager, replicationSettings),
            IndexReplicationExecutor(REPLICATION_EXECUTOR_NAME_FOLLOWER, clusterService, threadPool, client, replicationMetadataManager, replicationSettings, settingsModule),
            AutoFollowExecutor(REPLICATION_EXECUTOR_NAME_FOLLOWER, clusterService, threadPool, client, replicationMetadataManager, replicationSettings))
    }

    override fun getNamedWriteables(): List<NamedWriteableRegistry.Entry> {
        return listOf(
            NamedWriteableRegistry.Entry(PersistentTaskParams::class.java, ShardReplicationParams.NAME,
            // can't directly pass in ::ReplicationTaskParams due to https://youtrack.jetbrains.com/issue/KT-35912
            Writeable.Reader { inp -> ShardReplicationParams(inp) }),
            NamedWriteableRegistry.Entry(PersistentTaskState::class.java, ShardReplicationState.NAME,
            Writeable.Reader { inp -> ShardReplicationState.reader(inp) }),

            NamedWriteableRegistry.Entry(PersistentTaskParams::class.java, IndexReplicationParams.NAME,
                Writeable.Reader { inp -> IndexReplicationParams(inp) }),
            NamedWriteableRegistry.Entry(PersistentTaskState::class.java, IndexReplicationState.NAME,
                Writeable.Reader { inp -> IndexReplicationState.reader(inp) }),

            NamedWriteableRegistry.Entry(PersistentTaskParams::class.java, AutoFollowParams.NAME,
                                         Writeable.Reader { inp -> AutoFollowParams(inp) }),

            NamedWriteableRegistry.Entry(Metadata.Custom::class.java, ReplicationStateMetadata.NAME,
                Writeable.Reader { inp -> ReplicationStateMetadata(inp) }),
            NamedWriteableRegistry.Entry(NamedDiff::class.java, ReplicationStateMetadata.NAME,
                Writeable.Reader { inp -> ReplicationStateMetadata.Diff(inp) })

        )
    }

    override fun getNamedXContent(): List<NamedXContentRegistry.Entry> {
        return listOf(
            NamedXContentRegistry.Entry(PersistentTaskParams::class.java,
                    ParseField(IndexReplicationParams.NAME),
                    CheckedFunction { parser: XContentParser -> IndexReplicationParams.fromXContent(parser)}),
            NamedXContentRegistry.Entry(PersistentTaskState::class.java,
                    ParseField(IndexReplicationState.NAME),
                    CheckedFunction { parser: XContentParser -> IndexReplicationState.fromXContent(parser)}),
            NamedXContentRegistry.Entry(PersistentTaskParams::class.java,
                    ParseField(ShardReplicationParams.NAME),
                    CheckedFunction { parser: XContentParser -> ShardReplicationParams.fromXContent(parser)}),
            NamedXContentRegistry.Entry(PersistentTaskState::class.java,
                    ParseField(ShardReplicationState.NAME),
                    CheckedFunction { parser: XContentParser -> ShardReplicationState.fromXContent(parser)}),
            NamedXContentRegistry.Entry(PersistentTaskParams::class.java,
                    ParseField(AutoFollowParams.NAME),
                    CheckedFunction { parser: XContentParser -> AutoFollowParams.fromXContent(parser)}),
            NamedXContentRegistry.Entry(Metadata.Custom::class.java,
                    ParseField(ReplicationStateMetadata.NAME),
                    CheckedFunction { parser: XContentParser -> ReplicationStateMetadata.fromXContent(parser)})
        )
    }

    override fun getSettings(): List<Setting<*>> {
        return listOf(REPLICATED_INDEX_SETTING, REPLICATION_CHANGE_BATCH_SIZE, REPLICATION_LEADER_THREADPOOL_SIZE,
                REPLICATION_LEADER_THREADPOOL_QUEUE_SIZE, REPLICATION_PARALLEL_READ_PER_SHARD,
                REPLICATION_FOLLOWER_RECOVERY_CHUNK_SIZE, REPLICATION_FOLLOWER_RECOVERY_PARALLEL_CHUNKS,
                REPLICATION_PARALLEL_READ_POLL_DURATION, REPLICATION_AUTOFOLLOW_REMOTE_INDICES_POLL_DURATION,
                REPLICATION_AUTOFOLLOW_REMOTE_INDICES_RETRY_POLL_DURATION, REPLICATION_METADATA_SYNC_INTERVAL)
    }

    override fun getInternalRepositories(env: Environment, namedXContentRegistry: NamedXContentRegistry,
                                         clusterService: ClusterService, recoverySettings: RecoverySettings): Map<String, Repository.Factory> {
        val repoFactory = Repository.Factory { repoMetadata: RepositoryMetadata ->
            RemoteClusterRepository(repoMetadata, client, clusterService, recoverySettings, replicationMetadataManager, replicationSettings) }
        return mapOf(REMOTE_REPOSITORY_TYPE to repoFactory)
    }

    override fun getEngineFactory(indexSettings: IndexSettings): Optional<EngineFactory> {
        return if (indexSettings.settings.get(REPLICATED_INDEX_SETTING.key) != null) {
            Optional.of(EngineFactory { config -> org.opensearch.replication.ReplicationEngine(config) })
        } else {
            Optional.empty()
        }
    }

    override fun onIndexModule(indexModule: IndexModule) {
        super.onIndexModule(indexModule)
        if (indexModule.settings.get(REPLICATED_INDEX_SETTING.key) != null) {
            indexModule.addIndexEventListener(IndexCloseListener)
        }
    }
}
