package com.iqoo.neo10.chargemonitor.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.databinding.FragmentHistoryBinding
import com.iqoo.neo10.chargemonitor.ui.historydetail.HistoryDetailActivity

class HistoryFragmentVM : ViewModel() {
    val records: LiveData<List<ChargingRecord>> =
        App.instance.repository.getAllRecordsFlow().asLiveData()
}

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val vm: HistoryFragmentVM by activityViewModels()
    private val adapter = HistoryAdapter { record ->
        val intent = Intent(requireContext(), HistoryDetailActivity::class.java).apply {
            putExtra(HistoryDetailActivity.EXTRA_RECORD_ID, record.id)
        }
        startActivity(intent)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        vm.records.observe(viewLifecycleOwner) { records ->
            adapter.submitList(records)
            binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
