package io.heckel.ntfy.firebase

import android.app.Service
import android.content.Intent
import android.os.IBinder

// No-op: nstrfy uses nostr relays, not Firebase
class FirebaseService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
