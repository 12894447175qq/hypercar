package io.github.hypercar.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ConnectionServiceContractTest {
    @Test fun sourceOwnedMicrophoneDoesNotRequireAppRecordingOrMicrophoneFgs() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val services = doc.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".autowake.HyperCarService" }
        assertEquals("connectedDevice", service.getAttributeNS(android, "foregroundServiceType"))
        val permissions = doc.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        assertFalse(names.contains("android.permission.RECORD_AUDIO"))
        assertFalse(names.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
    }
}
