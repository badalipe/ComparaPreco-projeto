package com.quickprice.app

import java.math.BigDecimal
import java.net.URI
import org.jsoup.Jsoup

internal data class CartazProduct(
    val id: String,
    val code: String,
    val description: String,
    val imageUrl: String? = null
)

internal data class CartazPrice(
    val amount: BigDecimal,
    val display: String,
    val effectiveDate: String?
)

internal object CartazFacilHtmlParser {
    private val pricePattern = Regex("(?i)Valores:\\s*(?:R\\$\\s*)?([0-9][0-9.]*,[0-9]{2})")
    private val datePattern = Regex("\\b([0-3]\\d/[01]\\d/20\\d{2})\\b")
    private val emptyFamilySuffix = Regex("\\s*\\|\\s*-\\s*Familia:\\s*$", RegexOption.IGNORE_CASE)

    fun parseProducts(html: String): List<CartazProduct> {
        val document = Jsoup.parse(html, "https://rmarket.cartazfacil.pro/")
        return document.select("tr.trValor").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 3) return@mapNotNull null

            val id = cells[0].text().trim().takeWhile { it.isDigit() }
            if (id.isEmpty()) return@mapNotNull null

            val codeCell = cells[1]
            val code = codeCell.selectFirst("b.text-success")?.text()?.trim()
                ?: codeCell.text().split(Regex("\\s+")).lastOrNull { token -> token.all { it.isDigit() } }.orEmpty()
            val rawDescription = cells[2].selectFirst("small")?.text()?.trim()
                ?: cells[2].text().trim()
            val description = rawDescription.replace(emptyFamilySuffix, "").trim()
            val imageUrl = row.select("img[src]")
                .mapNotNull { it.attr("abs:src").takeIf(::isTrustedImageUrl) }
                .firstOrNull()

            if (description.isEmpty()) return@mapNotNull null
            CartazProduct(id = id, code = code, description = description, imageUrl = imageUrl)
        }
    }

    private fun isTrustedImageUrl(candidate: String): Boolean = runCatching {
        val uri = URI(candidate)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("simplus-img.s3.us-east-2.amazonaws.com", ignoreCase = true)
    }.getOrDefault(false)

    fun parsePrices(html: String): List<CartazPrice> {
        val document = Jsoup.parse(html)
        return document.select("option").mapNotNull { option ->
            val text = option.text().trim()
            val match = pricePattern.find(text) ?: return@mapNotNull null
            val display = match.groupValues[1]
            val amount = display.replace(".", "").replace(",", ".").toBigDecimalOrNull()
                ?: return@mapNotNull null
            val effectiveDate = datePattern.find(text)?.groupValues?.get(1)
            CartazPrice(amount = amount, display = display, effectiveDate = effectiveDate)
        }
    }
}
