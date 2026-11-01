package com.quickprice.app

import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import kotlin.math.abs
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val pool = Executors.newFixedThreadPool(3)
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()
    private lateinit var store: MarketStore
    private lateinit var userAgent: String

    private lateinit var txtStatus: TextView
    private lateinit var txtProduto: TextView
    private lateinit var txtCodigo: TextView
    private lateinit var txtDiff: TextView
    private lateinit var cardA: LinearLayout
    private lateinit var cardB: LinearLayout
    private lateinit var nomeA: TextView
    private lateinit var nomeB: TextView
    private lateinit var precoA: TextView
    private lateinit var precoB: TextView
    private lateinit var seloA: TextView
    private lateinit var seloB: TextView
    private lateinit var logoA: ImageView
    private lateinit var logoB: ImageView
    private lateinit var imgProduto: ImageView
    private lateinit var imgBackdrop: ImageView
    private lateinit var edtCodigo: EditText
    private lateinit var btnScan: View
    private lateinit var btnSalvar: View
    private lateinit var btnLista: Button
    private lateinit var btnVoz: View
    private lateinit var listaStore: ListaComprasStore
    private lateinit var historicoStore: HistoricoStore
    private var ultimoItem: ItemLista? = null
    @Volatile private var ultimaImagem: String? = null

    // Anti-disparo-duplo da busca
    private var ultimaConsulta = ""
    private var ultimaConsultaHora = 0L
    // EAN aprendido por código interno (foto funciona mesmo buscando pelo código do mercado)
    private lateinit var eanCache: MutableMap<String, String>

    @Volatile private var clientA: CartazFacilClient? = null
    @Volatile private var clientB: CartazFacilClient? = null
    @Volatile private var busyLogin = false
    @Volatile private var imagemToken = 0
    private var soundPool: SoundPool? = null
    private var bipId = 0
    private var alertaId = 0
    private var cestaA = 0.0
    private var cestaB = 0.0
    private lateinit var txtCesta: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = MarketStore(this)
        eanCache = carregarEanCache()
        userAgent = WebSettings.getDefaultUserAgent(applicationContext)
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also {
                bipId = it.load(this, R.raw.bip, 1)
                alertaId = it.load(this, R.raw.alert, 1)
            }

        txtStatus = findViewById(R.id.txtStatus)
        txtCesta = findViewById(R.id.txtCesta)
        txtCesta.setOnLongClickListener {
            cestaA = 0.0; cestaB = 0.0
            atualizarCesta()
            Toast.makeText(this, "Cesta zerada.", Toast.LENGTH_SHORT).show()
            true
        }
        txtProduto = findViewById(R.id.txtProduto)
        txtCodigo = findViewById(R.id.txtCodigo)
        txtDiff = findViewById(R.id.txtDiff)
        cardA = findViewById(R.id.cardA)
        cardB = findViewById(R.id.cardB)
        nomeA = findViewById(R.id.nomeA)
        nomeB = findViewById(R.id.nomeB)
        precoA = findViewById(R.id.precoA)
        precoB = findViewById(R.id.precoB)
        seloA = findViewById(R.id.seloA)
        seloB = findViewById(R.id.seloB)
        logoA = findViewById(R.id.logoA)
        logoB = findViewById(R.id.logoB)
        imgProduto = findViewById(R.id.imgProduto)
        imgBackdrop = findViewById(R.id.imgBackdrop)
        edtCodigo = findViewById(R.id.edtCodigo)
        btnScan = findViewById(R.id.btnScan)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnLista = findViewById(R.id.btnLista)
        btnVoz = findViewById(R.id.btnVoz)
        btnVoz.setOnClickListener { iniciarVoz() }
        listaStore = ListaComprasStore(this)
        historicoStore = HistoricoStore(this)

        btnSalvar.setOnClickListener { salvarNaLista() }
        btnLista.setOnClickListener { startActivity(Intent(this, ListaActivity::class.java)) }
        atualizarBadgeLista()

        // Botões flutuantes: cada um arrasta separadamente
        tornarArrastavel(btnSalvar, btnSalvar)
        tornarArrastavel(btnScan, btnScan)

        btnScan.setOnClickListener { abrirScanner() }
        edtCodigo.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                consultar(edtCodigo.text.toString())
                true
            } else false
        }

        val ma = store.mercadoA()
        val mb = store.mercadoB()
        nomeA.text = ma.nome
        nomeB.text = mb.nome
        aplicarLogo(logoA, ma.baseUrl)
        aplicarLogo(logoB, mb.baseUrl)
        conectarTudo()
        iniciarKeepAlive()
        verificarOfertasDoDia()
    }

    private fun aplicarLogo(img: ImageView, baseUrl: String) {
        val res = when {
            baseUrl.contains("carlaosupermercados", ignoreCase = true) -> R.drawable.logo_supercarlao
            baseUrl.contains("rmarket", ignoreCase = true) -> R.drawable.logo_redemarket
            else -> null
        }
        if (res == null) img.visibility = View.GONE else {
            img.visibility = View.VISIBLE
            img.setImageResource(res)
        }
    }

    private fun conectarTudo() {
        if (busyLogin) return
        busyLogin = true
        btnScan.isEnabled = false
        val ma = store.mercadoA()
        val mb = store.mercadoB()
        txtStatus.text = "Conectando em ${ma.nome} e ${mb.nome}..."
        pool.execute {
            val a = runCatching { CartazFacilClient(userAgent, ma.baseUrl).also { it.login(ma.usuario, ma.senha) } }
            val b = runCatching { CartazFacilClient(userAgent, mb.baseUrl).also { it.login(mb.usuario, mb.senha) } }
            clientA = a.getOrNull()
            clientB = b.getOrNull()
            runOnUiThread {
                busyLogin = false
                btnScan.isEnabled = true
                txtStatus.text = when {
                    clientA != null && clientB != null -> "Conectado nos dois mercados. Escaneie um produto."
                    clientA != null -> "Só ${ma.nome} conectou. Verifique o login de ${mb.nome} em Ajustes."
                    clientB != null -> "Só ${mb.nome} conectou. Verifique o login de ${ma.nome} em Ajustes."
                    else -> "Nenhum mercado conectou. Toque em AJUSTES para conferir usuários e senhas."
                }
            }
        }
    }

    private fun consultar(codigo: String) {
        val limpo = codigo.trim()
        if (limpo.isEmpty()) return
        // Alguns teclados disparam ENTER + "pesquisar" juntos; ignora o duplo
        val agora = System.currentTimeMillis()
        if (limpo == ultimaConsulta && agora - ultimaConsultaHora < 1200) return
        ultimaConsulta = limpo
        ultimaConsultaHora = agora
        if (!limpo.all { it.isDigit() }) { pesquisarPorNome(limpo); return }
        val ca = clientA
        val cb = clientB
        if (ca == null && cb == null) {
            Toast.makeText(this, "Nenhum mercado conectado.", Toast.LENGTH_SHORT).show()
            return
        }
        txtStatus.text = "Consultando \"$limpo\" nos dois mercados..."
        btnScan.isEnabled = false
        txtProduto.text = "Consultando..."
        precoA.text = "R$ -"
        precoB.text = "R$ -"
        btnSalvar.visibility = View.GONE
        ultimoItem = null
        imagemToken++
        mostrarImagem(null)

        val latch = CountDownLatch(2)
        var resA: Result<PriceLookupResult>? = null
        var resB: Result<PriceLookupResult>? = null
        pool.execute { resA = consultarSeguro(ca, store.mercadoA(), limpo); latch.countDown() }
        pool.execute { resB = consultarSeguro(cb, store.mercadoB(), limpo); latch.countDown() }
        latch.await(25, TimeUnit.SECONDS)

        runOnUiThread {
            btnScan.isEnabled = true
            mostrarResultado(limpo, resA, resB)
        }
    }

    /** Pesquisa por nome: consulta os dois mercados e mostra lista pra escolher o produto. */
    private fun pesquisarPorNome(termo: String) = pesquisarComTermos(listOf(termo))

    /** Pesquisa tentando vários termos (setores: usa sinônimos até achar produtos). */
    private fun pesquisarComTermos(termos: List<String>) {
        val ca = clientA
        val cb = clientB
        if (ca == null && cb == null) {
            Toast.makeText(this, "Nenhum mercado conectado.", Toast.LENGTH_SHORT).show()
            return
        }
        txtStatus.text = "Buscando produtos do setor nos dois mercados..."
        pool.execute {
            val achados = LinkedHashMap<String, String>() // código -> descrição
            for (termo in termos) {
                if (achados.size >= 40) break
                ca?.let { runCatching { it.searchProducts(termo) }.getOrNull() }
                    ?.forEach { p -> achados.putIfAbsent(p.code, p.description) }
                cb?.let { runCatching { it.searchProducts(termo) }.getOrNull() }
                    ?.forEach { p -> achados.putIfAbsent(p.code, p.description) }
            }
            runOnUiThread {
                txtStatus.text = "Conectado nos dois mercados. Escaneie um produto."
                when {
                    achados.isEmpty() -> Toast.makeText(
                        this, "Nenhum produto encontrado nesse setor.", Toast.LENGTH_LONG).show()
                    achados.size == 1 -> {
                        val codigo = achados.keys.first()
                        edtCodigo.setText(codigo)
                        consultar(codigo)
                    }
                    else -> mostrarEscolhaProduto(achados)
                }
            }
        }
    }


    /** Limpa a descrição que vem do sistema: remove "Familia:", "UN UN", traços soltos. */
    private fun limparDescricao(d: String): String {
        var s = Regex("\\s*[-|]\\s*Familia:.*$").replace(d, "")
        s = Regex("\\s+").replace(s, " ").trim()
        s = Regex("\\b(UN|KG|G|GR|ML|L|LT|CX|PCT|PT|UNID)\\s+(UN|KG|G|GR|ML|L|LT|CX|PCT|PT|UNID)\\b")
            .replace(s) { m -> m.groupValues[1] }
        s = Regex("\\s*-\\s*UN$", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("\\s*-\\s*$").replace(s, "")
        return s.trim()
    }

    /** Formata bonito: primeira letra de cada palavra em maiúsculo. */
    private fun bonito(d: String): String =
        d.lowercase().split(" ").joinToString(" ") { p ->
            if (p.length <= 2 && p !in listOf("kg", "ml", "cx")) p
            else p.replaceFirstChar { it.uppercase() }
        }

    /** Verifica se o preço atual caiu frente ao histórico; retorna o aviso ou vazio. */
    private fun alertaQueda(codigo: String, mercado: String, precoAtual: BigDecimal): String {
        val anteriores = historicoStore.historico(codigo, mercado)
        if (anteriores.size < 2) return ""
        val menorAnterior = anteriores.minOf { it.preco }
        val atual = precoAtual.toDouble()
        if (atual >= menorAnterior) return ""
        val queda = menorAnterior - atual
        val pct = queda / menorAnterior * 100.0
        if (pct < 3.0) return ""
        val dias = ((System.currentTimeMillis() - anteriores.minOf { it.data }) / 86_400_000L)
        val diasTxt = if (dias >= 1) " em ${dias}d" else ""
        return String.format(
            Locale("pt", "BR"),
            "🔥 %s: menor preço%s! Queda de R$ %.2f (%.0f%%)",
            mercado, diasTxt, queda, pct
        )
    }

    /** Mostra a lista de produtos encontrados com formatação limpa (nome em destaque, código discreto). */
    private fun mostrarEscolhaProduto(achados: LinkedHashMap<String, String>) {
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        val scroll = ScrollView(this).apply { addView(conteudo) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Escolha o produto (${achados.size})")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setCanceledOnTouchOutside(false) // digitar no campo não fecha mais a janela

        achados.entries.take(25).forEach { (codigo, descricao) ->
            val tv = TextView(this)
            val texto = android.text.SpannableString("$descricao\nCód. $codigo")
            texto.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, descricao.length, 0)
            texto.setSpan(android.text.style.AbsoluteSizeSpan(16, true), 0, descricao.length, 0)
            texto.setSpan(android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()), 0, descricao.length, 0)
            texto.setSpan(android.text.style.AbsoluteSizeSpan(12, true), descricao.length + 1, texto.length, 0)
            texto.setSpan(android.text.style.ForegroundColorSpan(0xFF9AA4BF.toInt()), descricao.length + 1, texto.length, 0)
            tv.text = texto
            tv.setPadding(0, dp(12), 0, dp(12))
            tv.setOnClickListener {
                dialog.dismiss()
                edtCodigo.setText(codigo)
                consultar(codigo)
            }
            conteudo.addView(tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val div = View(this)
            div.setBackgroundColor(0xFF2E3A66.toInt())
            conteudo.addView(div, ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        dialog.show()
    }

    /** Extrai o peso/volume da descrição (ex: "200G", "1KG", "900ML") e calcula R$/kg ou R$/L. */
    private fun precoUnitario(descricao: String, preco: BigDecimal): String? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(KG|KGS|G|GR|ML|L|LT|LTS)\\b",
            RegexOption.IGNORE_CASE).find(descricao) ?: return null
        val qtd = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        if (qtd <= 0) return null
        val unidade = m.groupValues[2].uppercase()
        val fator = when (unidade) {
            "G", "GR", "ML" -> qtd / 1000.0
            else -> qtd
        }
        val tipo = if (unidade in listOf("ML", "L", "LT", "LTS")) "L" else "kg"
        return String.format(Locale("pt", "BR"), "R$ %.2f/%s", preco.toDouble() / fator, tipo)
    }

    /**
     * Consulta com reconexão: se a sessão caiu (outro funcionário logou em outro PC),
     * faz login de novo e repete a consulta automaticamente.
     */
    private fun consultarSeguro(client: CartazFacilClient?, cfg: MercadoConfig, codigo: String): Result<PriceLookupResult> {
        if (client == null) return Result.failure(RuntimeException("Mercado não conectado"))
        val r1 = runCatching { client.lookupPrice(codigo) }
        val err = r1.exceptionOrNull()
        val sessaoCaiu = err is CartazLoginException ||
            (err?.message?.contains("HTTP", ignoreCase = true) == true)
        if (!sessaoCaiu) return r1
        val relogin = runCatching { client.login(cfg.usuario, cfg.senha) }
        return if (relogin.isSuccess) runCatching { client.lookupPrice(codigo) } else r1
    }

    /** Mantém a sessão ativa: a cada poucos minutos refaz o login nos dois mercados,
     *  garantindo que o app "ganhe" a disputa do duplo login. */
    private fun iniciarKeepAlive() {
        keepAlive.scheduleWithFixedDelay({
            try {
                val ma = store.mercadoA()
                val mb = store.mercadoB()
                clientA?.let { runCatching { it.login(ma.usuario, ma.senha) } }
                clientB?.let { runCatching { it.login(mb.usuario, mb.senha) } }
            } catch (_: Exception) { }
        }, 2, 4, TimeUnit.MINUTES)
    }

    private fun mostrarResultado(codigo: String, resA: Result<PriceLookupResult>?, resB: Result<PriceLookupResult>?) {
        val ma = store.mercadoA()
        val mb = store.mercadoB()

        val okA = resA?.getOrNull()
        val okB = resB?.getOrNull()
        val precoValA = okA?.price?.amount
        val precoValB = okB?.price?.amount

        val codProduto = okA?.product?.code ?: okB?.product?.code ?: "-"
        txtCodigo.text = if (codProduto == codigo) codigo else "$codProduto · $codigo"
        txtProduto.text = bonito(limparDescricao(
            okA?.product?.description ?: okB?.product?.description
                ?: "Produto não encontrado nos mercados."))

        // Ordena: o MENOR preço sempre fica em cima.
        data class Item(val nome: String, val precoTxt: String, val valor: BigDecimal?)
        val itens = listOf(
            Item(ma.nome, okA?.let { "R$ ${it.price.display}" } ?: erroCurto(resA), precoValA),
            Item(mb.nome, okB?.let { "R$ ${it.price.display}" } ?: erroCurto(resB), precoValB)
        ).sortedWith(compareBy({ it.valor == null }, { it.valor ?: BigDecimal.ZERO }))

        val top = itens[0]
        val bottom = itens[1]
        val soUm = (precoValA != null) != (precoValB != null)

        nomeA.text = top.nome
        precoA.text = top.precoTxt
        nomeB.text = bottom.nome
        precoB.text = bottom.precoTxt

        // A logo acompanha o mercado (não o cartão)
        val urlTop = if (itens[0].nome == ma.nome) ma.baseUrl else mb.baseUrl
        val urlBottom = if (itens[1].nome == ma.nome) ma.baseUrl else mb.baseUrl
        aplicarLogo(logoA, urlTop)
        aplicarLogo(logoB, urlBottom)

        cardA.setBackgroundResource(R.drawable.bg_card_vencedor)
        seloA.visibility = if (top.valor != null) View.VISIBLE else View.INVISIBLE
        seloA.text = if (soUm) "ÚNICO PREÇO" else "★ MENOR PREÇO"

        cardB.setBackgroundResource(R.drawable.bg_card_normal)
        seloB.visibility = if (top.valor != null && bottom.valor != null) View.VISIBLE else View.INVISIBLE

        txtDiff.text = when {
            precoValA != null && precoValB != null -> {
                val dif = (precoValA - precoValB).abs()
                val venc = if (precoValA <= precoValB) ma.nome else mb.nome
                "Diferença de R$ ${fmt(dif)} — melhor preço: $venc"
            }
            precoValA != null -> "Só ${ma.nome} tem este produto."
            precoValB != null -> "Só ${mb.nome} tem este produto."
            else -> "Preço indisponível nos dois mercados."
        }

        // Alerta de queda de preço (compara com o histórico local)
        val alertas = mutableListOf<String>()
        if (okA != null) alertas += alertaQueda(codigo, ma.nome, precoValA!!)
        if (okB != null) alertas += alertaQueda(codigo, mb.nome, precoValB!!)
        alertas.filter { it.isNotEmpty() }.forEach { txtDiff.append("\n" + it) }

        // Grava o preço atual no histórico (para comparações futuras)
        val descHist = okA?.product?.description ?: okB?.product?.description ?: ""
        val agora = System.currentTimeMillis()
        if (okA != null) historicoStore.registrar(RegistroPreco(codigo, descHist, ma.nome, precoValA!!.toDouble(), agora))
        if (okB != null) historicoStore.registrar(RegistroPreco(codigo, descHist, mb.nome, precoValB!!.toDouble(), agora))

        if (okA != null || okB != null) {
            // som diferente quando a diferença entre mercados é significativa
            val temOferta = precoValA != null && precoValB != null &&
                kotlin.math.abs(precoValA.subtract(precoValB).toDouble()) /
                maxOf(precoValA.toDouble(), precoValB.toDouble()) >= 0.03
            soundPool?.play(if (temOferta) alertaId else bipId, 1f, 1f, 1, 0, 1f)
            // soma na cesta do dia
            if (precoValA != null) cestaA += precoValA.toDouble()
            if (precoValB != null) cestaB += precoValB.toDouble()
            atualizarCesta()
            // Aprende o EAN dos códigos internos (foto funciona ao buscar pelo código do mercado)
            if (eanValido(codigo)) {
                okA?.product?.code?.let { eanCache["E:$it"] = codigo }
                okB?.product?.code?.let { eanCache["E:$it"] = codigo }
                salvarEanCache()
            }
            // Foto prioriza: imagem já aprendida por descrição > EAN digitado > EAN aprendido > busca por nome
            val normA = okA?.product?.description?.let { normDesc(it) }
            val normB = okB?.product?.description?.let { normDesc(it) }
            val imgAprendida = normA?.let { eanCache["I:$it"] }
                ?: normB?.let { eanCache["I:$it"] }
            val eanImagem = when {
                eanValido(codigo) -> codigo
                else -> eanCache["E:$codProduto"]
                    ?: normA?.let { eanCache["N:$it"] }
                    ?: normB?.let { eanCache["N:$it"] }
                    ?: codigo
            }
            if (imgAprendida != null) mostrarImagem(imgAprendida)
            else buscarImagemProduto(eanImagem, descHist)
        }


        // Prepara o item para a lista de compras
        val desc = okA?.product?.description ?: okB?.product?.description ?: ""
        ultimoItem = when {
            precoValA != null && precoValB != null -> {
                val vencA = precoValA <= precoValB
                ItemLista(
                    descricao = desc, codigo = codigo,
                    precoDisplay = "R$ ${(if (vencA) okA else okB)!!.price.display}",
                    precoValor = (if (vencA) precoValA else precoValB).toDouble(),
                    mercado = if (vencA) ma.nome else mb.nome,
                    economiaDisplay = "R$ ${fmt((precoValA - precoValB).abs())}",
                    economiaValor = (precoValA - precoValB).abs().toDouble(),
                    imagem = ultimaImagem ?: ""
                )
            }
            precoValA != null -> ItemLista(desc, codigo, "R$ ${okA!!.price.display}",
                precoValA.toDouble(), ma.nome, "", 0.0, ultimaImagem ?: "")
            precoValB != null -> ItemLista(desc, codigo, "R$ ${okB!!.price.display}",
                precoValB.toDouble(), mb.nome, "", 0.0, ultimaImagem ?: "")
            else -> null
        }
        btnSalvar.visibility = if (ultimoItem != null) View.VISIBLE else View.GONE

        txtStatus.text = "Conectado nos dois mercados. Escaneie um produto."
    }

    private fun salvarNaLista() {
        val item = ultimoItem ?: return
        val somou = listaStore.incrementarSeExistir(item.codigo, item.mercado, item.precoDisplay)
        if (!somou) listaStore.adicionar(item)
        btnSalvar.visibility = View.GONE
        ultimoItem = null
        atualizarBadgeLista()
        Toast.makeText(this,
            if (somou) "Quantidade atualizada 🛒" else "Salvo na lista 🛒",
            Toast.LENGTH_SHORT).show()
    }

    /** Mostra o total acumulado da sessão nos dois mercados (segure pra zerar). */
    private fun atualizarCesta() {
        val ma = store.mercadoA()
        val mb = store.mercadoB()
        if (cestaA <= 0 && cestaB <= 0) {
            txtCesta.visibility = View.GONE
            return
        }
        txtCesta.text = "🧺 Cesta: ${ma.nome} " + moeda(cestaA) + " · ${mb.nome} " + moeda(cestaB) +
            "  (segure p/ zerar)"
        txtCesta.visibility = View.VISIBLE
    }

    private fun moeda(v: Double): String =
        String.format(Locale("pt", "BR"), "R$ %.2f", v)

    /** Uma vez por dia: mostra produtos consultados cujo preço caiu hoje. */
    private fun verificarOfertasDoDia() {
        try {
            val hoje = System.currentTimeMillis() / 86_400_000L
            val marcador = getSharedPreferences("app_lock", MODE_PRIVATE)
            if (marcador.getLong("ofertas_dia", 0) == hoje) return
            marcador.edit().putLong("ofertas_dia", hoje).apply()

            val registros = historicoStore.todos()
            val ofertas = mutableListOf<String>()
            registros.groupBy { it.codigo + "|" + it.mercado }.forEach { (_, lista) ->
                val doDia = lista.filter { it.data / 86_400_000L == hoje }
                if (doDia.isNotEmpty()) {
                    val minAnterior = lista.filter { it.data / 86_400_000L < hoje }
                        .minOfOrNull { it.preco } ?: return@forEach
                    val menorHoje = doDia.minOf { it.preco }
                    val pct = (minAnterior - menorHoje) / minAnterior * 100.0
                    if (pct >= 3.0) {
                        ofertas.add(
                            "\uD83D\uDD25 " + lista.first().descricao + "\n" +
                                "   " + lista.first().mercado + " — " +
                                moeda(menorHoje) + " (-" +
                                String.format(Locale("pt", "BR"), "%.0f", pct) + "%)"
                        )
                    }
                }
            }
            if (ofertas.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("\uD83D\uDD25 Ofertas de hoje (" + ofertas.size + ")")
                    .setMessage(ofertas.take(10).joinToString("\n\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        } catch (_: Exception) { }
    }

    private fun atualizarBadgeLista() {
        btnLista.text = "🛒 Lista (${listaStore.tamanho()})"
    }

    /** EAN válido tem 8 a 14 dígitos (EAN-8, UPC-A, EAN-13/14). */
    private fun eanValido(s: String) = s.length in 8..14 && s.all { it.isDigit() }

    /** Descrição normalizada: limpa, tira stopwords e ORDENA os tokens
     *  (assim "Sagu Yoki Uva 250g" = "Sagu Yoki 250g Uva", de mercados diferentes). */
    private fun normDesc(d: String): String =
        limparDescricao(d).lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in listOf("un", "unid", "familia") }
            .sorted()
            .joinToString(" ")

    /** Cache EAN aprendido por código interno (gravado no aparelho). */
    private fun carregarEanCache(): MutableMap<String, String> {
        val prefs = getSharedPreferences("ean_cache", MODE_PRIVATE)
        val mapa = mutableMapOf<String, String>()
        prefs.all.forEach { (k, v) -> if (v is String) mapa[k] = v as String }
        return mapa
    }

    private fun salvarEanCache() {
        val ed = getSharedPreferences("ean_cache", MODE_PRIVATE).edit()
        ed.clear()
        eanCache.forEach { (k, v) -> ed.putString(k, v) }
        ed.apply()
    }

    /** Busca a foto no Open Food Facts pelo NOME do produto (busca por voz/texto).
     * Só aceita se o nome retornado tiver alta similaridade com o buscado (evita foto errada). */
    private fun buscarImagemPorNome(descricao: String): String? {
        val limpa = limparPraBusca(descricao)
        if (limpa.length < 4) return null
        // tokens só com letras (sem tamanhos tipo "170g"), sem acentos
        val tokens = semAcentos(limpa).lowercase()
            .split(Regex("[^a-z]+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return null
        // 1ª tentativa com todos os termos; se não achar, 2ª com só os 3 principais
        return buscarOff(tokens) ?: buscarOff(tokens.take(3))
    }

    /** Consulta o Open Food Facts e devolve a foto do MELHOR match (>= metade dos termos). */
    private fun buscarOff(tokens: List<String>): String? {
        return try {
            val url = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=" +
                java.net.URLEncoder.encode(tokens.joinToString(" "), "UTF-8") +
                "&search_simple=1&action=process&json=1&page_size=5"
            val resp = org.jsoup.Jsoup.connect(url)
                .userAgent("ComparaPreco/2.0 (consulta de precos pessoal)")
                .timeout(9000)
                .ignoreContentType(true)
                .execute()
            val arr = org.json.JSONObject(resp.body()).optJSONArray("products") ?: return null
            var melhorUrl: String? = null
            var melhorScore = 0.0
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val nome = p.optString("product_name")
                val img = p.optString("image_front_url")
                if (nome.isEmpty() || img.isEmpty()) continue
                val nomeNorm = semAcentos(nome).lowercase()
                val score = tokens.count { it in nomeNorm }.toDouble() / tokens.size
                if (score > melhorScore) { melhorScore = score; melhorUrl = img }
            }
            if (melhorScore >= 0.5) melhorUrl else null
        } catch (e: Exception) { null }
    }

    /** Remove acentos pra comparar nomes (nestle = nestlé). */
    private fun semAcentos(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun limparPraBusca(d: String): String {
        var s = Regex("\\s*[-|]\\s*Familia:.*$").replace(d, "")
        s = Regex("\\b(UN|UNID)\\b", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }

    /** Foto do produto: Open Food Facts por EAN; se não houver EAN (voz/nome),
     * tenta Open Food Facts por nome com filtro de similaridade. */
    private fun buscarImagemProduto(codigoBarras: String, descricao: String) {
        val token = ++imagemToken
        val eanValido = codigoBarras.length in 8..14 && codigoBarras.all { it.isDigit() }
        if (!eanValido) {
            pool.execute {
                val url = buscarImagemPorNome(descricao)
                runOnUiThread { if (token == imagemToken) mostrarImagem(url) }
            }
            return
        }
        pool.execute {
            // 1) Open Food Facts (API oficial, foto frontal do produto)
            var imageUrl: String? = null
            try {
                val resp = org.jsoup.Jsoup
                    .connect("https://world.openfoodfacts.org/api/v2/product/$codigoBarras.json")
                    .userAgent("ComparaPreco/2.0 (consulta de precos pessoal)")
                    .timeout(8000)
                    .ignoreContentType(true)
                    .execute()
                val json = org.json.JSONObject(resp.body())
                if (json.optInt("status") == 1) {
                    val img = json.optJSONObject("product")?.optString("image_front_url")
                    if (!img.isNullOrEmpty()) imageUrl = img
                }
            } catch (e: Exception) { }
            // 2) Fallback: BeepScan (produtos fora do Open Food Facts)
            if (imageUrl == null) {
                imageUrl = try {
                    val document = org.jsoup.Jsoup.connect("https://www.beepscan.com/barcode/$codigoBarras")
                        .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        .timeout(8000)
                        .get()
                    document.selectFirst("meta[property=og:image]")?.attr("abs:content")
                        ?.takeIf { it.startsWith("http") }
                        ?: document.select("img[src]")
                            .map { it.attr("abs:src") }
                            .firstOrNull {
                                it.startsWith("http") &&
                                    !it.endsWith(".svg", ignoreCase = true) &&
                                    !it.contains("logo", ignoreCase = true)
                            }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Erro ao buscar imagem: ${e.message}")
                    null
                }
            }
            // Aprende: descrição normalizada -> EAN + URL da foto (vale p/ código interno e nome)
            if (imageUrl != null && eanValido(codigoBarras)) {
                val norm = normDesc(descricao)
                if (norm.isNotEmpty()) {
                    eanCache["N:$norm"] = codigoBarras
                    eanCache["I:$norm"] = imageUrl
                    salvarEanCache()
                }
            }
            runOnUiThread { if (token == imagemToken) mostrarImagem(imageUrl) }
        }
    }

    /** Foto com degradê estilo Netflix + fundo desfocado atrás. */
    private fun mostrarImagem(model: String?) {
        ultimaImagem = model
        // se o item ainda não foi salvo, guarda a foto nele para a lista
        ultimoItem?.let {
            if (it.imagem.isEmpty() && !model.isNullOrEmpty()) {
                ultimoItem = it.copy(imagem = model)
            }
        }
        if (isFinishing || isDestroyed) return
        if (model.isNullOrEmpty()) {
            Glide.with(this).clear(imgBackdrop)
            imgBackdrop.setImageDrawable(null)
            imgProduto.setImageResource(R.drawable.ic_placeholder_box)
            return
        }
        Glide.with(this)
            .load(model)
            .error(R.drawable.ic_placeholder_box)
            .transform(NetflixEdgeTransformation.getTransformation())
            .into(imgProduto)

        Glide.with(this)
            .load(model)
            .override(24, 48)
            .transform(CenterCrop())
            .into(imgBackdrop)
    }

    private fun erroCurto(r: Result<PriceLookupResult>?): String = when {
        r == null -> "Mercado não conectado"
        r.getOrNull() != null -> "R$ ${r.getOrNull()!!.price.display}"
        r.exceptionOrNull() is ProductNotFoundException -> "Não encontrado"
        r.exceptionOrNull() is AmbiguousProductException -> "Mais de um produto"
        r.exceptionOrNull() is CartazLoginException -> "Sessão expirou"
        else -> {
            val msg = r.exceptionOrNull()?.message ?: "sem detalhes"
            "Erro: " + msg.take(70)
        }
    }

    private fun fmt(v: BigDecimal): String {
        val parts = v.toPlainString().split(".")
        val inteiros = parts[0].reversed().chunked(3).joinToString(".").reversed()
        val dec = (parts.getOrNull(1) ?: "0").padEnd(2, '0').take(2)
        return "$inteiros,$dec"
    }

    /** Torna um painel arrastável: segura e arrasta pra mover; toque simples continua clicando. */
    private fun tornarArrastavel(toque: View, movido: View) {
        val parent = movido.parent as ViewGroup
        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var arrastando = false
        val limiar = dp(14)
        toque.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = movido.x; startY = movido.y
                    arrastando = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!arrastando && (abs(dx) > limiar || abs(dy) > limiar)) arrastando = true
                    if (arrastando) {
                        val nx = (startX + dx).coerceIn(0f, (parent.width - movido.width).toFloat())
                        val ny = (startY + dy).coerceIn(0f, (parent.height - movido.height).toFloat())
                        movido.x = nx
                        movido.y = ny
                    }
                    arrastando
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val foiArraste = arrastando
                    arrastando = false
                    // se foi arraste, consome o evento; se não, deixa o clique passar
                    foiArraste
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Abre o reconhecimento de voz do celular pra buscar produto falando. */
    private fun iniciarVoz() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o nome do produto")
        }
        try {
            startActivityForResult(intent, REQ_VOZ)
        } catch (e: Exception) {
            Toast.makeText(this, "Comando de voz não disponível neste aparelho.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirScanner() {
        startActivityForResult(Intent(this, ScannerActivity::class.java), REQ_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            val codigo = data?.getStringExtra("codigo").orEmpty()
            edtCodigo.setText(codigo)
            if (codigo.isNotEmpty()) consultar(codigo)
        }
        if (requestCode == REQ_VOZ && resultCode == RESULT_OK) {
            val falado = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty().trim()
            if (falado.isEmpty()) return
            edtCodigo.setText(falado)
            if (falado.all { it.isDigit() }) consultar(falado) else pesquisarPorNome(falado)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listaStore.isInitialized) atualizarBadgeLista()
    }

    override fun onDestroy() {
        soundPool?.release()
        soundPool = null
        pool.shutdownNow()
        keepAlive.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_SCAN = 42
        private const val REQ_VOZ = 43
    }
}
