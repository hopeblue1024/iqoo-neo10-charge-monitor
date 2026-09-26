package com.iqoo.neo10.chargemonitor.ui.log

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.iqoo.neo10.chargemonitor.databinding.ActivityLogBinding
import com.iqoo.neo10.chargemonitor.util.AppLogger

/** 运行时日志查看页：展示 AppLogger 记录的排查信息 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnClear.setOnClickListener {
            AppLogger.clear()
            refresh()
        }

        refresh()
    }

    private fun refresh() {
        val lines = AppLogger.allLines()
        binding.tvLogCount.text = "共 ${lines.size} 条"
        binding.tvLogContent.text = lines.joinToString("\n")
    }
}
