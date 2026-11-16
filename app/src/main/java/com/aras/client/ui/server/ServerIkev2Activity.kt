package com.aras.client.ui.server

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig
import com.aras.client.AppConfig.DEFAULT_PORT
import com.aras.client.R
import com.aras.client.core.IkeV2Manager
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.toastError
import com.aras.client.fmt.Ikev2Fmt
import com.aras.client.helper.FileChooserHelper
import com.aras.client.ui.compose.FormTextField
import com.aras.client.util.LogUtil
import java.security.cert.X509Certificate

/**
 * Editor for an IKEv2/IPsec profile. The tunnel is built by the platform's own
 * IKE engine, so the profile only names the gateway, the auth pair, the
 * identities and an optional CA to trust the gateway by; there is no port to
 * pick and no transport settings.
 */
class ServerIkev2Activity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.IKEV2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        caFileChooser = FileChooserHelper(this)
    }

    private lateinit var caFileChooser: FileChooserHelper

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig).apply {
                configType = EConfigType.IKEV2
                // The port is only cosmetic: the platform IKE dials UDP 500/4500.
                if (port == DEFAULT_PORT.toString()) port = "500"
            }
        }
        var caSubject by rememberSaveable {
            mutableStateOf(caDisplayName(initialConfig.ikev2CaCert))
        }

        ServerEditorScaffold(
            title = "IKEv2",
            onSaveClick = { saveServer(uiState) }
        ) {
            FormTextField(
                stringResource(R.string.server_lab_remarks),
                uiState.remarks,
                { uiState.remarks = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_address),
                uiState.address,
                { uiState.address = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_username),
                uiState.username,
                { uiState.username = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_password),
                uiState.password,
                { uiState.password = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_local_id),
                uiState.ikev2LocalId,
                { uiState.ikev2LocalId = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_remote_id),
                uiState.ikev2RemoteId,
                { uiState.ikev2RemoteId = it }
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.server_lab_ikev2_ca),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    caSubject.ifBlank { stringResource(R.string.server_lab_ikev2_ca_none) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        caFileChooser.launch("*/*") { uri ->
                            val cert = uri?.let { IkeV2Manager.readCaCertificate(this@ServerIkev2Activity, it) }
                            if (cert == null) {
                                toastError(R.string.toast_ikev2_ca_invalid)
                            } else {
                                uiState.ikev2CaCert = cert.second
                                caSubject = caDisplayName(cert.second)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.server_lab_ikev2_ca_choose))
                    }
                    if (uiState.ikev2CaCert.isNotBlank()) {
                        Button(onClick = {
                            uiState.ikev2CaCert = ""
                            caSubject = ""
                        }) {
                            Text(stringResource(R.string.server_lab_ikev2_ca_clear))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.server_lab_ikev2_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /** The subject CN, the name a chosen CA file is shown by. */
    private fun caDisplayName(encoded: String?): String =
        runCatching {
            val der = android.util.Base64.decode(encoded.orEmpty(), android.util.Base64.NO_WRAP)
            (java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate).subjectX500Principal.name
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "IkeV2: unreadable stored CA certificate", it)
            ""
        }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.server.isNullOrBlank()) {
            toastError(R.string.server_lab_ikev2_no_host)
            return false
        }
        return true
    }

    override fun nameProfile(state: ServerUiState, config: ProfileItem) {
        if (config.remarks.isBlank()) {
            config.remarks = uniqueRemarks(Ikev2Fmt.defaultRemarks(config))
        }
    }
}
