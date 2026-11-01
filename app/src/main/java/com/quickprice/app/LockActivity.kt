package com.quickprice.app

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

/**
 * Tela de senha pessoal do app. A senha dos mercados (Cartaz Fácil / Rede Market)
 * fica embutida e o app conecta sozinho — esta é a única senha que o usuário digita.
 *
 * A senha pode vir embutida no APK via variável de ambiente APP_PIN no build
 * (mesmo esquema dos Secrets do GitHub). Se não houver senha embutida, o app
 * pede para CRIAR uma no primeiro uso e guarda apenas o hash neste aparelho.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var edtSenha: EditText
    private lateinit var edtConfirma: EditText
    private lateinit var txtErro: TextView
    private lateinit var btnEntrar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val embutida = decodificar(BuildConfig.APP_PIN).ifEmpty { SENHA_PADRAO }
        val p = prefs()
        val hashSalvo = p.getString("pin_hash", null)

        // Já digitou a senha uma vez após a instalação: abre direto, sem pedir de novo
        if (p.getBoolean("liberado", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        if (embutida.isEmpty() && hashSalvo == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_lock)
        edtSenha = findViewById(R.id.edtSenha)
        edtConfirma = findViewById(R.id.edtConfirma)
        txtErro = findViewById(R.id.txtErro)
        btnEntrar = findViewById(R.id.btnEntrar)

        val criar = embutida.isEmpty() // só cria senha local quando não há embutida
        edtConfirma.visibility = if (criar) View.VISIBLE else View.GONE
        btnEntrar.text = if (criar) "Criar senha" else "Entrar"

        btnEntrar.setOnClickListener {
            val s1 = edtSenha.text.toString()
            if (criar) {
                val s2 = edtConfirma.text.toString()
                when {
                    s1.length < 4 -> mostrarErro("Use pelo menos 4 caracteres.")
                    s1 != s2 -> mostrarErro("As senhas não coincidem.")
                    else -> {
                        p.edit().putString("pin_hash", sha256(s1)).putBoolean("liberado", true).apply()
                        entrar()
                    }
                }
            } else {
                val ok = (embutida.isNotEmpty() && s1 == embutida) ||
                    (hashSalvo != null && sha256(s1) == hashSalvo)
                if (ok) {
                    p.edit().putBoolean("liberado", true).apply()
                    entrar()
                } else mostrarErro("Senha incorreta.")
            }
        }
    }

    private fun entrar() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun mostrarErro(msg: String) {
        txtErro.text = msg
        txtErro.visibility = View.VISIBLE
    }

    private fun prefs() = getSharedPreferences("app_lock", MODE_PRIVATE)

    companion object {
        /** Senha pessoal embutida no app (a mesma pedida na tela inicial). */
        private const val SENHA_PADRAO = "190505"
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun decodificar(s: String): String {
        if (s.isEmpty()) return ""
        val key = "qp-7Kx!".toByteArray(Charsets.UTF_8)
        val b = Base64.decode(s, Base64.DEFAULT)
        return String(ByteArray(b.size) { (b[it].toInt() xor key[it % key.size].toInt()).toByte() }, Charsets.UTF_8)
    }
}
