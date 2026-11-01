package com.quickprice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RegistroPreco(
    val codigo: String,
    val descricao: String,
    val mercado: String,
    val preco: Double,
    val data: Long
)

/** Histórico local de preços consultados (base para o alerta de queda). */
internal class HistoricoStore(context: Context) {
    private val prefs = context.getSharedPreferences("historico_precos", Context.MODE_PRIVATE)

    fun todos(): List<RegistroPreco> {
        val arr = JSONArray(prefs.getString("registros", "[]") ?: "[]")
        val lista = ArrayList<RegistroPreco>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            lista.add(RegistroPreco(
                codigo = o.optString("c"),
                descricao = o.optString("d"),
                mercado = o.optString("m"),
                preco = o.optDouble("p"),
                data = o.optLong("t")
            ))
        }
        return lista
    }

    fun historico(codigo: String, mercado: String): List<RegistroPreco> =
        todos().filter { it.codigo == codigo && it.mercado == mercado }

    fun registrar(r: RegistroPreco) {
        val arr = JSONArray(prefs.getString("registros", "[]") ?: "[]")
        arr.put(JSONObject().apply {
            put("c", r.codigo); put("d", r.descricao); put("m", r.mercado)
            put("p", r.preco); put("t", r.data)
        })
        // mantém no máx. 400 registros (mais antigos saem)
        val novo = JSONArray()
        val inicio = maxOf(0, arr.length() - 400)
        for (i in inicio until arr.length()) novo.put(arr.get(i))
        prefs.edit().putString("registros", novo.toString()).apply()
    }
}
