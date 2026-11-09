package com.womp.recvault

import android.net.Uri
import recvault.core.Key
import recvault.core.LocalFile

class NeedsSignIn(msg: String) : Exception(msg)

interface CloudClient {
    /** Get a token and make sure the RecVault/<device> folder exists. */
    suspend fun prepare()

    /** Every file in this device's cloud folder, as (name, size, hash) the CLOUD reports. */
    suspend fun listKeys(): Set<Key>

    /** Upload, then return the Key the cloud reports for what it actually stored. */
    suspend fun upload(f: LocalFile, uri: Uri): Key
}
