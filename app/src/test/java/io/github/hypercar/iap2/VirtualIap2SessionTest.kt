package io.github.hypercar.iap2

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class VirtualIap2SessionTest {
    private class Fixture {
        val output = ArrayDeque<ByteArray>()
        val logs = mutableListOf<String>()
        var clock = 0L
        val session = VirtualIap2Session({ output.add(it); true }, logs::add, now = { clock }, epoch = { 1_793_106_514_000 })
        var peerSequence = 20
        var phoneSequence = 0
        val seen = mutableListOf<Iap2Csm.Frame>()

        fun connect() {
            session.arm()
            assertArrayEquals(Iap2LinkEngine.MARKER, output.removeFirst())
            val sync = Iap2LinkEngine.SynchronizationPayload(127, 4096, 1000, 500, 4, 3,
                listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 1), Iap2LinkEngine.SessionDescriptor(2, 2, 1)))
            session.feed(packet(0x80, peerSequence, 0, 0, sync.encode()))
            val echo = output.removeFirst()
            assertEquals(0xc0, echo[4].toInt() and 255)
            phoneSequence = echo[5].toInt() and 255
            session.feed(packet(0x40, ++peerSequence, phoneSequence, 0))
            drain()
            assertTrue(seen.any { it.messageId == 0x1d00 })
        }
        fun receive(id: Int, body: ByteArray = byteArrayOf()) {
            session.feed(packet(0x40, ++peerSequence, phoneSequence, 1, Iap2Csm.encode(id, body)))
            drain()
        }
        fun drain() {
            var guard = 0
            while (output.isNotEmpty()) {
                check(++guard < 1000) { "ACK loop" }
                val bytes = output.removeFirst()
                var at = 0
                while (at + 9 <= bytes.size) {
                    if (bytes[at] != 0xff.toByte() || bytes[at + 1] != 0x5a.toByte()) { at++; continue }
                    val len = ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
                    assertEquals(0, bytes.copyOfRange(at, at + 9).sumOf { it.toInt() and 255 } and 255)
                    val control = bytes[at + 4].toInt() and 255
                    phoneSequence = bytes[at + 5].toInt() and 255
                    if (len > 9 && control and 0x80 == 0) {
                        assertEquals(1, bytes[at + 7].toInt() and 255) // 采纳 MIB3 控制会话号，不能写死 10。
                        val body = bytes.copyOfRange(at + 9, at + len - 1)
                        seen.addAll(Iap2Csm.Parser().offer(body))
                        session.feed(packet(0x40, peerSequence, phoneSequence, 0))
                    }
                    at += len
                }
            }
        }
    }

    @Test fun mib3DetectSynControlAuthenticationAndSubscriptionExchange() {
        val f = Fixture()
        f.connect()
        f.receive(0x1d01, Iap2Csm.string(0, "Audi-fixture"))
        assertTrue(f.seen.any { it.messageId == 0x1d02 })
        assertTrue(f.seen.any { it.messageId == 0xaa00 })
        f.receive(0xaa06)
        f.receive(0xaa01, Iap2Csm.param(0, byteArrayOf(1, 2, 3)))
        assertEquals(20, Iap2Csm.parseParams(f.seen.last { it.messageId == 0xaa02 }.body).single().value.size)
        f.receive(0xaa04)
        assertEquals(32, Iap2Csm.parseParams(f.seen.last { it.messageId == 0xaa02 }.body).single().value.size)
        f.receive(0xaa03, Iap2Csm.param(0, byteArrayOf(7)))
        assertEquals(1, f.seen.count { it.messageId == 0xaa05 })
        assertTrue(f.seen.any { it.messageId == 0x4e0c })
        for ((request, response) in listOf(0x5000 to 0x5001, 0x4157 to 0x4158, 0x4154 to 0x4155, 0x4170 to 0x4171)) {
            f.receive(request)
            assertTrue(f.seen.any { it.messageId == response })
        }
        val before = f.seen.size
        f.receive(0xfffb, byteArrayOf(1, 2, 3)) // 仅链路 ACK，不伪造定位数据。
        assertEquals(before, f.seen.size)
        f.session.close()
        f.session.feed(Iap2LinkEngine.MARKER)
        f.clock += 10000
        f.session.tick()
        assertTrue(f.output.isEmpty())
    }

    @Test fun noPeerAndFailedAuthenticationHaveBoundedRetriesAndNeverClaimSuccess() {
        val f = Fixture()
        f.connect()
        f.receive(0x1d01)
        f.receive(0xaa01, byteArrayOf(1))
        f.receive(0xaa03) // 空应答不能推进成功。
        for (i in 1..3) { f.clock += 3001; f.session.tick(); f.drain() }
        assertFalse(f.seen.any { it.messageId == 0xaa05 })
        assertTrue(f.logs.any { "所有挑战长度" in it })
        val lonely = Fixture()
        lonely.session.arm()
        lonely.clock = 6000
        lonely.session.tick()
        lonely.clock = 20000
        lonely.session.tick()
        val sent = lonely.output.size
        lonely.clock = 60000
        lonely.session.tick()
        assertEquals(sent, lonely.output.size)
        assertTrue(lonely.logs.any { "协商超时" in it })
        assertFalse(lonely.session.isOpen())
    }

    @Test fun fragmentedEarlyInputSurvivesAndOverflowClosesOnlyItsOwnLink() {
        val f = Fixture()
        val sync = Iap2LinkEngine.SynchronizationPayload(4, 4096, 1000, 500, 4, 3,
            listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 1)))
        val bytes = packet(0x80, 20, 0, 0, sync.encode())
        f.session.feed(bytes.copyOfRange(0, 5))
        f.session.feed(bytes.copyOfRange(5, bytes.size))
        assertTrue(f.output.isEmpty())
        f.session.arm()
        assertArrayEquals(Iap2LinkEngine.MARKER, f.output.removeFirst())
        assertEquals(0xc0, f.output.removeFirst()[4].toInt() and 255)
        val overflow = Fixture()
        overflow.session.feed(ByteArray(262145))
        overflow.session.arm()
        assertFalse(overflow.session.isOpen())
        assertTrue(overflow.output.isEmpty())
    }

    companion object {
        private fun packet(control: Int, seq: Int, ack: Int, session: Int, payload: ByteArray = byteArrayOf()): ByteArray {
            val length = 9 + if (payload.isEmpty()) 0 else payload.size + 1
            val out = ByteArrayOutputStream()
            for (v in intArrayOf(0xff, 0x5a, length ushr 8, length, control, seq, ack, session)) out.write(v)
            out.write(-out.toByteArray().sumOf { it.toInt() and 255 })
            if (payload.isNotEmpty()) { out.write(payload); out.write(-payload.sumOf { it.toInt() and 255 }) }
            return out.toByteArray()
        }
    }
}
