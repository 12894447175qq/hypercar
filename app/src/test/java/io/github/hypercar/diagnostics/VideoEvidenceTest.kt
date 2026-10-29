package io.github.hypercar.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.*
import java.util.zip.ZipInputStream
import org.json.JSONObject

class VideoEvidenceTest {
    @Test fun realArchiveRoundTripAndChecksums() {
        val root=Files.createTempDirectory("hc-evidence").toFile()
        try {
            File(root,"batch.json").writeText("{\"status\":\"未确认\"}")
            val payload=byteArrayOf(0,0,0,1,0x65,12,13)
            VideoEvidence.write(File(root,"source.packets"),listOf(VideoEvidence.Packet(123,payload)))
            assertArrayEquals(payload,VideoEvidence.read(File(root,"source.packets")).single().data)
            val bytes=ByteArrayOutputStream();VideoEvidence.archive(root,bytes)
            val extracted=mutableMapOf<String,ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { z->while(true){val e=z.nextEntry?:break;extracted[e.name]=z.readBytes()} }
            val m=JSONObject(String(extracted.getValue("manifest.json"))).getJSONArray("files")
            assertEquals(2,m.length());assertEquals(VideoEvidence.sha(File(root,"source.packets")),m.getJSONObject(1).getString("sha256"))
        } finally {root.deleteRecursively()}
    }
    @Test fun corruptedOrEscapingEvidenceNeverExports() {
        val root=Files.createTempDirectory("hc-boundary").toFile()
        try {
            File(root,"batch.json").writeText("{}")
            File(root,"bad.packets").writeBytes(VideoEvidence.magic+byteArrayOf(1))
            assertThrows(EOFException::class.java){VideoEvidence.read(File(root,"bad.packets"))}
            Files.createSymbolicLink(File(root,"escaped.txt").toPath(),File(root.parent,"outside.txt").toPath())
            assertThrows(IllegalArgumentException::class.java){VideoEvidence.archive(root,ByteArrayOutputStream())}
        } finally {root.deleteRecursively()}
    }
}
