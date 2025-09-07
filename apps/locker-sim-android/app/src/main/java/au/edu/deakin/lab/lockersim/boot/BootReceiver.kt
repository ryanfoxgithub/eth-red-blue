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

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Ignoring broadcast: $action")
            return
        }

        Log.d("BootReceiver", "Boot broadcast received: $action")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

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

        WorkManager.getInstance(context).enqueueUniqueWork(
            "boot-beacon",
            ExistingWorkPolicy.REPLACE,
            work
        )
    }
}
