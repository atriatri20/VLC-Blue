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

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.videolan.resources.AppContextProvider
import org.videolan.tools.Settings
import java.io.InputStream
import java.net.URLDecoder
import java.util.Properties

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
 */
object SmbImageLoader {

    private val baseContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
        }
        BaseContext(PropertyConfiguration(props))
    }

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
     * credential set is tried at stream open time.
     */
    fun openStream(uri: Uri): InputStream? {
        val url = buildUrl(uri) ?: return null
        val host = runCatching { uri.host }.getOrNull() ?: return null
        val contexts = ArrayList<CIFSContext>()
        val userInfo = runCatching { uri.userInfo }.getOrNull()
        if (!userInfo.isNullOrBlank()) contexts.add(baseContext.withCredentials(parseAuthenticator(userInfo)))
        storedCredential(host)?.let { contexts.add(baseContext.withCredentials(it)) }
        contexts.add(baseContext.withGuestCrendentials())
        contexts.add(baseContext.withAnonymousCredentials())
        var authFailure = false
        for (context in contexts) {
            try {
                return SmbFileInputStream(SmbFile(url, context))
            } catch (e: SmbAuthException) {
                authFailure = true
            } catch (ignored: Exception) {
            }
        }
        if (authFailure) throw SmbAuthRequiredException(host)
        return null
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
