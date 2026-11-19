package com.quickprice.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class ScannerActivity : AppCompatActivity() {
    private var failureAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iniciarLeitura()
    }

    private fun iniciarLeitura() {
        // Este app pesquisa produtos: limitar o detector a códigos comerciais
        // reduz formatos irrelevantes. O auto-zoom ajuda com códigos pequenos.
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_ITF
            )
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val codigo = barcode.rawValue
                if (codigo.isNullOrBlank()) {
                    finish()
                    return@addOnSuccessListener
                }
                val intent = Intent().apply { putExtra("codigo", codigo) }
                setResult(RESULT_OK, intent)
                finish()
            }
            .addOnCanceledListener {
                finish()
            }
            .addOnFailureListener { e ->
                if (failureAttempts == 0 && !isFinishing) {
                    failureAttempts++
                    window.decorView.postDelayed({
                        if (!isFinishing && !isDestroyed) iniciarLeitura()
                    }, 350)
                } else {
                    Toast.makeText(
                        this,
                        "O leitor Google falhou. Verifique a ligação e tente novamente. ${e.message.orEmpty()}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
    }
}
