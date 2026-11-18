package com.squaretoast.pos.sync

import androidx.room.withTransaction
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.OutboxState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class Mutation(
    val mutationId: String,
    val aggregateId: String,
    val type: String,
    val deviceSeq: Long,
    val payloadJson: String,
)

enum class MutationStatus { APPLIED, DUPLICATE, CONFLICT }

@Serializable
data class MutationResult(val mutationId: String, val status: MutationStatus, val reason: String? = null)

/** The cloud's mutation endpoint. It must dedupe by mutationId: a resend returns DUPLICATE, never re-applies. */
fun interface SyncApi {
    suspend fun push(batch: List<Mutation>): List<MutationResult>
}

sealed interface DrainOutcome {
    data class Drained(val sent: Int, val conflicts: Int) : DrainOutcome
    data class RetryLater(val cause: Throwable) : DrainOutcome
}

/**
 * Sends PENDING outbox rows in deviceSeq order, [batchSize] at a time. APPLIED and DUPLICATE both mean the server
 * has the mutation, so the row is DONE. CONFLICT rows are kept for a blocking alert and never resent. Any failure
 * puts the batch back to PENDING; resending is safe because the server dedupes by mutationId.
 */
class OutboxDrainer(private val db: AppDatabase, private val api: SyncApi, private val batchSize: Int = 50) {

    suspend fun drain(): DrainOutcome {
        val outbox = db.outboxDao()
        outbox.resetInflight() // rows a killed process left in flight
        var sent = 0
        var conflicts = 0
        while (true) {
            val rows = outbox.pending(batchSize)
            if (rows.isEmpty()) return DrainOutcome.Drained(sent, conflicts)
            val ids = rows.map { it.mutationId }
            outbox.setStates(ids, OutboxState.INFLIGHT)
            val results = try {
                api.push(rows.map { Mutation(it.mutationId, it.aggregateId, it.type, it.deviceSeq, it.payloadJson) })
                    .associateBy { it.mutationId }
            } catch (e: CancellationException) {
                outbox.releaseForRetry(ids, "cancelled")
                throw e
            } catch (e: Exception) {
                outbox.releaseForRetry(ids, e.javaClass.simpleName)
                return DrainOutcome.RetryLater(e)
            }
            val missing = ids.filter { it !in results }
            if (missing.isNotEmpty()) {
                outbox.releaseForRetry(ids, "incomplete response")
                return DrainOutcome.RetryLater(IllegalStateException("server omitted ${missing.size} results"))
            }
            db.withTransaction {
                for (id in ids) {
                    val r = results.getValue(id)
                    when (r.status) {
                        MutationStatus.APPLIED, MutationStatus.DUPLICATE -> outbox.setState(id, OutboxState.DONE)
                        MutationStatus.CONFLICT -> { outbox.markConflict(id, r.reason); conflicts++ }
                    }
                }
            }
            sent += rows.size
        }
    }
}
