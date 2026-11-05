package com.seuapp.comparapreco.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Representa um produto encontrado, seja no cache ou em alguma das APIs.
 */
data class Produto(
    val codigoBarras: String,
    val nome: String?,
    val imagemUrl: String?,
    val fonte: String // "cache", "beepscan", "si5" ou "eanpictures" - útil para depurar
)

/**
 * Busca um produto por código de barras seguindo uma cadeia de fontes,
 * parando na primeira que encontrar resultado. Produtos encontrados são
 * salvos no cache (Firestore), então o MESMO produto escaneado por
 * qualquer funcionário depois disso não gasta mais cota de API nenhuma.
 *
 * Ordem da cadeia:
 *  1. Cache local (Firestore)      - instantâneo, grátis, sem limite
 *  2. BeepScan                      - sua integração já existente
 *  3. SI5 Global                    - fallback gratuito (100 consultas/dia por token)
 *  4. EAN Pictures                  - último recurso, só pra imagem
 */
class ProductRepository(
    private val si5Token: String // seu token gratuito, gerado em global.si5.com.br/login
) {
    private val db = FirebaseFirestore.getInstance()
    private val cacheCollection = db.collection("produtos_cache")
    private val http = OkHttpClient()

    suspend fun buscarProduto(codigoBarras: String): Produto? {
        // 1. Cache primeiro - não gasta nenhuma API se já tiver sido escaneado antes
        buscarNoCache(codigoBarras)?.let { return it }

        // 2. BeepScan - sua função já existente
        buscarNoBeepScan(codigoBarras)?.let { produto ->
            salvarNoCache(produto)
            return produto
        }

        // 3. SI5 Global
        buscarNoSi5Global(codigoBarras)?.let { produto ->
            salvarNoCache(produto)
            return produto
        }

        // 4. EAN Pictures (apenas imagem, ver nota no método)
        buscarNoEanPictures(codigoBarras)?.let { produto ->
            salvarNoCache(produto)
            return produto
        }

        return null // nenhuma fonte encontrou - pode cair no cadastro manual
    }

    // ---------- 1. CACHE (Firestore) ----------

    private suspend fun buscarNoCache(codigoBarras: String): Produto? {
        return try {
            val doc = cacheCollection.document(codigoBarras).get().await()
            if (doc.exists()) {
                Produto(
                    codigoBarras = codigoBarras,
                    nome = doc.getString("nome"),
                    imagemUrl = doc.getString("imagemUrl"),
                    fonte = "cache"
                )
            } else null
        } catch (e: Exception) {
            null // se o cache falhar por algum motivo, segue a cadeia normalmente
        }
    }

    private fun salvarNoCache(produto: Produto) {
        val dados = hashMapOf(
            "nome" to produto.nome,
            "imagemUrl" to produto.imagemUrl,
            "fonteOriginal" to produto.fonte,
            "atualizadoEm" to System.currentTimeMillis()
        )
        // não precisa de await: pode gravar em segundo plano sem travar a UI
        cacheCollection.document(produto.codigoBarras).set(dados)
    }

    // ---------- 2. BEEPSCAN ----------

    private suspend fun buscarNoBeepScan(codigoBarras: String): Produto? {
        // TODO: troque este corpo pela sua função atual do BeepScan.
        // Só garanta que o retorno seja um Produto(codigoBarras, nome, imagemUrl, "beepscan")
        // ou null quando não encontrar, para a cadeia continuar certinho.
        return null
    }

    // ---------- 3. SI5 GLOBAL ----------
    // Doc oficial: https://global.si5.com.br/documentacao
    // Grátis: 100 consultas/dia por token, sem cartão.

    private fun buscarNoSi5Global(codigoBarras: String): Produto? {
        return try {
            val request = Request.Builder()
                .url("https://global.si5.com.br/api/produtos/$codigoBarras")
                .addHeader("Authorization", "Bearer $si5Token")
                .addHeader("Accept", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                Produto(
                    codigoBarras = codigoBarras,
                    nome = json.optString("descricao").ifBlank { null },
                    imagemUrl = json.optString("imagem_url").ifBlank { null },
                    fonte = "si5"
                )
            }
        } catch (e: IOException) {
            null
        }
    }

    // ---------- 4. EAN PICTURES ----------

    private fun buscarNoEanPictures(codigoBarras: String): Produto? {
        // Atenção: a EAN Pictures não tem API REST oficial e documentada
        // publicamente. O que a comunidade usa é um projeto de referência
        // que consulta o site e extrai a imagem do HTML (ex:
        // github.com/valterpatrick/EanPictures). Use só como complemento
        // visual, não como fonte de nome/preço confiável.
        // TODO: implementar se decidir usar esse projeto como referência.
        return null
    }
}
