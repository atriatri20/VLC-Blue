package org.videolan.vlc.gui.dialogs

import android.net.Uri
import org.videolan.tools.readableNumber

data class SubtitleItem(
    val idSubtitle: String,
    val fileId: Long,
    val mediaUri: Uri,
    val subLanguageID: String,
    val movieReleaseName: String,
    val state: State,
    var zipDownloadLink: String,
    val hearingImpaired: Boolean,
    val rating: Float,
    val downloadNumber: Long,
    var fileName: String = "",
    var downloadError: Boolean = false
) {
    fun getReadableDownloadNumber() = downloadNumber.readableNumber()
}

enum class State {
    Downloading,
    Downloaded,
    NotDownloaded
}
