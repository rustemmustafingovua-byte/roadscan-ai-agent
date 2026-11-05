package ai.roadscan.core.estimate

import kotlin.test.Test
import kotlin.test.assertEquals

class PriceCatalogOverridesTest {

    private val base = PriceCatalog(
        region = "UA-DEFAULT",
        currency = "UAH",
        vatRate = 0.20,
        items = listOf(
            PriceItem("MILLING", "Фрезерування", "м³", 1_150.0),
            PriceItem("ASPHALT", "Асфальтобетон", "т", 6_400.0),
        ),
    )

    @Test
    fun `правка меняет только указанные коды`() {
        val result = base.withOverrides(mapOf("ASPHALT" to 7_000.0))
        assertEquals(7_000.0, result.priceOf("ASPHALT"))
        assertEquals(1_150.0, result.priceOf("MILLING"))
        // Структура и реквизиты каталога не тронуты.
        assertEquals(base.currency, result.currency)
        assertEquals(base.vatRate, result.vatRate)
        assertEquals(base.items.size, result.items.size)
    }

    @Test
    fun `неизвестный код и мусор игнорируются`() {
        val result = base.withOverrides(
            mapOf(
                "NOPE" to 100.0,
                "MILLING" to Double.NaN,
                "ASPHALT" to -5.0,
            ),
        )
        assertEquals(base, result)
    }

    @Test
    fun `нулевая цена — валидная правка (бесплатная работа)`() {
        val result = base.withOverrides(mapOf("MILLING" to 0.0))
        assertEquals(0.0, result.priceOf("MILLING"))
    }
}
