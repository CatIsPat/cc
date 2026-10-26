package com.example.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchSnippet(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

class WebSearchEngine {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun searchLiveWeb(query: String): List<WebSearchSnippet> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        coroutineScope {
            val wikiDeferred = async { fetchWikipediaResults(cleanQuery) }
            val ddgDeferred = async { fetchDuckDuckGoInstantResults(cleanQuery) }
            val hnDeferred = async { fetchHackerNewsResults(cleanQuery) }

            val combined = mutableListOf<WebSearchSnippet>()
            combined.addAll(ddgDeferred.await())
            combined.addAll(wikiDeferred.await())
            combined.addAll(hnDeferred.await())
            combined.distinctBy { it.url }.take(7)
        }
    }

    private fun fetchWikipediaResults(query: String): List<WebSearchSnippet> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=4"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AetherAgent/1.0 (Android; Personal Cognitive Agent)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val searchArr = root.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
                val list = mutableListOf<WebSearchSnippet>()
                for (i in 0 until searchArr.length()) {
                    val obj = searchArr.getJSONObject(i)
                    val title = obj.optString("title")
                    val rawSnippet = obj.optString("snippet")
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .trim()
                    val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"
                    if (title.isNotBlank() && rawSnippet.isNotBlank()) {
                        list.add(
                            WebSearchSnippet(
                                title = title,
                                snippet = rawSnippet,
                                url = pageUrl,
                                source = "Wikipedia Live"
                            )
                        )
                    }
                }
                list
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchDuckDuckGoInstantResults(query: String): List<WebSearchSnippet> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AetherAgent/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val results = mutableListOf<WebSearchSnippet>()
                val abstractText = root.optString("AbstractText")
                val abstractUrl = root.optString("AbstractURL")
                val heading = root.optString("Heading").ifBlank { query }
                if (abstractText.isNotBlank()) {
                    results.add(
                        WebSearchSnippet(
                            title = heading,
                            snippet = abstractText,
                            url = abstractUrl.ifBlank { "https://duckduckgo.com/?q=$encoded" },
                            source = "DuckDuckGo Instant"
                        )
                    )
                }
                val related = root.optJSONArray("RelatedTopics") ?: JSONArray()
                for (i in 0 until related.length().coerceAtMost(3)) {
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text")
                    val firstUrl = item.optString("FirstURL")
                    if (text.isNotBlank() && firstUrl.isNotBlank()) {
                        results.add(
                            WebSearchSnippet(
                                title = text.take(55) + if (text.length > 55) "…" else "",
                                snippet = text,
                                url = firstUrl,
                                source = "DuckDuckGo"
                            )
                        )
                    }
                }
                results
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchHackerNewsResults(query: String): List<WebSearchSnippet> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://hn.algolia.com/api/v1/search?query=$encoded&tags=story&hitsPerPage=3"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AetherAgent/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val hits = root.optJSONArray("hits") ?: JSONArray()
                val results = mutableListOf<WebSearchSnippet>()
                for (i in 0 until hits.length()) {
                    val hit = hits.getJSONObject(i)
                    val title = hit.optString("title")
                    val storyUrl = hit.optString("url")
                        .ifBlank { "https://news.ycombinator.com/item?id=${hit.optString("objectID")}" }
                    val points = hit.optInt("points", 0)
                    val author = hit.optString("author", "hn")
                    if (title.isNotBlank()) {
                        results.add(
                            WebSearchSnippet(
                                title = title,
                                snippet = "Community signal ($points pts by @$author) — real-time tech/web index.",
                                url = storyUrl,
                                source = "HN Algolia Live"
                            )
                        )
                    }
                }
                results
            }
        }.getOrDefault(emptyList())
    }
}
