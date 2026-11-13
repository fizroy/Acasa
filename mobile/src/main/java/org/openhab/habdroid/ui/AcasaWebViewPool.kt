/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Acasa: keeps the most recently used Webview widgets alive, keyed by URL, so that going back to a page
 * (e.g. the heat pump schematic) shows it instantly instead of loading it again.
 */
object AcasaWebViewPool {
    private const val MAX_POOLED = 4

    private val pooled = LinkedHashMap<String, WebView>(8, 0.75f, true)
    private val destroyed: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Returns a WebView for [url] and whether it already has that page loaded.
     */
    fun obtain(context: Context, url: String): Pair<WebView, Boolean> {
        val existing = pooled[url]
        if (existing != null && existing.parent == null) {
            return existing to true
        }
        val webView = WebView(context)
        if (existing == null) {
            pooled[url] = webView
            trim(keep = webView)
        }
        return webView to false
    }

    /**
     * Called when a holder no longer shows [webView]. Pooled views stay alive, others are destroyed.
     */
    fun release(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (!pooled.containsValue(webView)) {
            destroy(webView)
        } else {
            trim()
        }
    }

    fun isUsable(webView: WebView) = webView !in destroyed

    fun clear() {
        pooled.values.toList().forEach { destroy(it) }
        pooled.clear()
    }

    private fun trim(keep: WebView? = null) {
        if (pooled.size <= MAX_POOLED) {
            return
        }
        // Evict least recently used views that are not on screen
        val iterator = pooled.entries.iterator()
        while (pooled.size > MAX_POOLED && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.parent == null && entry.value !== keep) {
                iterator.remove()
                destroy(entry.value)
            }
        }
    }

    private fun destroy(webView: WebView) {
        if (webView in destroyed) {
            return
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        destroyed.add(webView)
        webView.stopLoading()
        webView.destroy()
    }
}
