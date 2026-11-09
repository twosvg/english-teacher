package com.encoach.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val dialogWindow = window
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialogWindow.setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val keyView = findViewById<EditText>(R.id.inputApiKey)
        val baseView = findViewById<EditText>(R.id.inputBaseUrl)
        val modelView = findViewById<EditText>(R.id.inputModel)

        val current = Prefs.load(this)
        keyView.setText(current.apiKey)
        baseView.setText(current.baseUrl)
        modelView.setText(current.model)

        findViewById<CheckBox>(R.id.checkShowKey).setOnCheckedChangeListener { _, checked ->
            keyView.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            keyView.setSelection(keyView.text.length)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.save(
                this,
                keyView.text.toString(),
                baseView.text.toString(),
                modelView.text.toString()
            )
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }
}
