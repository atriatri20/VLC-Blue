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
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.InputStream
import java.util.Properties

/**
 * Minimal read-only SMB2/3 client used to decode images stored on network shares.
 * libvlc only exposes SMB through its playback engine, so image bytes are read
 * with jcifs instead. Credentials embedded in the uri (smb://user:pass@host/...)
 * are honored; otherwise a guest then anonymous session is attempted.
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

    /**
     * Open a smb:// uri for reading. SmbFile connects lazily, so each candidate
     * credential set is tried at stream open time.
     */
    fun openStream(uri: Uri): InputStream? {
        val url = buildUrl(uri) ?: return null
        val contexts = ArrayList<CIFSContext>()
        val userInfo = runCatching { uri.userInfo }.getOrNull()
        if (!userInfo.isNullOrBlank()) contexts.add(baseContext.withCredentials(parseAuthenticator(userInfo)))
        contexts.add(baseContext.withGuestCrendentials())
        contexts.add(baseContext.withAnonymousCredentials())
        for (context in contexts) {
            val stream = runCatching { SmbFileInputStream(SmbFile(url, context)) }.getOrNull() ?: continue
            return stream
        }
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
        return if (colon >= 0) NtlmPasswordAuthenticator(domain, rest.substring(0, colon), rest.substring(colon + 1))
        else NtlmPasswordAuthenticator(domain, rest, "")
    }

    private fun buildUrl(uri: Uri): String? {
        val host = runCatching { uri.host }.getOrNull() ?: return null
        if (host.isEmpty()) return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = runCatching { uri.path }.getOrNull() ?: "/"
        return "smb://$host$port$path"
    }
}
