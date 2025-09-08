// BootReceiver.kt
package au.edu.deakin.lab.lockersim.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import au.edu.deakin.lab.lockersim.beacon.BeaconWorker

/**
 * I use this BroadcastReceiver to start one beacon right after device boot.
 *
 * Preconditions (what I must set in AndroidManifest.xml):
 * - <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
 * - <receiver ... android:exported="true"> with an <intent-filter> for BOOT_COMPLETED
 *   (on Android 12+ I *must* declare android:exported; true lets the system deliver
 *   the implicit boot broadcast to me).
 * - If I also want LOCKED_BOOT_COMPLETED (before user unlock), add
 *   android:directBootAware="true" to the receiver and include that action.
 * - Because I use a WorkManager network constraint, I also add:
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
 *
 * Usability note:
 * - After install, the app needs to be launched once; “stopped” apps don’t receive
 *   BOOT_COMPLETED until they’re first run.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // I accept only the boot actions; everything else is ignored defensively.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Ignoring broadcast: $action")
            return
        }

        Log.d("BootReceiver", "Boot broadcast received: $action")

        // WorkManager constraint: wait until the device has a connected network.
        // This prevents my beacon from failing while Wi-Fi is still associating.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // I enqueue a *single* one-shot beacon work with my lab URL.
        // bursts=1, interval=1s → exactly one POST after boot.
        val work = OneTimeWorkRequestBuilder<BeaconWorker>()
            .setInputData(
                BeaconWorker.input(
                    url = "http://10.42.0.1:8000/beacon",
                    bursts = 1,
                    intervalSec = 1
                )
            )
            .setConstraints(constraints)
            .build()

        // Use a unique name so I don’t enqueue duplicates if both
        // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED arrive on the same boot.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "boot-beacon",                 // unique work name
            ExistingWorkPolicy.REPLACE,    // replace any previous pending one
            work
        )
    }
}
