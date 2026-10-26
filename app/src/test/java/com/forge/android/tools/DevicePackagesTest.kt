package com.forge.android.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePackagesTest {
    @Test fun packageListValuesAreDirectlyUsableForLaunch() {
        assertEquals(listOf("com.example.app", "org.example.second"),
            DevicePackages.parse("package:com.example.app\r\npackage:org.example.second\npackage:com.example.app"))
    }
    @Test fun diagnosticsAndMalformedLinesAreNotPackageNames() {
        assertEquals(emptyList<String>(), DevicePackages.parse("Error: denied\npackage:\npackage:bad;command\n"))
    }
}
