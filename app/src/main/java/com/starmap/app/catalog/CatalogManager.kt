package com.starmap.app.catalog

import android.content.Context
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.ConstellationCatalog
import com.starmap.app.astro.StarCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the on-device catalogs. Both the naked-eye set (`stars.json`, ~8,900 stars
 * to magnitude 6.5) and the extended set (`stars_ext.json`, ~41,000 stars to
 * magnitude 8) ship inside the APK, plus the constellation figures — so the whole
 * sky works offline with no downloads. A setting chooses which star set to load.
 */
class CatalogManager(private val context: Context) {

    suspend fun loadStars(useExtended: Boolean): StarCatalog = withContext(Dispatchers.IO) {
        val asset = if (useExtended) "stars_ext.json" else "stars.json"
        context.assets.open(asset).use { StarCatalog.parse(it) }
    }

    suspend fun loadConstellations(): List<Constellation> = withContext(Dispatchers.IO) {
        context.assets.open("constellations.json").use { ConstellationCatalog.parse(it) }
    }
}
