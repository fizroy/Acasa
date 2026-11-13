/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.http.SslCertificate
import android.net.http.SslError
import android.security.KeyChain
import android.security.KeyChainException
import android.util.Base64
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.net.toUri
import de.duenndns.ssl.MemorizingTrustManager
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.CloudConnection
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getStringOrNull
import org.openhab.habdroid.util.isDemoModeEnabled
import org.openhab.habdroid.util.openInBrowser

open class ConnectionWebViewClient(val connection: Connection) : WebViewClient() {
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        val proxyHost = (connection as? CloudConnection)?.proxyUrl?.host
        if ((proxyHost != null && host == proxyHost) || host == connection.httpClient.targetHost) {
            handler.proceed(connection.username, connection.password)
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        @Suppress("DEPRECATION")
        return shouldOverrideUrlLoading(view, request.url.toString())
    }

    // This is called on older Android versions
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (url == EMPTY_PAGE || view.url == EMPTY_PAGE) {
            Log.d(TAG, "Either current or new page is '$EMPTY_PAGE'")
            return false
        }

        val uri = url.toUri()
        val viewUri = view.url?.toUri()
        if (uri.host == viewUri?.host) {
            Log.d(TAG, "Same host: Load in WebView ($url)")
            return false
        }

        Log.d(TAG, "New host: Open in external browser ($url, WebView is on $viewUri)")
        uri.openInBrowser(view.context)

        return true
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val context = view.context
        val cert = getX509Certificate(error.certificate)
        val mtm = MemorizingTrustManager(context)
        if (cert != null && mtm.isCertKnown(cert)) {
            Log.d(TAG, "Invalid certificate, but the same one as the main connection")
            handler.proceed()
            return
        }

        // Acasa: self-signed certificates of Webview pages (Grafana etc.) are accepted only after
        // the user confirmed that exact certificate once; it is remembered per host:port.
        val uri = error.url?.toUri()
        val hostKey = uri?.let { "${it.host}:${if (it.port > 0) it.port else 443}" }
        val x509 = cert as? X509Certificate
        val fingerprint = x509?.let { sha256(it.encoded) }
        if (hostKey != null && x509 != null && fingerprint != null) {
            val acceptedKey = "$hostKey|$fingerprint"
            val prefs = context.getSharedPreferences(ACASA_CERT_PREFS, Context.MODE_PRIVATE)
            if (prefs.getStringSet(ACASA_CERT_KEY, emptySet())!!.contains(acceptedKey)) {
                handler.proceed()
                return
            }
            val activity = context as? Activity ?: (context as? ContextWrapper)?.baseContext as? Activity
            if (activity != null && !activity.isFinishing) {
                val waiting = pendingCertDecisions[acceptedKey]
                if (waiting != null) {
                    // A dialog for this certificate is already shown, wait for its answer
                    waiting.add(handler)
                    return
                }
                pendingCertDecisions[acceptedKey] = mutableListOf(handler)
                val message = buildString {
                    append("Pagina de pe $hostKey folosește un certificat care nu e de încredere ")
                    append("(de obicei self-signed).\n\n")
                    append("Emis pentru: ${x509.subjectX500Principal.name}\n")
                    append("Emis de: ${x509.issuerX500Principal.name}\n")
                    append("Valabil până la: ${x509.notAfter}\n\n")
                    append("SHA-256: ${fingerprint.chunked(2).joinToString(":")}\n\n")
                    append("Accepți acest certificat? Va fi ținut minte și nu te mai întreb pentru el.")
                }
                AlertDialog.Builder(activity)
                    .setTitle("Certificat necunoscut")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Accept") { _, _ ->
                        val accepted = prefs.getStringSet(ACASA_CERT_KEY, emptySet())!!.toMutableSet()
                        accepted.add(acceptedKey)
                        prefs.edit { putStringSet(ACASA_CERT_KEY, accepted) }
                        pendingCertDecisions.remove(acceptedKey)?.forEach { it.proceed() }
                    }
                    .setNegativeButton("Refuz") { _, _ ->
                        pendingCertDecisions.remove(acceptedKey)?.forEach { it.cancel() }
                        showSslErrorPage(view, error)
                    }
                    .show()
                return
            }
        }

        Log.e(TAG, "Invalid certificate")
        handler.cancel()
        showSslErrorPage(view, error)
    }

    private fun showSslErrorPage(view: WebView, error: SslError) {
        val context = view.context
        val host = connection.httpClient.targetHost
        val errorMessage = when (error.primaryError) {
            SslError.SSL_NOTYETVALID -> context.getString(R.string.error_certificate_not_valid_yet)
            SslError.SSL_EXPIRED -> context.getString(R.string.error_certificate_expired)
            SslError.SSL_IDMISMATCH -> context.getString(R.string.error_certificate_wrong_host, host)
            SslError.SSL_DATE_INVALID -> context.getString(R.string.error_certificate_invalid_date)
            else -> context.getString(R.string.webview_ssl)
        }

        val html = "<html><body><p>$errorMessage</p><p>${error.certificate}</p></body></html>"
        val encodedHtml = Base64.encodeToString(html.toByteArray(), Base64.NO_PADDING)
        view.loadData(encodedHtml, "text/html; charset=UTF-8", "base64")
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02X".format(it) }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        Log.d(TAG, "SSL Client Cert required")
        val prefs = view.context.getPrefs()
        if (prefs.isDemoModeEnabled()) {
            request.cancel()
            return
        }

        val serverId = prefs.getActiveServerId()
        val alias = prefs.getStringOrNull(PrefKeys.buildServerKey(serverId, PrefKeys.SSL_CLIENT_CERT_PREFIX))
        Log.d(TAG, "Using alias $alias")
        if (alias == null) {
            request.cancel()
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val chain = KeyChain.getCertificateChain(view.context, alias)
                val privateKey = KeyChain.getPrivateKey(view.context, alias)
                request.proceed(privateKey, chain)
            } catch (e: KeyChainException) {
                Log.d(TAG, "Error getting certificate chain or private key", e)
                request.ignore()
            } catch (e: InterruptedException) {
                Log.d(TAG, "Error getting certificate chain or private key", e)
                request.ignore()
            }
        }
    }

    /**
     * @author Heath Borders at https://stackoverflow.com/questions/20228800/how-do-i-validate-an-android-net-http-sslcertificate-with-an-x509trustmanager
     */
    private fun getX509Certificate(sslCertificate: SslCertificate): Certificate? {
        val bundle = SslCertificate.saveState(sslCertificate)
        val bytes = bundle.getByteArray("x509-certificate") ?: return null
        return try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes))
        } catch (e: CertificateException) {
            null
        }
    }

    companion object {
        private val TAG = ConnectionWebViewClient::class.java.simpleName

        const val EMPTY_PAGE = "about:blank"

        private const val ACASA_CERT_PREFS = "acasa_webview_certs"
        private const val ACASA_CERT_KEY = "accepted"
        private val pendingCertDecisions = mutableMapOf<String, MutableList<SslErrorHandler>>()
    }
}
