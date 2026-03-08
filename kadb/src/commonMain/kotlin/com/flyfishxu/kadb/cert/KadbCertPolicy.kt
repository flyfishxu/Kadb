package com.flyfishxu.kadb.cert

data class KadbCertPolicy(
    val keySizeBits: Int = 2048,
    val certValidityDays: Int = 3650,
    val autoHealInvalidPrivateKey: Boolean = true,
    val subject: Subject = Subject()
) {
    data class Subject(
        val cn: String = "Adb",
        val ou: String = "",
        val o: String = "Android",
        val l: String = "",
        val st: String = "",
        val c: String = "US"
    )
}
