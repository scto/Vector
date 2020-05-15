package com.haroldadmin.vector.state

import com.haroldadmin.vector.VectorState
import com.haroldadmin.vector.loggers.Logger
import com.haroldadmin.vector.loggers.logd
import com.haroldadmin.vector.loggers.logv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext

/**
 * A [StateProcessor] which processes jobs sent to it sequentially, prioritizing state reducers over actions.
 *
 * This implementation is based on the [select] statement rather than an [kotlinx.coroutines.channels.actor].
 * Additionally, it supports startup under a lazy mode to facilitate testing. If it is created under lazy mode, it
 * does not begin processing jobs sent to it until the [start] method is called.
 *
 * Benchmarks suggest that this implementation is about 50% faster than the Actors based implementation.
 *
 * @param shouldStartImmediately if true, jobs sent to this processor begin processing immediately after creation, or
 * only after [start] is called otherwise
 * @param stateHolder the [StateHolder] where this processor can store and read the current state
 * @param logger a [Logger] to log miscellaneous information
 * @param coroutineContext The [CoroutineContext] under which this processor will execute jobs sent to it
 */
internal class SelectBasedStateProcessor<S : VectorState>(
    shouldStartImmediately: Boolean = false,
    private val stateHolder: StateHolder<S>,
    private val logger: Logger,
    coroutineContext: CoroutineContext
) : StateProcessor<S> {

    /**
     * [CoroutineScope] for managing coroutines in this state processor
     */
    private val processorScope = CoroutineScope(coroutineContext)

    /**
     * Queue for state reducers.
     * Has unlimited capacity so that sending new elements to it is not a blocking operation
     **/
    private val setStateChannel: Channel<reducer<S>> = Channel(Channel.UNLIMITED)

    /**
     * Queue for actions on the current state.
     * Has unlimited capacity so that sending new elements to it is not a blocking operation
     **/
    private val getStateChannel: Channel<action<S>> = Channel(Channel.UNLIMITED)

    /**
     * A convenience utility to check if any of the queues contain jobs to be processed
     */
    private val hasMoreJobs: Boolean
        get() = !setStateChannel.isEmpty || !getStateChannel.isEmpty

    init {
        if (shouldStartImmediately) {
            start()
        } else {
            logger.logv { "Starting in Lazy mode. Call start()/drain() to begin processing actions and reducers" }
        }
    }

    override fun offerSetAction(reducer: suspend S.() -> S) {
        if (processorScope.isActive && !setStateChannel.isClosedForSend) {
            // TODO Look for a solution to the case where the channel could be closed between the check and this offer
            //  statement
            setStateChannel.offer(reducer)
        }
    }

    override fun offerGetAction(action: suspend (S) -> Unit) {
        if (processorScope.isActive && !getStateChannel.isClosedForSend) {
            // TODO Look for a solution to the case where the channel could be closed between the check and this offer
            //  statement
            getStateChannel.offer(action)
        }
    }

    override fun clearProcessor() {
        if (processorScope.isActive) {
            logger.logd { "Clearing StateProcessor $this" }
            processorScope.cancel()
            setStateChannel.close()
            getStateChannel.close()
        }
    }

    override fun start() {
        processorScope.launch {
            while (isActive) {
                selectJob()
            }
        }
    }

    override suspend fun drain() {
        do {
            coroutineScope {
                // Process all jobs currently in the queues
                while (hasMoreJobs && processorScope.isActive) {
                    selectJob(sideEffectScope = this)
                }
            }
        } while (hasMoreJobs && processorScope.isActive) // Nested jobs could have filled queues again, so repeat the process
    }

    /**
     * Waits for values from [setStateChannel] and [getStateChannel] simultaneously, prioritizing set-state
     * jobs over get-state jobs. State reducers are processed immediately and the new state produced by them is
     * sent to the [StateHolder]. State actions are processed in a separate coroutine, so that the [select] statement
     * does not block on long-running actions. The coroutine for processing the state-action is launched in
     * [sideEffectScope].
     */
    private suspend fun selectJob(sideEffectScope: CoroutineScope = processorScope) {
        select<Unit> {
            setStateChannel.onReceive { reducer ->
                val newState = stateHolder.state.reducer()
                stateHolder.updateState(newState)
            }
            getStateChannel.onReceive { action ->
                sideEffectScope.launch {
                    action.invoke(stateHolder.state)
                }
            }
        }
    }
}
