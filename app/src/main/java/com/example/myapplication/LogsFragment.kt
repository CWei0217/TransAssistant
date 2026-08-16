package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class LogsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)
        val tvLogs = view.findViewById<TextView>(R.id.tv_logs_content)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_refresh_logs)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btn_copy_logs)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_clear_logs)

        fun refresh() {
            tvLogs.text = AppLog.readAll()
        }

        btnRefresh.setOnClickListener { refresh() }
        btnCopy.setOnClickListener {
            val text = tvLogs.text?.toString().orEmpty()
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("app_logs", text))
            Toast.makeText(requireContext(), "日志已复制", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            AppLog.clear()
            refresh()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }

        refresh()
        return view
    }
}

