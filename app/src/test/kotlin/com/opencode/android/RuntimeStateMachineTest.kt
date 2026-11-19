package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {

    @Test
    fun bootReachesHealthyThroughStarting() {
        val machine = RuntimeStateMachine()
        assertEquals(RuntimePhase.ABSENT, machine.state.phase)

        machine.transition(RuntimeEvent.BOOTSTRAP_STARTED)
        assertEquals(RuntimePhase.PREPARING, machine.state.phase)

        machine.transition(RuntimeEvent.RUNTIME_READY)
        assertEquals(RuntimePhase.STARTING, machine.state.phase)

        val healthy = machine.transition(RuntimeEvent.HEALTH_OK)
        assertEquals(RuntimePhase.HEALTHY, healthy.phase)
        assertEquals(0, healthy.generation)
    }

    @Test
    fun healthLossIsDegradedAndRecoveryIsHealthy() {
        val machine = RuntimeStateMachine(RuntimeState(RuntimePhase.HEALTHY, 4))
        machine.transition(RuntimeEvent.HEALTH_LOST)
        assertEquals(RuntimePhase.DEGRADED, machine.state.phase)
        assertEquals(4, machine.state.generation)

        machine.transition(RuntimeEvent.HEALTH_RECOVERED)
        assertEquals(RuntimePhase.HEALTHY, machine.state.phase)
        assertEquals(4, machine.state.generation)
    }

    @Test
    fun restartIncrementsGenerationExactlyOnce() {
        val machine = RuntimeStateMachine(RuntimeState(RuntimePhase.HEALTHY, 7))
        machine.transition(RuntimeEvent.RESTART_REQUESTED)
        assertEquals(RuntimePhase.RESTARTING, machine.state.phase)
        assertEquals(8, machine.state.generation)

        machine.transition(RuntimeEvent.RESTART_REQUESTED)
        assertEquals(8, machine.state.generation)

        machine.transition(RuntimeEvent.HEALTH_OK)
        assertEquals(RuntimePhase.HEALTHY, machine.state.phase)
        assertEquals(8, machine.state.generation)
    }

    @Test
    fun stoppedWinsOverEveryAutomaticEvent() {
        val machine = RuntimeStateMachine(RuntimeState(RuntimePhase.HEALTHY, 2))
        machine.transition(RuntimeEvent.STOP_REQUESTED)
        assertEquals(RuntimePhase.STOPPED, machine.state.phase)

        for (event in listOf(
            RuntimeEvent.HEALTH_LOST,
            RuntimeEvent.HEALTH_RECOVERED,
            RuntimeEvent.HEALTH_OK,
            RuntimeEvent.SERVER_SPAWNED,
            RuntimeEvent.RESTART_REQUESTED,
            RuntimeEvent.RUNTIME_READY,
        )) {
            machine.transition(event)
            assertEquals(RuntimePhase.STOPPED, machine.state.phase)
            assertEquals(2, machine.state.generation)
        }
    }

    @Test
    fun startFromStoppedGoesToPreparing() {
        val machine = RuntimeStateMachine(RuntimeState(RuntimePhase.STOPPED, 3))
        machine.transition(RuntimeEvent.START_REQUESTED)
        assertEquals(RuntimePhase.PREPARING, machine.state.phase)
        assertEquals(3, machine.state.generation)
    }

    @Test
    fun sessionRefKeepsIdButDropsCredentialOnGenerationChange() {
        val ref = SessionRef(1, "ses_abc", "Chat", "token")
        val rebound = ref.onGenerationChanged(2)
        assertEquals(1, ref.generation)
        assertEquals("token", ref.token)
        assertEquals(2, rebound.generation)
        assertEquals("ses_abc", rebound.id)
        assertNull(rebound.token)
        assertTrue(rebound.matches(2, "ses_abc"))
        assertTrue(!rebound.matches(1, "ses_abc"))
    }
}
