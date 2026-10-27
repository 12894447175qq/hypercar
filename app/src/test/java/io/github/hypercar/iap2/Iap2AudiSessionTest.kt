package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test

/** Synthetic CSM replay of the observed MIB3 sequence, without real certificates/credentials. */
class Iap2AudiSessionTest {
    private class Receiver(private val signatureLength: Int, private val emitWifi: Boolean = true) : Iap2LinkChannel {
        val challenges = mutableListOf<Int>()
        private val incoming = ArrayDeque<ByteArray>()
        private val parser = Iap2Csm.Parser()
        override val isAlive = true
        override fun awaitReady(timeoutMillis: Long) = true
        override fun peerMaxControlPayload(): Int? = null
        override fun close() = Unit
        override fun sendControl(bytes: ByteArray, awaitAck: Boolean): Boolean {
            for (frame in parser.offer(bytes)) {
                fun respond(id: Int, body: ByteArray) { incoming.add(Iap2Csm.encode(id, body)) }
                when (frame.messageId) {
                    0x1d00 -> respond(0x1d01,
                        Iap2Csm.string(0, "Audi-fixture-$signatureLength-$emitWifi") + Iap2Csm.string(1, "AUDI3260") +
                            Iap2Csm.string(2, "DELPHI") + Iap2Csm.u16List(6, intArrayOf(0xaa01, 0xaa03, 0x5703)) +
                            Iap2Csm.u16List(7, intArrayOf(0x5702)))
                    0xaa00 -> respond(0xaa01, byteArrayOf(1, 2, 3))
                    0xaa02 -> {
                        val length = Iap2Csm.parseParams(frame.body).single().value.size
                        challenges.add(length)
                        respond(if (length == signatureLength) 0xaa03 else 0xaa04, byteArrayOf(1))
                    }
                    0x5702 -> if (emitWifi) respond(0x5703,
                        Iap2Csm.string(1, "Audi-fixture") + Iap2Csm.string(2, "synthetic-only"))
                }
            }
            return true
        }
        override fun recvControl(timeoutMillis: Long): ByteArray? {
            if (incoming.isNotEmpty()) return incoming.removeFirst()
            if (timeoutMillis > 0) Thread.sleep(minOf(timeoutMillis, 2))
            return null
        }
    }

    @Test fun audiGetsSignatureAndWifiWithout4301() {
        val receiver = Receiver(20)
        val logs = mutableListOf<String>()
        val result = Iap2SenderSession(receiver, logs::add, onEndpoint = {}, profile = HeadUnitProfiles.AUDI_MIB3).run(1_000)
        assertEquals(listOf(20), receiver.challenges)
        assertTrue(logs.any { "rx=0xaa03" in it })
        assertEquals("Audi-fixture", result.ssid)
        assertTrue(result.ipAddresses.isEmpty()) // Network discovery, rather than a fabricated 0x4301, supplies the IP.
    }

    @Test fun remembered32SuccessStillWinsOnAudi() {
        MfiLengthMemory.recordSuccess(null, "Audi-fixture-32-true AUDI3260 DELPHI", 32)
        val receiver = Receiver(32)
        Iap2SenderSession(receiver, {}, onEndpoint = {}, profile = HeadUnitProfiles.AUDI_MIB3).run(1_000)
        assertEquals(listOf(32), receiver.challenges)
    }

    @Test fun declaredWifiTimeoutIsNotReportedAsMissingCapability() {
        val logs = mutableListOf<String>()
        Iap2SenderSession(Receiver(20, emitWifi = false), logs::add, onEndpoint = {}, profile = HeadUnitProfiles.AUDI_MIB3).run(100)
        assertTrue(logs.any { "0x5702 可收=true" in it && "0x5703 可发=true" in it })
        assertFalse(logs.any { "既未声明 0x5702" in it })
    }
}
