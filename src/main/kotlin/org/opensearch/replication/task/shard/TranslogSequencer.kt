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

package org.opensearch.replication.task.shard

import org.opensearch.replication.ReplicationException
import org.opensearch.replication.action.changes.GetChangesResponse
import org.opensearch.replication.action.replay.ReplayChangesAction
import org.opensearch.replication.action.replay.ReplayChangesRequest
import org.opensearch.replication.metadata.store.ReplicationMetadata
import org.opensearch.replication.util.suspendExecute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.launch
import org.opensearch.client.Client
import org.opensearch.common.logging.Loggers
import org.opensearch.index.shard.ShardId
import org.opensearch.index.translog.Translog
import org.opensearch.tasks.TaskId
import java.util.concurrent.ConcurrentHashMap

/**
 * A TranslogSequencer allows multiple producers of [Translog.Operation]s to write them in sequence number order to an
 * index.  It internally uses an [actor] to serialize writes to the index. Producer can call the [send] method
 * to add a batch of operations to the queue.  If the queue is full the producer will be suspended.  Operations can be
 * sent out of order i.e. the operation with sequence number 2 can be sent before the operation with sequence number 1.
 * In this case the Sequencer will internally buffer the operations that cannot be delivered until the missing in-order
 * operations arrive.
 *
 * This uses the ObsoleteCoroutinesApi actor API.  As described in the [actor] docs there is no current replacement for
 * this API and a new one is being worked on to which we can migrate when needed.
 */
@ObsoleteCoroutinesApi
class TranslogSequencer(scope: CoroutineScope, private val replicationMetadata: ReplicationMetadata,
                        private val followerShardId: ShardId,
                        private val leaderAlias: String, private val leaderIndexName: String,
                        private val parentTaskId: TaskId, private val client: Client, initialSeqNo: Long) {

    private val unAppliedChanges = ConcurrentHashMap<Long, GetChangesResponse>()
    private val log = Loggers.getLogger(javaClass, followerShardId)!!
    private val completed = CompletableDeferred<Unit>()

    private val sequencer = scope.actor<Unit>(capacity = Channel.UNLIMITED) {
        // Exceptions thrown here will mark the channel as failed and the next attempt to send to the channel will
        // raise the same exception.  See [SendChannel.close] method for details.
        var highWatermark = initialSeqNo
        for (m in channel) {
            while (unAppliedChanges.containsKey(highWatermark + 1)) {
                val next = unAppliedChanges.remove(highWatermark + 1)!!
                val replayRequest = ReplayChangesRequest(followerShardId, next.changes, next.maxSeqNoOfUpdatesOrDeletes,
                                                         leaderAlias, leaderIndexName)
                replayRequest.parentTask = parentTaskId
                launch {
                    val replayResponse = client.suspendExecute(replicationMetadata, ReplayChangesAction.INSTANCE, replayRequest)
                    if (replayResponse.shardInfo.failed > 0) {
                        replayResponse.shardInfo.failures.forEachIndexed { i, failure ->
                            log.error("Failed replaying changes. Failure:$i:$failure")
                        }
                        throw org.opensearch.replication.ReplicationException("failed to replay changes", replayResponse.shardInfo.failures)
                    }
                }
                highWatermark = next.changes.lastOrNull()?.seqNo() ?: highWatermark
            }
        }
        completed.complete(Unit)
    }

    suspend fun close() {
        sequencer.close()
        completed.await()
    }

    suspend fun send(changes : GetChangesResponse) {
        unAppliedChanges[changes.fromSeqNo] = changes
        sequencer.send(Unit)
    }
}
