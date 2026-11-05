package com.litebrowser

import android.app.Activity
import android.os.Bundle
import android.widget.*
import kotlin.concurrent.thread

class HormouzSettingsActivity : Activity() {
    private lateinit var etApiKey: EditText
    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerMode: Spinner
    private lateinit var tvTestResult: TextView
    private lateinit var btnTest: Button
    private lateinit var btnSave: Button
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hormouz_settings)
        store = Store(this)

        etApiKey = findViewById(R.id.et_hormouz_api_key)
        spinnerModel = findViewById(R.id.spinner_hormouz_model)
        spinnerMode = findViewById(R.id.spinner_hormouz_mode)
        tvTestResult = findViewById(R.id.tv_test_result)
        btnTest = findViewById(R.id.btn_test_connection)
        btnSave = findViewById(R.id.btn_save_settings)

        etApiKey.setText(store.hormouzApiKey)
        tvTestResult.text = "وضعیت: ذخیره نشده"

        val models = HormouzClient.AVAILABLE_MODELS
        val modelAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, models) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val tv = view as android.widget.TextView
                tv.textSize = 14f
                tv.setPadding(8, 8, 8, 8)
                val isFree = models[position].endsWith(":free")
                tv.setTextColor(if (isFree) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF9800"))
                return view
            }

            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                val tv = view as android.widget.TextView
                tv.textSize = 14f
                tv.setPadding(16, 16, 16, 16)
                val isFree = models[position].endsWith(":free")
                tv.setTextColor(if (isFree) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF9800"))
                return view
            }
        }
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = modelAdapter
        val savedModelIndex = models.indexOf(store.hormouzModel)
        if (savedModelIndex >= 0) spinnerModel.setSelection(savedModelIndex)

        val modes = listOf("auto", "manual", "parallel")
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spinnerMode.adapter = modeAdapter
        val savedModeIndex = modes.indexOf(store.hormouzMode)
        if (savedModeIndex >= 0) spinnerMode.setSelection(savedModeIndex)

        btnTest.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                tvTestResult.text = "وضعیت: کلید خالی است"
                return@setOnClickListener
            }
            tvTestResult.text = "وضعیت: در حال تست..."
            btnTest.isEnabled = false
            thread {
                val response = HormouzClient.sendRequest(
                    apiKey = key,
                    model = models[spinnerModel.selectedItemPosition],
                    messages = listOf(HormouzClient.Message("user", "Say 'OK' in one word."))
                )
                runOnUiThread {
                    btnTest.isEnabled = true
                    if (response.success) {
                        tvTestResult.text = "وضعیت: موفق (${response.content})"
                        tvTestResult.setTextColor(android.graphics.Color.GREEN)
                    } else {
                        tvTestResult.text = "وضعیت: خطا - ${response.error}"
                        tvTestResult.setTextColor(android.graphics.Color.RED)
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            store.hormouzApiKey = etApiKey.text.toString().trim()
            store.hormouzModel = models[spinnerModel.selectedItemPosition]
            store.hormouzMode = modes[spinnerMode.selectedItemPosition]
            Toast.makeText(this, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
