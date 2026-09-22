package com.micon.app.ui.holo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoloCatalogPagingTest {
    @Test fun `server total pages determine whether catalog continues`() {
        assertTrue(hasNextCatalogPage(page = 0, totalPages = 3, itemCount = 40, pageSize = 40))
        assertFalse(hasNextCatalogPage(page = 2, totalPages = 3, itemCount = 12, pageSize = 40))
    }

    @Test fun `full page fallback continues when total pages are absent`() {
        assertTrue(hasNextCatalogPage(page = 0, totalPages = 0, itemCount = 40, pageSize = 40))
        assertFalse(hasNextCatalogPage(page = 1, totalPages = 0, itemCount = 7, pageSize = 40))
    }
}
