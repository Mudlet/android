package org.mudlet.android.ui

/** Bite branch only: which of the map file client's behaviours a test copy breaks; empty breaks none of them. */
object Bite {
    @Volatile
    var stage = ""

    /** The JVM tests' breaks stay off the device, where they would end the instrumented tests first. */
    val offDevice = System.getProperty("java.vm.name") != "Dalvik"
}
