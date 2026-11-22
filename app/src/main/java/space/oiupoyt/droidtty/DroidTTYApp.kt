package space.oiupoyt.droidtty

import android.app.Application
import space.oiupoyt.droidtty.system.CrashGuard

class DroidTTYApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, so even a crash while the launcher starts up is caught and counted.
        CrashGuard.install(this)
    }
}
