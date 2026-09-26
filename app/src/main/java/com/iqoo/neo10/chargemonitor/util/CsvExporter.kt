package com.iqoo.neo10.chargemonitor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

/**
 * 导出充电记录的全部时序采样为 CSV 文件，并通过系统分享。
 */
object CsvExporter {

    suspend fun exportAndShare(context: Context, record: ChargingRecord, samples: List<BatterySample>) {
        val uri = withContext(Dispatchers.IO) {
            val file = writeCsv(context, record, samples)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "导出充电日志 CSV"))
    }

    private fun writeCsv(
        context: Context,
        record: ChargingRecord,
        samples: List<BatterySample>
    ): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "charge_${record.id}_${record.startTime}.csv"
        val file = File(dir, name)
        FileWriter(file).use { w ->
            w.write("elapsed_sec,time,capacity,voltage_V,current_A,power_W,temperature_C,source\n")
            for (s in samples) {
                w.write("${s.elapsedSec},")
                w.write("${FormatUtil.formatTime(s.timestamp)},")
                w.write("${s.capacity},")
                w.write("${String.format("%.3f", s.voltage)},")
                w.write("${String.format("%.3f", s.current)},")
                w.write("${String.format("%.2f", s.power)},")
                w.write("${String.format("%.1f", s.temperature)},")
                w.write("${s.source}\n")
            }
        }
        return file
    }
}
