package app.rebubble.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryReceiptLabelTest {

    @Test
    fun `read wins over delivered`() {
        assertEquals("Read", deliveryReceiptLabel(show = true, dateDelivered = 1L, dateRead = 2L))
    }

    @Test
    fun `delivered when read is null`() {
        assertEquals("Delivered", deliveryReceiptLabel(show = true, dateDelivered = 1L, dateRead = null))
    }

    @Test
    fun `null when neither set or not shown`() {
        assertNull(deliveryReceiptLabel(show = true, dateDelivered = null, dateRead = null))
        assertNull(deliveryReceiptLabel(show = false, dateDelivered = 1L, dateRead = 2L))
    }
}
