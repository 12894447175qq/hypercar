package io.github.hypercar.iap2
import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test

/** Isolated diagnosis fixture: only external receiver timing is synthetic. */
class EveningMfiReplayTest {
    private class Peer(val identity: String, val localCancel: Boolean) : Iap2LinkChannel {
        private val parser = Iap2Csm.Parser()
        private val incoming = ArrayDeque<ByteArray>()
        val challenges = mutableListOf<Int>()
        var alive = true
        override val isAlive: Boolean get() = alive
        override fun awaitReady(timeoutMillis: Long) = true
        override fun peerMaxControlPayload(): Int? = null
        override fun close() { alive = false }
        override fun sendControl(bytes: ByteArray, awaitAck: Boolean): Boolean {
            if (!alive) return false
            for (frame in parser.offer(bytes)) {
                fun reply(id: Int, body: ByteArray) { incoming.add(Iap2Csm.encode(id, body)) }
                when(frame.messageId) {
                    0x1d00 -> reply(0x1d01, Iap2Csm.string(0,identity)+Iap2Csm.string(1,"AUDI3260")+
                        Iap2Csm.string(2,"DELPHI")+Iap2Csm.u16List(6,intArrayOf(0xaa01,0xaa03,0x5703))+
                        Iap2Csm.u16List(7,intArrayOf(0x5702)))
                    0xaa00 -> reply(0xaa01, byteArrayOf(1,2,3))
                    0xaa02 -> {
                        val length = Iap2Csm.parseParams(frame.body).single().value.size
                        challenges.add(length)
                        if (localCancel) close()
                        // The 20-byte response arrives only after a 32-byte retry was sent.
                        // The receiver never signs the new 32-byte challenge.
                        else if (challenges.size == 2) reply(0xaa03, Iap2Csm.param(0,ByteArray(128){20}))
                    }
                    0x5702 -> reply(0x5703,Iap2Csm.string(1,"synthetic-test")+
                        Iap2Csm.string(2,"synthetic-only")+Iap2Csm.u8(3,2))
                }
            }
            return true
        }
        override fun recvControl(timeoutMillis: Long): ByteArray? {
            if(incoming.isNotEmpty()) return incoming.removeFirst()
            if(alive && timeoutMillis>0) Thread.sleep(minOf(timeoutMillis,5))
            return null
        }
    }
    @Test fun delayed20ReplyMustNotOverwriteKnown20With32() {
        val peer=Peer("evening-delayed-20",false)
        val identity="${peer.identity} AUDI3260 DELPHI"
        MfiLengthMemory.recordSuccess(null,identity,20)
        Iap2SenderSession(peer,{ println(it) },onEndpoint={},profile=HeadUnitProfiles.AUDI_MIB3).run(15_000)
        assertEquals(listOf(20,32),peer.challenges)
        assertEquals("A reply to the old request must not certify the new length",20,
            MfiLengthMemory.load(null,identity).succeeded)
    }
    @Test fun localCloseMustNotEraseKnown20OrMarkItRejected() {
        val peer=Peer("evening-local-cancel",true)
        val identity="${peer.identity} AUDI3260 DELPHI"
        MfiLengthMemory.recordSuccess(null,identity,20)
        runCatching { Iap2SenderSession(peer,{ println(it) },onEndpoint={},profile=HeadUnitProfiles.AUDI_MIB3).run(1_000) }
        assertEquals(listOf(20),peer.challenges)
        assertEquals("Local cancellation is not a receiver rejection",MfiLengthMemory.Entry(succeeded=20),
            MfiLengthMemory.load(null,identity))
    }
}
