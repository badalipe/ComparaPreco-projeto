package com.quickprice.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import org.jsoup.Jsoup
import java.util.concurrent.Executors
import java.util.Locale

class ListaActivity : AppCompatActivity() {

    private lateinit var store: ListaComprasStore
    private lateinit var histStore: HistoricoStore
    private lateinit var container: LinearLayout
    private val imgPool = Executors.newSingleThreadExecutor()
    private val cacheImg = HashMap<String, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ListaComprasStore(this)
        histStore = HistoricoStore(this)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(container)
        setContentView(scroll)
        montar()
    }

    private fun montar() {
        container.removeAllViews()
        val itens = store.todos()

        container.addView(TextView(this).apply {
            text = "🛒 LISTA DE COMPRAS"
            setTextColor(0xFF8FA3FF.toInt())
            textSize = 13f
        })

        if (itens.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "\nNenhum item salvo ainda.\n\nEscaneie um produto e toque no\ncarrinho verde para salvar."
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            return
        }

        itens.forEachIndexed { index, item ->
            container.addView(linha(item, index))
        }

        container.addView(divisor())
        val porMercado = LinkedHashMap<String, Double>()
        itens.forEach {
            val chave = it.mercado
            porMercado[chave] = (porMercado[chave] ?: 0.0) + it.precoValor * it.quantidade
        }
        porMercado.forEach { (mercado, total) ->
            container.addView(TextView(this).apply {
                text = "Gastar no $mercado: ${moeda(total)}"
                setTextColor(Color.WHITE)
                textSize = 16f
            })
        }

        container.addView(TextView(this).apply {
            text = "\n💰 ECONOMIA TOTAL: ${moeda(store.economiaTotal())}"
            setTextColor(0xFF4ADE80.toInt())
            textSize = 19f
        })

        val btnEnviar = Button(this).apply {
            text = "Enviar lista no WhatsApp"
            setTextColor(Color.WHITE)
            textSize = 15f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF25D366.toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_whatsapp_24, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener { enviarLista() }
        }
        container.addView(btnEnviar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val btnLimpar = Button(this).apply {
            text = "🗑 Limpar lista"
            setOnClickListener {
                store.limpar()
                montar()
                Toast.makeText(this@ListaActivity, "Lista limpa.", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnLimpar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun linha(item: ItemLista, index: Int): LinearLayout {
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            setBackgroundColor(0xFF171D36.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        carregarImagem(img, item.codigo, item.imagem)
        linha.addView(img)

        val texto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, dp(8), 0)
        }
        texto.addView(TextView(this).apply {
            text = bonito(limparDescricao(item.descricao)) +
                if (item.quantidade > 1) "  ×${item.quantidade}" else ""
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
        })
        texto.addView(TextView(this).apply {
            val totalItem = item.precoValor * item.quantidade
            text = "${item.mercado} · ${item.precoDisplay}" +
                (if (item.quantidade > 1) " = ${moeda(totalItem)}" else "") +
                (if (item.economiaValor > 0)
                    "   (economia ${moeda(item.economiaValor * item.quantidade)})" else "")
            setTextColor(if (item.economiaValor > 0) 0xFF4ADE80.toInt() else 0xFF9AA4BF.toInt())
            textSize = 13f
        })
        linha.addView(texto)

        val btnExcluir = Button(this).apply {
            text = "✕"
            setOnClickListener {
                store.remover(index)
                montar()
            }
        }
        linha.addView(btnExcluir, ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))

        // toque longo no item mostra o histórico de preços
        linha.setOnLongClickListener {
            mostrarHistorico(item)
            true
        }

        return linha
    }

    /** Mostra o histórico de preços do produto (mín/méd/máx + últimos registros). */
    private fun mostrarHistorico(item: ItemLista) {
        val regs = histStore.historico(item.codigo, item.mercado)
        val msg = if (regs.isEmpty()) {
            "Sem histórico de preço para este produto."
        } else {
            val ord = regs.sortedByDescending { it.data }
            val min = ord.minOf { it.preco }
            val max = ord.maxOf { it.preco }
            val media = ord.sumOf { it.preco } / ord.size
            val sb = StringBuilder(
                String.format(Locale("pt", "BR"),
                    "Mín: R$ %.2f   Méd: R$ %.2f   Máx: R$ %.2f\n\n",
                    min, media, max))
            ord.take(10).forEach {
                val data = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
                    .format(java.util.Date(it.data))
                sb.append("$data — ${moeda(it.preco)}\n")
            }
            sb.toString()
        }
        AlertDialog.Builder(this)
            .setTitle(bonito(limparDescricao(item.descricao)))
            .setMessage(msg)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun carregarImagem(img: ImageView, barcode: String, urlSalva: String) {
        if (urlSalva.isNotEmpty()) {
            Glide.with(this).load(urlSalva).error(R.drawable.ic_placeholder_box).into(img)
            return
        }
        img.setImageResource(R.drawable.ic_placeholder_box)
        if (barcode.isEmpty()) return
        cacheImg[barcode]?.let {
            if (it.isNotEmpty()) Glide.with(this).load(it).error(R.drawable.ic_placeholder_box).into(img)
            return
        }
        imgPool.execute {
            var url = ""
            // 1) Open Food Facts (API oficial)
            try {
                val resp = Jsoup.connect("https://world.openfoodfacts.org/api/v2/product/$barcode.json")
                    .userAgent("ComparaPreco/2.0 (consulta de precos pessoal)")
                    .timeout(8000)
                    .ignoreContentType(true)
                    .execute()
                val json = org.json.JSONObject(resp.body())
                if (json.optInt("status") == 1) {
                    val img = json.optJSONObject("product")?.optString("image_front_url")
                    if (!img.isNullOrEmpty()) url = img
                }
            } catch (e: Exception) { }
            // 2) Fallback: BeepScan (com tentativas)
            if (url.isEmpty()) {
                repeat(3) { tentativa ->
                    if (url.isEmpty()) {
                        if (tentativa > 0) try { Thread.sleep(1500) } catch (_: Exception) {}
                        url = try {
                            val doc = Jsoup.connect("https://www.beepscan.com/barcode/$barcode")
                                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                                .timeout(10000)
                                .get()
                            doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
                                ?.takeIf { it.startsWith("http") } ?: ""
                        } catch (e: Exception) { "" }
                    }
                }
            }
            if (url.isNotEmpty()) {
                cacheImg[barcode] = url
                runOnUiThread {
                    Glide.with(this@ListaActivity).load(url)
                        .error(R.drawable.ic_placeholder_box).into(img)
                }
            }
        }
    }

    /** Monta o texto da lista e abre o compartilhamento (WhatsApp). */
    private fun enviarLista() {
        val itens = store.todos()
        if (itens.isEmpty()) {
            Toast.makeText(this, "A lista está vazia.", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("🛒 *MINHA LISTA - COMPARA PREÇO*\n\n")
        itens.forEach { item ->
            sb.append("▪️ *")
            if (item.quantidade > 1) sb.append("x${item.quantidade} ")
            sb.append(bonito(limparDescricao(item.descricao))).append("*\n")
            sb.append(item.mercado).append(" — ").append(item.precoDisplay)
            if (item.quantidade > 1) {
                sb.append(" = ${moeda(item.precoValor * item.quantidade)}")
            }
            if (item.economiaValor > 0) {
                sb.append(" _(economia ${moeda(item.economiaValor * item.quantidade)})_")
            }
            sb.append("\n\n")
        }
        sb.append("💰 *Total: ${moeda(store.totalGeral())}*")
        val economia = store.economiaTotal()
        if (economia > 0) sb.append("\n✅ *Economia: ${moeda(economia)}*")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Enviar lista"))
    }

    private fun limparDescricao(d: String): String {
        var s = d
        s = Regex("\\s*[-|]\\s*Familia:.*$").replace(s, "")
        s = Regex("\\s+").replace(s, " ").trim()
        s = Regex("\\b(UN|KG|G|GR|ML|L|LT|CX|PCT|PT|UNID)\\s+(UN|KG|G|GR|ML|L|LT|CX|PCT|PT|UNID)\\b")
            .replace(s) { m -> m.groupValues[1] }
        s = Regex("\\s*-\\s*UN$", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("\\s*-\\s*$").replace(s, "")
        return s.trim()
    }

    private fun bonito(d: String): String =
        d.lowercase().split(" ").joinToString(" ") { p ->
            if (p.length <= 2 && p !in listOf("kg", "ml", "cx")) p
            else p.replaceFirstChar { it.uppercase() }
        }

    private fun divisor(): android.view.View {
        val v = android.view.View(this)
        v.setBackgroundColor(0xFF2E3A66.toInt())
        return v
    }

    private fun moeda(v: Double): String = String.format(Locale("pt", "BR"), "R$ %.2f", v)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
