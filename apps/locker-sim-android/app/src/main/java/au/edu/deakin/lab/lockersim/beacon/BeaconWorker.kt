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

class BeaconWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val bursts = inputData.getInt("bursts", 5)
        val interval = inputData.getInt("intervalSec", 60)

        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val media = "application/json; charset=utf-8".toMediaType()

        repeat(bursts) { i ->
            val bodyJson = JSONObject().apply {
                put("model", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("status", "encrypted")
                put("nonce", System.currentTimeMillis())
            }
            val req = Request.Builder()
                .url(url) // e.g. http://10.42.0.1:8000/beacon
                .header("User-Agent", "Locker-Beacon/1.0 (SIMULATION)")
                .post(bodyJson.toString().toRequestBody(media))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    Log.d("BeaconWorker", "Beacon $i -> ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w("BeaconWorker", "Beacon $i failed: ${e.message}")
            }
            if (i < bursts - 1) delay(interval * 1000L)
        }
        return Result.success()
    }

    companion object {
        fun input(url: String, bursts: Int, intervalSec: Int) =
            Data.Builder()
                .putString("url", url)
                .putInt("bursts", bursts)
                .putInt("intervalSec", intervalSec)
                .build()
    }
}
