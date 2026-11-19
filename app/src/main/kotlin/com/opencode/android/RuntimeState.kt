package com.opencode.android

import java.util.Collections

/**
 * The lifecycle of the OpenCode runtime, shared by the foreground service, the watchdog and the
 * WebView shell. It is deliberately a small pure-Kotlin state machine: every component observes
 * the same transitions instead of keeping its own flags and guessing the state from a TCP probe.
 *
 * [generation] is the identity of the currently running server. A restart increments it exactly
 * once, which lets the WebView invalidate cookies and pairing tokens without treating the change
 * as a transport failure.
 */
enum class RuntimePhase {
    /** No runtime contract has been published yet. */
    ABSENT,

    /** Bootstrap/self-heal is running; the native install card is authoritative. */
    PREPARING,

    /** The process was spawned but `/api/info` has not answered yet. */
    STARTING,

    /** `/api/info` answered 200 with the expected credential. Safe to reveal the WebView. */
    HEALTHY,

    /** The process is still alive but the health probe failed. */
    DEGRADED,

    /** A restart is in progress; [RuntimeState.generation] is already the new one. */
    RESTARTING,

    /** The user explicitly stopped the server. Every automatic guard must stand down. */
    STOPPED,
}

/** What happened to the runtime. Only [RuntimeStateMachine] produces new states. */
enum class RuntimeEvent {
    BOOTSTRAP_STARTED,
    RUNTIME_READY,
    SERVER_SPAWNED,
    HEALTH_OK,
    HEALTH_LOST,
    HEALTH_RECOVERED,
    RESTART_REQUESTED,
    STOP_REQUESTED,
    START_REQUESTED,
}

/** Immutable snapshot published to the activity, the notification and the WebView shell. */
data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.ABSENT,
    val generation: Int = 0,
    val reason: String = "",
)

/**
 * Deterministic transition function. Kept free of Android references so it can be unit tested
 * on the JVM and reused by the service without depending on a particular process instance.
 */
class RuntimeStateMachine(initial: RuntimeState = RuntimeState()) {

    @Volatile
    var state: RuntimeState = initial
        private set

    /** Apply [event] and return the resulting snapshot. */
    fun transition(event: RuntimeEvent): RuntimeState = synchronized(this) {
        val current = state
        val next = when (event) {
            RuntimeEvent.STOP_REQUESTED -> RuntimeState(RuntimePhase.STOPPED, current.generation, "user")
            RuntimeEvent.START_REQUESTED -> if (current.phase == RuntimePhase.STOPPED) {
                RuntimeState(RuntimePhase.PREPARING, current.generation, "user")
            } else {
                current
            }

            RuntimeEvent.BOOTSTRAP_STARTED -> when (current.phase) {
                RuntimePhase.ABSENT, RuntimePhase.PREPARING -> current.copy(
                    phase = RuntimePhase.PREPARING,
                    reason = "bootstrap",
                )
                else -> current
            }

            RuntimeEvent.RUNTIME_READY -> when (current.phase) {
                RuntimePhase.ABSENT, RuntimePhase.PREPARING -> current.copy(
                    phase = RuntimePhase.STARTING,
                    reason = "runtime-ready",
                )
                else -> current
            }

            RuntimeEvent.SERVER_SPAWNED -> when (current.phase) {
                RuntimePhase.PREPARING, RuntimePhase.RESTARTING, RuntimePhase.HEALTHY,
                RuntimePhase.DEGRADED, RuntimePhase.STARTING,
                -> current.copy(phase = RuntimePhase.STARTING, reason = "spawned")

                else -> current
            }

            RuntimeEvent.HEALTH_OK -> when (current.phase) {
                RuntimePhase.STOPPED -> current
                RuntimePhase.RESTARTING -> current.copy(phase = RuntimePhase.HEALTHY, reason = "healthy")
                else -> current.copy(phase = RuntimePhase.HEALTHY, reason = "healthy")
            }

            RuntimeEvent.HEALTH_LOST -> when (current.phase) {
                RuntimePhase.HEALTHY -> current.copy(phase = RuntimePhase.DEGRADED, reason = "health-lost")
                else -> current
            }

            RuntimeEvent.HEALTH_RECOVERED -> when (current.phase) {
                RuntimePhase.DEGRADED -> current.copy(phase = RuntimePhase.HEALTHY, reason = "recovered")
                else -> current
            }

            RuntimeEvent.RESTART_REQUESTED -> if (current.phase == RuntimePhase.STOPPED) {
                current
            } else {
                RuntimeState(
                    RuntimePhase.RESTARTING,
                    current.generation + 1,
                    "restart",
                )
            }
        }
        state = next
        next
    }

    /** Force a snapshot, used when an adopted server is discovered after a process death. */
    fun publish(snapshot: RuntimeState): RuntimeState = synchronized(this) {
        state = snapshot
        snapshot
    }
}

/**
 * Process-wide source of truth. The service runs in the same app process as the activity, so a
 * single holder lets the notification, the watchdog and the WebView observe the same transitions
 * instead of polling independent flags.
 */
object RuntimeStateHolder {

    private val machine = RuntimeStateMachine()
    private val listeners = Collections.synchronizedList(
        ArrayList<(RuntimeState) -> Unit>(),
    )

    /** Current snapshot, safe to read from any thread. */
    val state: RuntimeState get() = machine.state

    /** Apply an event and notify observers. */
    fun transition(event: RuntimeEvent): RuntimeState {
        val next = machine.transition(event)
        notifyListeners(next)
        return next
    }

    /** Publish a snapshot (used when a running server is adopted after a process death). */
    fun publish(snapshot: RuntimeState): RuntimeState {
        val next = machine.publish(snapshot)
        notifyListeners(next)
        return next
    }

    fun addListener(listener: (RuntimeState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (RuntimeState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(snapshot: RuntimeState) {
        listeners.toList().forEach { listener ->
            try {
                listener(snapshot)
            } catch (e: Exception) {
                // One broken observer must not stop the supervisor.
            }
        }
    }
}

/**
 * The identity the WebView needs in order to decide whether a cookie/token is still valid.
 * A generation change keeps the chat the user was reading and drops the stale credential.
 */
data class SessionRef(
    val generation: Int,
    val id: String? = null,
    val title: String = "",
    val token: String? = null,
) {
    /** Rebind the session to a new server without carrying the old credential over. */
    fun onGenerationChanged(newGeneration: Int): SessionRef = copy(
        generation = newGeneration,
        token = null,
    )

    /** True when the session belongs to the generation currently published by the supervisor. */
    fun matches(generation: Int, id: String?): Boolean =
        this.generation == generation && this.id != null && this.id == id
}
