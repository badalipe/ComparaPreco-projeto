package com.quickprice.app

import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.jsoup.Jsoup

internal data class PriceLookupResult(
    val barcode: String,
    val product: CartazProduct,
    val price: CartazPrice
)

internal open class CartazFacilException(message: String) : Exception(message)
internal open class CartazLoginException(message: String) : CartazFacilException(message)
/** Usuário/senha recusados pelo site (só nesse caso a senha salva deve ser apagada). */
internal class CartazBadCredentialsException(message: String) : CartazLoginException(message)
internal class ProductNotFoundException : CartazFacilException("Produto não encontrado.")
internal class AmbiguousProductException : CartazFacilException("O código encontrou mais de um produto. Confira no sistema.")
internal class PriceNotFoundException : CartazFacilException("O produto foi encontrado, mas não há valor disponível.")

internal class CartazFacilClient(
    private val userAgent: String,
    private val baseUrl: String = BASE_URL
) {
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var authenticated = false

    @Throws(CartazFacilException::class)
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            throw CartazLoginException("Informe o usuário e a senha.")
        }

        val loginPage = request("/index.php", "GET", referer = null, ajax = false)
        if (loginPage.statusCode !in 200..299) {
            throw CartazLoginException("Não foi possível conectar ao sistema.")
        }

        val form = Jsoup.parse(loginPage.body, baseUrl).selectFirst("form")
            ?: throw CartazLoginException("O sistema não respondeu como esperado. Tente novamente.")
        val fields = linkedMapOf<String, String>()
        form.select("input[type=hidden][name]").forEach { input ->
            fields[input.attr("name")] = input.attr("value")
        }
        fields["usuario"] = username
        fields["senha"] = password
        fields["tipo_login"] = "cf"

        val action = form.absUrl("action").ifBlank { "$baseUrl/index.php" }
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val response = request(action, "POST", body, loginPage.finalUrl, ajax = false)
        val responseText = Jsoup.parse(response.body).text()
        val path = URL(response.finalUrl).path.lowercase(Locale.ROOT)
        if (!(path.endsWith("/home.php") || path.endsWith("/unitario.php")) ||
            responseText.contains("Os dados não conferem", ignoreCase = true)
        ) {
            authenticated = false
            throw CartazBadCredentialsException("Acesso recusado. Confira o usuário e a senha.")
        }

        val unitarioPage = request("/unitario.php", "GET", referer = response.finalUrl, ajax = false)
        val unitarioPath = URL(unitarioPage.finalUrl).path.lowercase(Locale.ROOT)
        authenticated = unitarioPage.statusCode in 200..299 &&
            unitarioPath.endsWith("/unitario.php")
        if (!authenticated) {
            throw CartazLoginException("Não foi possível abrir a consulta de preços.")
        }
    }

    @Throws(CartazFacilException::class)
    /** Busca produtos por código de barras ou nome (para a pesquisa por nome do app). */
    fun searchProducts(term: String): List<CartazProduct> {
        if (!authenticated) throw CartazLoginException("Faça o login antes de consultar preços.")
        val clean = term.trim()
        if (clean.isEmpty()) return emptyList()
        val searchPath = "/inc/buscaProduto.php?buscaProd=${encode(clean)}&sessao="
        val search = request(searchPath, "POST", ByteArray(0), "$baseUrl/unitario.php", ajax = true)
        requireAuthenticatedResponse(search)
        return CartazFacilHtmlParser.parseProducts(search.body)
    }

    fun lookupPrice(barcode: String): PriceLookupResult {
        if (!authenticated) throw CartazLoginException("Faça o login antes de consultar preços.")
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isEmpty()) throw CartazFacilException("O leitor não retornou um código de barras.")

        // A opção Todas Sessões usa uma sessão vazia na requisição que retorna os produtos.
        val searchPath = "/inc/buscaProduto.php?buscaProd=${encode(cleanBarcode)}&sessao="
        val search = request(searchPath, "POST", ByteArray(0), "$baseUrl/unitario.php", ajax = true)
        requireAuthenticatedResponse(search)
        val products = CartazFacilHtmlParser.parseProducts(search.body)
        if (products.isEmpty()) throw ProductNotFoundException()
        // Alguns servidores retornam variações/duplicatas do mesmo produto.
        // Escolhe automaticamente: código exato primeiro, senão o primeiro da lista.
        val product = products.firstOrNull { it.code == cleanBarcode } ?: products.first()
        val valuesPath = "/inc/filtro_select.php?ref=valorProd&id_produto=${encode(product.id)}"
        val values = request(valuesPath, "GET", referer = "$baseUrl/unitario.php", ajax = true)
        requireAuthenticatedResponse(values)
        val prices = CartazFacilHtmlParser.parsePrices(values.body)
        val price = prices.firstOrNull() ?: throw PriceNotFoundException()
        return PriceLookupResult(cleanBarcode, product, price)
    }

    private fun requireAuthenticatedResponse(response: HttpResponse) {
        val path = URL(response.finalUrl).path.lowercase(Locale.ROOT)
        val containsLoginForm = Jsoup.parse(response.body)
            .selectFirst("form input#usuario, form input#senha[type=password]") != null
        if (path.endsWith("/index.php") || containsLoginForm) {
            authenticated = false
            throw CartazLoginException("A sessão expirou. Entre novamente.")
        }
        if (response.statusCode !in 200..299) {
            throw CartazFacilException("O sistema respondeu com erro ${response.statusCode}.")
        }
    }

    private fun request(
        pathOrUrl: String,
        method: String,
        body: ByteArray? = null,
        referer: String?,
        ajax: Boolean
    ): HttpResponse {
        var currentUrl = if (pathOrUrl.startsWith("http")) pathOrUrl else baseUrl.trimEnd('/') + pathOrUrl
        var currentMethod = method.uppercase(Locale.ROOT)
        var currentBody = body

        repeat(MAX_REDIRECTS + 1) {
            val url = URL(currentUrl)
            val requestBody = currentBody
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = currentMethod
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                setRequestProperty("Accept", if (ajax) "text/html, */*; q=0.01" else "text/html,application/xhtml+xml")
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                if (ajax) setRequestProperty("X-Requested-With", "XMLHttpRequest")

                cookieManager.get(url.toURI(), emptyMap()).forEach { (name, values) ->
                    if (name.equals("Cookie", ignoreCase = true) && values.isNotEmpty()) {
                        setRequestProperty("Cookie", values.joinToString("; "))
                    }
                }

                if (requestBody != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(requestBody.size)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
            }

            if (requestBody != null) {
                connection.outputStream.use { it.write(requestBody) }
            }

            val status = connection.responseCode
            cookieManager.put(url.toURI(), connection.headerFields)
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("O sistema retornou uma resposta inválida.")
                val nextUrl = URL(url, location)
                val allowedHost = URL(baseUrl).host
                if (!nextUrl.protocol.equals("https", ignoreCase = true) ||
                    !nextUrl.host.equals(allowedHost, ignoreCase = true)
                ) {
                    connection.disconnect()
                    throw IOException("Resposta inesperada do sistema.")
                }
                if (status == HttpURLConnection.HTTP_SEE_OTHER ||
                    ((status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP) && currentMethod == "POST")
                ) {
                    currentMethod = "GET"
                    currentBody = null
                }
                currentUrl = nextUrl.toString()
                connection.disconnect()
                return@repeat
            }

            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val finalUrl = connection.url.toString()
            connection.disconnect()
            return HttpResponse(status, finalUrl, responseBody)
        }
        throw IOException("O sistema demorou demais para responder.")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class HttpResponse(val statusCode: Int, val finalUrl: String, val body: String)

    companion object {
        private const val BASE_URL = "https://rmarket.cartazfacil.pro"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_REDIRECTS = 5
    }
}
