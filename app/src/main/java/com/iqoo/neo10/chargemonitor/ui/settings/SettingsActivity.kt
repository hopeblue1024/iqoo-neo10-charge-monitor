package com.iqoo.neo10.chargemonitor.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.R
import com.iqoo.neo10.chargemonitor.databinding.ActivitySettingsBinding
import com.iqoo.neo10.chargemonitor.util.CsvExporter
import com.iqoo.neo10.chargemonitor.util.PrefUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // 主题开关
        val isDark = PrefUtil.isDarkTheme(this)
        binding.switchTheme.isChecked = isDark
        binding.switchTheme.setOnCheckedChangeListener { _, checked ->
            PrefUtil.setDarkTheme(this, checked)
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // 版本号
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = ver

        // 导出
        binding.btnExport.setOnClickListener { exportAllRecords() }
    }

    private fun exportAllRecords() {
        CoroutineScope(Dispatchers.Main).launch {
            val records = App.instance.repository.getAllRecordsOnce()
            if (records.isEmpty()) {
                Toast.makeText(this@SettingsActivity, R.string.settings_no_records, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 导出最近一条记录的采样数据作为示例
            val record = records.first()
            val samples = App.instance.repository.getSamples(record.id)
            CsvExporter.exportAndShare(this@SettingsActivity, record, samples)
        }
    }
}
