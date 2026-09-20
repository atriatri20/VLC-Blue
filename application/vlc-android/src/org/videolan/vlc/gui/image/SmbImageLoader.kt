/*
 * *************************************************************************
 *  SmbImageLoader.kt
 * **************************************************************************
 *  Copyright © 2026 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */
package org.videolan.vlc.gui.image

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbRandomAccessFile
import org.videolan.resources.AppContextProvider
import org.videolan.tools.Settings
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal read-only SMB2/3 client used to decode images stored on network shares.
 * libvlc only exposes SMB through its playback engine and keeps its credentials
 * native, so image bytes are read with jcifs instead.
 *
 * Credential resolution order: uri embedded (smb://user:pass@host/...), then the
 * credentials saved for that host by [storeCredential] (persisted, typically
 * entered once through the viewer prompt), then guest, then anonymous. If every
 * attempt fails with an authentication error, [SmbAuthRequiredException] is
 * thrown so the caller can prompt the user.
 *
 * Hosts that answer with a connectivity error (rather than an auth error) are
 * remembered as unreachable for [DEAD_HOST_TTL_MS]; browsing code can skip them
 * with [isKnownDead] instead of paying the timeout again on every row. The list
 * is reset as soon as the device attaches to another network.
 */
object SmbImageLoader {

    private const val DEAD_HOST_TTL_MS = 5 * 60 * 1000L
    private const val NETWORK_RECHECK_MS = 1000L

    /** Pause before the second credential pass, long enough for a session re-setup */
    private const val AUTH_RETRY_MS = 500L
    private const val TAG = "SmbImageLoader"

    private val baseContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "3000")
            setProperty("jcifs.smb.client.responseTimeout", "5000")
            setProperty("jcifs.smb.client.soTimeout", "8000")
        }
        BaseContext(PropertyConfiguration(props))
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        runCatching {
            AppContextProvider.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
    }

    /** host -> time it last failed to answer */
    private val deadHosts = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastActiveNetwork: Network? = null

    @Volatile
    private var lastNetworkCheck = 0L

    class SmbAuthRequiredException(val host: String) : Exception("SMB authentication required for $host")

    fun storeCredential(host: String, username: String, password: String, domain: String = "") {
        Settings.getInstance(AppContextProvider.appContext).edit()
                .putString(smbKey(host), "$domain\n$username\n$password")
                .apply()
    }

    fun hasStoredCredential(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return !Settings.getInstance(AppContextProvider.appContext).getString(smbKey(host), null).isNullOrEmpty()
    }

    private fun storedCredential(host: String): NtlmPasswordAuthenticator? {
        val saved = Settings.getInstance(AppContextProvider.appContext).getString(smbKey(host), null) ?: return null
        val parts = saved.split("\n")
        return NtlmPasswordAuthenticator(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    private fun smbKey(host: String) = "smb_credentials_$host"

    /**
     * Open a smb:// uri for reading. SmbFile connects lazily, so each candidate
     * credential set is tried at stream open time. This entry point always
     * probes the host, even a [isKnownDead] one: it is reached from deliberate
     * actions (opening an image), where a retry is what the user asked for.
     *
     * A credential that normally works can still be rejected once in a while -
     * the server expired the session, or it is throttling new connections - and
     * the guest/anonymous attempts that follow then look exactly like a missing
     * password. So when we do hold a credential, the whole set is tried a second
     * time before [SmbAuthRequiredException] is thrown and the user is asked.
     */
    fun openStream(uri: Uri): InputStream? {
        val url = buildUrl(uri) ?: return null
        val host = runCatching { uri.host }.getOrNull() ?: return null
        val attempts = if (hasCredentialFor(uri, host)) 2 else 1
        repeat(attempts) { attempt ->
            if (attempt > 0) runCatching { Thread.sleep(AUTH_RETRY_MS) }
            var authFailure = false
            for (context in credentialContexts(uri, host)) {
                try {
                    val stream = SmbFileInputStream(SmbFile(url, context))
                    markAlive(host)
                    return stream
                } catch (e: SmbAuthException) {
                    authFailure = true
                } catch (e: Exception) {
                    if (!isConnectivityFailure(e)) continue
                    markDead(host)
                    return null
                }
            }
            if (!authFailure) return null
        }
        throw SmbAuthRequiredException(host)
    }

    private fun hasCredentialFor(uri: Uri, host: String) =
            !runCatching { uri.userInfo }.getOrNull().isNullOrBlank() || hasStoredCredential(host)

    /**
     * Random access variant used by the video thumbnailer (MediaDataSource)
     */
    fun openRandomAccess(uri: Uri): SmbRandomAccessFile? {
        val url = buildUrl(uri) ?: return null
        val host = runCatching { uri.host }.getOrNull() ?: return null
        for (context in credentialContexts(uri, host)) {
            val file = try {
                SmbRandomAccessFile(SmbFile(url, context), "r")
            } catch (e: Exception) {
                if (isConnectivityFailure(e)) markDead(host)
                continue
            }
            markAlive(host)
            return file
        }
        return null
    }

    /**
     * True while [host] is known to be unreachable. Speculative work (row
     * thumbnails, folder covers) should be skipped for such a host; reading on
     * the user's request still goes through.
     */
    fun isKnownDead(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        revalidateNetwork()
        val since = deadHosts[host] ?: return false
        if (System.currentTimeMillis() - since > DEAD_HOST_TTL_MS) {
            deadHosts.remove(host)
            return false
        }
        return true
    }

    private fun markAlive(host: String?) {
        if (host.isNullOrEmpty()) return
        if (deadHosts.remove(host) != null) Log.i(TAG, "$host reachable again: resuming thumbnail probes")
    }

    private fun markDead(host: String) {
        revalidateNetwork()
        if (deadHosts.put(host, System.currentTimeMillis()) == null)
            Log.w(TAG, "$host did not answer, skipping speculative SMB probes for ${DEAD_HOST_TTL_MS / 1000}s")
    }

    /**
     * A share unreachable on one network says nothing about the next one, so the
     * blacklist is dropped whenever the device attaches to a different network.
     * Checked lazily instead of through a callback: thumbnails bind often enough
     * to notice a switch, and the binder call itself is throttled.
     */
    private fun revalidateNetwork() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkCheck < NETWORK_RECHECK_MS) return
        lastNetworkCheck = now
        val current = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        if (current != lastActiveNetwork) {
            lastActiveNetwork = current
            deadHosts.clear()
        }
    }

    /**
     * jcifs-ng reports an unreachable host as a plain SmbException wrapping the
     * socket error, so the whole cause chain has to be walked. An auth error
     * means the opposite: the host answered and only the credentials are wrong.
     */
    private fun isConnectivityFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is SmbAuthException -> return false
                is ConnectException, is SocketTimeoutException, is NoRouteToHostException,
                is UnknownHostException -> return true
            }
            val message = cause.message?.lowercase()
            if (message != null && (message.contains("timed out") || message.contains("connection refused") ||
                            message.contains("no route to host") || message.contains("network is unreachable") ||
                            message.contains("failed to connect"))) return true
            cause = cause.cause
        }
        return false
    }

    private fun credentialContexts(uri: Uri, host: String): List<CIFSContext> {
        val contexts = ArrayList<CIFSContext>()
        val userInfo = runCatching { uri.userInfo }.getOrNull()
        if (!userInfo.isNullOrBlank()) contexts.add(baseContext.withCredentials(parseAuthenticator(userInfo)))
        storedCredential(host)?.let { contexts.add(baseContext.withCredentials(it)) }
        contexts.add(baseContext.withGuestCrendentials())
        contexts.add(baseContext.withAnonymousCredentials())
        return contexts
    }

    /**
     * userInfo forms: "user", "user:pass" or "domain;user:pass"
     */
    private fun parseAuthenticator(userInfo: String): NtlmPasswordAuthenticator {
        var rest = userInfo
        var domain = ""
        val semi = userInfo.indexOf(';')
        if (semi >= 0) {
            domain = userInfo.substring(0, semi)
            rest = userInfo.substring(semi + 1)
        }
        val colon = rest.indexOf(':')
        val decoded = runCatching { URLDecoder.decode(rest, "UTF-8") }.getOrDefault(rest)
        return if (colon >= 0) NtlmPasswordAuthenticator(domain, decoded.substring(0, colon), decoded.substring(colon + 1))
        else NtlmPasswordAuthenticator(domain, decoded, "")
    }

    private fun buildUrl(uri: Uri): String? {
        val host = runCatching { uri.host }.getOrNull() ?: return null
        if (host.isEmpty()) return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = runCatching { uri.path }.getOrNull() ?: "/"
        return "smb://$host$port$path"
    }
}
