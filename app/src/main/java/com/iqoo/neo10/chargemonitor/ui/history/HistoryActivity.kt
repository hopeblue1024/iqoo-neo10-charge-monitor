package com.iqoo.neo10.chargemonitor.ui.history

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.databinding.ActivityHistoryBinding
import com.iqoo.neo10.chargemonitor.ui.historydetail.HistoryDetailActivity

class HistoryViewModel : ViewModel() {
    val records: LiveData<List<ChargingRecord>> =
        App.instance.repository.getAllRecordsFlow().asLiveData()
}

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val vm: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = HistoryAdapter { record ->
            val intent = Intent(this, HistoryDetailActivity::class.java).apply {
                putExtra(HistoryDetailActivity.EXTRA_RECORD_ID, record.id)
            }
            startActivity(intent)
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        vm.records.observe(this) { records ->
            adapter.submitList(records)
            binding.tvEmpty.visibility = if (records.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}
