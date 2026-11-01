package com.quickprice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ItemLista(
    val descricao: String,
    val codigo: String,
    val precoDisplay: String,
    val precoValor: Double,
    val mercado: String,
    val economiaDisplay: String,
    val economiaValor: Double,
    val imagem: String,
    val quantidade: Int = 1
)

/** Lista de compras salva no aparelho (fica mesmo fechando o app). */
internal class ListaComprasStore(context: Context) {
    private val prefs = context.getSharedPreferences("lista_compras", Context.MODE_PRIVATE)

    fun todos(): List<ItemLista> {
        val arr = JSONArray(prefs.getString("itens", "[]") ?: "[]")
        val lista = ArrayList<ItemLista>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            lista.add(ItemLista(
                descricao = o.optString("d"),
                codigo = o.optString("c"),
                precoDisplay = o.optString("p"),
                precoValor = o.optDouble("pv"),
                mercado = o.optString("m"),
                economiaDisplay = o.optString("e"),
                economiaValor = o.optDouble("ev"),
                imagem = o.optString("i"),
                quantidade = o.optInt("q", 1)
            ))
        }
        return lista
    }

    private fun salvarTodos(lista: List<ItemLista>) {
        val arr = JSONArray()
        lista.forEach { item ->
            arr.put(JSONObject().apply {
                put("d", item.descricao); put("c", item.codigo)
                put("p", item.precoDisplay); put("pv", item.precoValor)
                put("m", item.mercado); put("e", item.economiaDisplay)
                put("ev", item.economiaValor); put("i", item.imagem)
                put("q", item.quantidade)
            })
        }
        prefs.edit().putString("itens", arr.toString()).apply()
    }

    fun adicionar(item: ItemLista) = salvarTodos(todos() + item)

    /** Se já existe o mesmo item (mesmo código/mercado/preço), só aumenta a quantidade. */
    fun incrementarSeExistir(codigo: String, mercado: String, precoDisplay: String): Boolean {
        val lista = todos().toMutableList()
        val idx = lista.indexOfFirst {
            it.codigo == codigo && it.mercado == mercado && it.precoDisplay == precoDisplay
        }
        if (idx < 0) return false
        val item = lista[idx]
        lista[idx] = item.copy(quantidade = item.quantidade + 1)
        salvarTodos(lista)
        return true
    }

    fun remover(index: Int) {
        val lista = todos().toMutableList()
        if (index in lista.indices) {
            lista.removeAt(index)
            salvarTodos(lista)
        }
    }

    fun limpar() = prefs.edit().remove("itens").apply()

    fun tamanho(): Int = todos().size

    fun economiaTotal(): Double = todos().sumOf { it.economiaValor * it.quantidade }

    fun totalGeral(): Double = todos().sumOf { it.precoValor * it.quantidade }
}
