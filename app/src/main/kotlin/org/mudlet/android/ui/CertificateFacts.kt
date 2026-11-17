package org.mudlet.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mudlet.android.R
import org.mudlet.android.data.Settings
import org.mudlet.android.protocol.RefusedCertificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The game's switches that would let [certificate] through, which desktop Mudlet highlights beside its facts: none for
 * one with [RefusedCertificate.others], as desktop never points at "Ignore all certificate errors".
 */
fun certificateSwitches(certificate: RefusedCertificate): List<Settings.BoolSetting> = if (certificate.others) {
    emptyList()
} else {
    listOfNotNull(
        Settings.acceptExpiredCertificate.takeIf { certificate.expired },
        Settings.acceptSelfSignedCertificate.takeIf { certificate.selfSigned },
    )
}

/** Why [certificate] was refused, then who it was issued to and by, when it runs out and its serial number. */
@Composable
fun CertificateFacts(certificate: RefusedCertificate) {
    certificate.errors.forEach { Text(stringResource(R.string.certificate_error, it)) }
    Text(stringResource(R.string.certificate_issued_to, certificate.issuedTo), Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.certificate_issuer, certificate.issuer))
    Text(stringResource(R.string.certificate_expires, expiryText(certificate.expires)))
    Text(stringResource(R.string.certificate_serial, certificate.serial))
}

@Composable
private fun expiryText(expires: String): String {
    if (expires.isEmpty()) return stringResource(R.string.certificate_expiry_unknown)
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(expires))
    }.getOrDefault(expires)
}
