package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class CarLifePortPolicyTest {
    private fun table(port: Int, uid: Int, state: String = "0A") = """
        sl local_address rem_address st tx_queue rx tr tm retr uid
        0: 0100007F:${port.toString(16)} 00000000:0000 $state 00000000:00000000 00:00000000 00000000 $uid 0 123
    """.trimIndent()

    @Test fun unreadableProcDoesNotMeanForeignOrFree() {
        for (input in listOf(null, "cat: Permission denied", "")) {
            assertEquals(CarLifePorts.State.UNKNOWN, CarLifePorts.inspect(input, 7240, 10424).state)
        }
        assertEquals(CarLifePorts.State.NOT_LISTENING,
            CarLifePorts.inspect(table(7240, 0, "06"), 7240, 10424).state)
    }

    @Test fun exactLocalOwnerIsRequired() {
        assertEquals(CarLifePorts.State.OWNED, CarLifePorts.inspect(table(7240, 10424), 7240, 10424).state)
        assertEquals(CarLifePorts.State.FOREIGN, CarLifePorts.inspect(table(7240, 10425), 7240, 10424).state)
        assertEquals(CarLifePorts.State.FOREIGN,
            CarLifePorts.inspect(table(7240, 10424).replace("0100007F", "0101A8C0"), 7240, 10424).state)
    }

    @Test fun ipv6LoopbackAloneCannotAuthorizeAnIpv4Dial() {
        assertEquals(CarLifePorts.State.FOREIGN,
            CarLifePorts.inspect(table(7240, 10424).replace("0100007F", "00000000000000000000000001000000"), 7240, 10424).state)
        assertEquals(CarLifePorts.State.OWNED,
            CarLifePorts.inspect(table(7240, 10424).replace("0100007F", "0000000000000000FFFF00000100007F"), 7240, 10424).state)
    }

    @Test fun cmdAndVideoAloneCannotAuthorizeConnectingToForeignAudioOrControl() {
        val ports = CarLifePorts.standardPorts.associateWith {
            CarLifePorts.inspect(table(it, 10424), it, 10424)
        }
        assertTrue(CarLifePorts.serverReady(ports))
        for (port in CarLifePorts.standardPorts) {
            for (state in listOf(CarLifePorts.State.UNKNOWN, CarLifePorts.State.NOT_LISTENING, CarLifePorts.State.FOREIGN)) {
                assertFalse("port=$port state=$state", CarLifePorts.serverReady(ports + (port to CarLifePorts.Inspection(state))))
            }
            assertFalse(CarLifePorts.serverReady(ports - port))
        }
    }

    @Test fun vivoServerNeverTurnsIntoACompetingGateway() {
        val empty = CarLifePorts.standardPorts.associateWith { CarLifePorts.Inspection(CarLifePorts.State.NOT_LISTENING) }
        assertEquals(CarLifePorts.Action.WAIT, CarLifePorts.action(empty, serverRequired = true, expired = false))
        assertEquals(CarLifePorts.Action.INCOMPLETE, CarLifePorts.action(empty, serverRequired = true, expired = true))
        assertEquals(CarLifePorts.Action.LISTEN, CarLifePorts.action(empty, serverRequired = false, expired = true))
        val partial = empty + (7240 to CarLifePorts.Inspection(CarLifePorts.State.OWNED))
        assertEquals(CarLifePorts.Action.INCOMPLETE, CarLifePorts.action(partial, serverRequired = false, expired = true))
        val unknown = empty - 7240
        assertEquals(CarLifePorts.Action.NEEDS_ACCESS, CarLifePorts.action(unknown, serverRequired = true, expired = true))
        val foreign = empty + (9340 to CarLifePorts.Inspection(CarLifePorts.State.FOREIGN))
        assertEquals(CarLifePorts.Action.CONFLICT, CarLifePorts.action(foreign, serverRequired = true, expired = false))
    }
}
