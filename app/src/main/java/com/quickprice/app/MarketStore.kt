package com.quickprice.app

import android.content.Context
import android.util.Base64

internal data class MercadoConfig(
    val nome: String,
    val baseUrl: String,
    val usuario: String,
    val senha: String
) {
    val valido: Boolean get() = baseUrl.isNotBlank() && usuario.isNotBlank() && senha.isNotBlank()
}

/** Configuração dos dois mercados (editável na tela de Ajustes do app). */
internal class MarketStore(context: Context) {
    private val prefs = context.getSharedPreferences("compara_mercados", Context.MODE_PRIVATE)

    fun mercadoA(): MercadoConfig = MercadoConfig(
        nome = prefs.getString("a_nome", null) ?: "Super Carlão",
        baseUrl = (prefs.getString("a_url", null) ?: "https://carlaosupermercados.cartazfacil.pro").trimEnd('/'),
        usuario = prefs.getString("a_user", null) ?: "supercarlao105",
        senha = prefs.getString("a_pass", null) ?: "sup12345"
    )

    fun mercadoB(): MercadoConfig {
        val embU = decodificar(BuildConfig.CF_USER).ifEmpty { "rmarket07" }
        val embP = decodificar(BuildConfig.CF_PASS).ifEmpty { "sup12345" }
        return MercadoConfig(
            nome = prefs.getString("b_nome", null) ?: "Redemarket",
            baseUrl = (prefs.getString("b_url", null) ?: "https://rmarket.cartazfacil.pro").trimEnd('/'),
            usuario = prefs.getString("b_user", null) ?: embU,
            senha = prefs.getString("b_pass", null) ?: embP
        )
    }

    fun salvarA(m: MercadoConfig) = prefs.edit()
        .putString("a_nome", m.nome).putString("a_url", m.baseUrl)
        .putString("a_user", m.usuario).putString("a_pass", m.senha).apply()

    fun salvarB(m: MercadoConfig) = prefs.edit()
        .putString("b_nome", m.nome).putString("b_url", m.baseUrl)
        .putString("b_user", m.usuario).putString("b_pass", m.senha).apply()

    fun limpar() = prefs.edit().clear().apply()

    companion object {
        private fun decodificar(s: String): String {
            if (s.isEmpty()) return ""
            val key = "qp-7Kx!".toByteArray(Charsets.UTF_8)
            val b = Base64.decode(s, Base64.DEFAULT)
            return String(ByteArray(b.size) { (b[it].toInt() xor key[it % key.size].toInt()).toByte() }, Charsets.UTF_8)
        }
    }
}
