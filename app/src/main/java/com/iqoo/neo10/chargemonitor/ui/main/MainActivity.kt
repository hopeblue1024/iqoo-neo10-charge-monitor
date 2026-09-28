package com.iqoo.neo10.chargemonitor.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.iqoo.neo10.chargemonitor.R
import com.iqoo.neo10.chargemonitor.databinding.ActivityMainBinding
import com.iqoo.neo10.chargemonitor.service.ChargingMonitorService
import com.iqoo.neo10.chargemonitor.ui.chart.ChartActivity
import com.iqoo.neo10.chargemonitor.ui.history.HistoryFragment
import com.iqoo.neo10.chargemonitor.ui.settings.SettingsActivity
import com.iqoo.neo10.chargemonitor.util.PrefUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> startMonitorService()
                Intent.ACTION_POWER_DISCONNECTED -> stopMonitorService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav(savedInstanceState)
        requestNotificationPermission()
        observeViewModel()
    }

    private fun applyThemeMode() {
        val dark = PrefUtil.isDarkTheme(this)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun setupBottomNav(savedInstanceState: Bundle?) {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragmentContainer, HomeFragment())
                    }
                    true
                }
                R.id.nav_history -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragmentContainer, HistoryFragment())
                    }
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    // 保持之前的选中状态，从设置页返回时不会跳回主页
                    binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.nav_home }
                    true
                }
                else -> false
            }
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    private fun observeViewModel() {
        vm.snapshot.observe(this) { s ->
            (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? HomeFragment)?.updateData(s)
            val isCharging = s.status == "充电中" || s.status == "已充满"
            if (isCharging && vm.activeRecord.value == null) {
                startMonitorService()
            }
        }
        vm.activeRecord.observe(this) { r ->
            (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? HomeFragment)?.updateSession(r)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
        vm.startTicking()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        vm.stopTicking()
    }

    private fun startMonitorService() {
        try {
            val svc = Intent(this, ChargingMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
        }
    }

    private fun stopMonitorService() {
        try {
            val svc = Intent(this, ChargingMonitorService::class.java).apply {
                action = ChargingMonitorService.ACTION_STOP
            }
            startService(svc)
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
