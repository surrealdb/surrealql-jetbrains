package com.surrealdb.surql

import com.intellij.openapi.util.IconLoader
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

object SurQLIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/surql.svg", SurQLIcons::class.java)

    /**
     * Surrealist brand mark, loaded eagerly from /icons/surrealist.png via
     * ImageIO (IconLoader's extension-based dispatch only handles .svg/.png/.gif,
     * so it can't reliably load .webp on its own). The source is the full
     * wordmark — we pre-scale it down to fit the editor's notification button so
     * the JButton it lives in keeps a stable size regardless of HiDPI scaling.
     */
    @JvmField
    val SURREALIST: Icon = loadScaledRaster("/icons/surrealist.png", maxEdge = 22) ?: FILE

    private fun loadScaledRaster(resourcePath: String, maxEdge: Int): Icon? {
        val url = SurQLIcons::class.java.getResource(resourcePath) ?: return null
        val source = runCatching { ImageIO.read(url) }.getOrNull() ?: return null
        val scale = minOf(
            maxEdge.toDouble() / source.width,
            maxEdge.toDouble() / source.height,
        )
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return ImageIcon(source.getScaledInstance(w, h, Image.SCALE_SMOOTH))
    }
}
