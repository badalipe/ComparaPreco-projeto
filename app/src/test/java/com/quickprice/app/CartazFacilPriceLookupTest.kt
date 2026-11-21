package com.quickprice.app

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartazFacilPriceLookupTest {

    @Test
    fun `parser reconhece o EAN informado na linha do Cartaz Facil`() {
        val html = """
            <table>
              <tr class="trValor">
                <td>4207</td>
                <td><b class="text-success">7894900011517</b></td>
                <td><small>Refrigerante Coca-Cola 2 L</small></td>
              </tr>
            </table>
        """.trimIndent()

        val product = CartazFacilHtmlParser.parseProducts(html).single()

        assertEquals("4207", product.id)
        assertEquals("7894900011517", product.code)
        assertEquals("Refrigerante Coca-Cola 2 L", product.description)
    }

    @Test
    fun `correspondencia exacta vem primeiro e IDs repetidos sao eliminados`() {
        val exact = CartazProduct("42", "7894900011517", "Coca-Cola 2 L")
        val duplicateIdButWrongCode = CartazProduct("42", "789490001151", "Coca-Cola 2 L")
        val otherExact = CartazProduct("43", "7894900011517", "Coca-Cola 2 L - outra filial")
        val unrelated = CartazProduct("44", "789490001151", "Coca-Cola embalagem diferente")

        val ordered = orderPriceCandidates(
            listOf(duplicateIdButWrongCode, unrelated, exact, otherExact),
            "7894900011517"
        )

        assertEquals(listOf(exact, otherExact, unrelated), ordered)
    }

    @Test
    fun `parser extrai valor e data de uma opcao de preco`() {
        val html = """
            <select>
              <option>Selecione</option>
              <option>Valores: R$ 12,99 - 21/11/2026</option>
            </select>
        """.trimIndent()

        val prices = CartazFacilHtmlParser.parsePrices(html)

        assertEquals(1, prices.size)
        assertEquals(BigDecimal("12.99"), prices.single().amount)
        assertEquals("12,99", prices.single().display)
        assertEquals("21/11/2026", prices.single().effectiveDate)
        assertTrue(prices.single().amount > BigDecimal.ZERO)
    }
}
