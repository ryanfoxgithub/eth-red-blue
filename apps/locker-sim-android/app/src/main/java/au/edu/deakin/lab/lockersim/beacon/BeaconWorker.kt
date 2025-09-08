package au.edu.deakin.lab.lockersim.beacon

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BeaconWorker — a tiny background task that sends JSON "beacons" to my lab server.
 *
 * How I use it:
 * - WorkManager schedules me (e.g., after boot or on button press).
 * - I read my inputs (url, bursts, interval) from WorkManager's Data.
 * - I send N POSTs with a short delay between them, then finish.
 *
 * Reliability notes:
 * - I return Result.success() regardless of individual request failures
 *   because this is a demo. If I wanted retries/backoff, I'd return Result.retry()
 *   when all attempts fail.
 * - Network availability is best handled by a WorkManager constraint
 *   (set in the enqueuing code), not here.
 */
class BeaconWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Read configuration passed in when the work was enqueued.
        val url = inputData.getString("url") ?: return Result.failure() // I require a URL
        val bursts = inputData.getInt("bursts", 5)                      // how many beacons to send
        val interval = inputData.getInt("intervalSec", 60)              // delay between beacons (seconds)

        // Build a small OkHttp client. callTimeout caps the entire call length.
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        // I'll send application/json; OkHttp needs a MediaType object.
        val media = "application/json; charset=utf-8".toMediaType()

        // Send "bursts" beacons, spacing them out by "interval" seconds.
        repeat(bursts) { i ->
            // Compose a simple JSON body with device info and a nonce.
            val bodyJson = JSONObject().apply {
                put("model", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("status", "encrypted")             // demo status flag for my lab
                put("nonce", System.currentTimeMillis()) // unique-ish value per send
            }

            // Build the HTTP POST request.
            val req = Request.Builder()
                .url(url) // e.g., http://10.42.0.1:8000/beacon
                // Distinct UA so my IDS rule / logs can key on it.
                .header("User-Agent", "Locker-Beacon/1.0 (SIMULATION)")
                .post(bodyJson.toString().toRequestBody(media))
                .build()

            // Execute the call; log success or failure.
            try {
                client.newCall(req).execute().use { resp ->
                    Log.d("BeaconWorker", "Beacon $i -> ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w("BeaconWorker", "Beacon $i failed: ${e.message}")
            }

            // Sleep between beacons, except after the last one.
            if (i < bursts - 1) delay(interval * 1000L)
        }

        // I consider the work done; WorkManager will mark it as succeeded.
        return Result.success()
    }

    companion object {
        /**
         * Helper to build the Data object I pass into WorkManager.
         * I keep the key names in one place so enqueue and worker agree.
         */
        fun input(url: String, bursts: Int, intervalSec: Int) =
            Data.Builder()
                .putString("url", url)
                .putInt("bursts", bursts)
                .putInt("intervalSec", intervalSec)
                .build()
    }
}
