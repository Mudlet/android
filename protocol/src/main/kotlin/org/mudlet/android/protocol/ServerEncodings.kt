package org.mudlet.android.protocol

/**
 * The encodings Mudlet can read a game's text in, which the profile setting `serverEncoding` takes: the names Lua's
 * `getServerEncodingsList()` gives, in its order, which is the order desktop Mudlet's preferences list them in.
 */
object ServerEncodings {
    /** What Mudlet gives a new profile. */
    const val DEFAULT = "UTF-8"

    val names: List<String> = listOf(
        "ASCII",
        "UTF-8",
        "EUC-KR",
        "SHIFT_JIS",
        "EUC-JP",
        "GBK",
        "GB18030",
        "BIG5",
        "BIG5-HKSCS",
        "ISO 8859-1",
        "CP1162",
        "CP437",
        "CP667",
        "CP737",
        "CP850",
        "CP866",
        "CP869",
        "ISO 8859-10",
        "ISO 8859-11",
        "ISO 8859-13",
        "ISO 8859-14",
        "ISO 8859-15",
        "ISO 8859-16",
        "ISO 8859-2",
        "ISO 8859-3",
        "ISO 8859-4",
        "ISO 8859-5",
        "ISO 8859-6",
        "ISO 8859-7",
        "ISO 8859-8",
        "ISO 8859-9",
        "KOI8-R",
        "KOI8-U",
        "MACINTOSH",
        "MEDIEVIA",
        "WINDOWS-1250",
        "WINDOWS-1251",
        "WINDOWS-1252",
        "WINDOWS-1253",
        "WINDOWS-1254",
        "WINDOWS-1255",
        "WINDOWS-1256",
        "WINDOWS-1257",
        "WINDOWS-1258",
    )
}
