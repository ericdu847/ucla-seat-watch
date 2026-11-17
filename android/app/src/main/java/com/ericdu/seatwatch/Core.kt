package com.ericdu.seatwatch

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

// ------------------------------------------------------------------ prefs
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("seatwatch", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var repoFull: String
        get() = p.getString("repo", DEFAULT_REPO) ?: DEFAULT_REPO
        set(v) = p.edit().putString("repo", v.trim()).apply()
    var branch: String
        get() = p.getString("branch", "main") ?: "main"
        set(v) = p.edit().putString("branch", v).apply()
    var topic: String
        get() = p.getString("topic", "") ?: ""
        set(v) = p.edit().putString("topic", v).apply()
    var email: String
        get() = p.getString("email", "") ?: ""
        set(v) = p.edit().putString("email", v.trim()).apply()
    var smtpUser: String
        get() = p.getString("smtp_user", "") ?: ""
        set(v) = p.edit().putString("smtp_user", v.trim()).apply()
    var instant: Boolean
        get() = p.getBoolean("instant", true)
        set(v) = p.edit().putBoolean("instant", v).apply()
    var lastId: String
        get() = p.getString("last_id", "") ?: ""
        set(v) = p.edit().putString("last_id", v).apply()
    var subjectsCache: String
        get() = p.getString("subjects", "") ?: ""
        set(v) = p.edit().putString("subjects", v).apply()

    /** Remember a message id; returns false if we've already shown it. */
    @Synchronized
    fun markSeen(id: String): Boolean {
        val seen = (p.getString("seen", "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableList()
        if (id in seen) return false
        seen.add(id)
        p.edit().putString("seen", seen.takeLast(200).joinToString(",")).putString("last_id", id).apply()
        return true
    }

    fun history(): List<JSONObject> {
        val a = JSONArray(p.getString("history", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Synchronized
    fun addHistory(title: String, body: String, time: Long) {
        val a = JSONArray(p.getString("history", "[]"))
        val n = JSONArray().put(JSONObject().put("title", title).put("body", body).put("time", time))
        for (i in 0 until minOf(a.length(), 29)) n.put(a.get(i))
        p.edit().putString("history", n.toString()).apply()
    }

    companion object {
        const val DEFAULT_REPO = "ericdu847/ucla-seat-watch"
        fun newTopic(): String {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return "uclaseat-" + (1..22).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}

// ------------------------------------------------------------------ http
class HttpError(val code: Int, msg: String) : IOException(msg)

object Http {
    fun request(
        url: String, method: String = "GET", body: String? = null,
        headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "SeatWatch-Android/1.0")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ------------------------------------------------------------------ github
class GitHub(private val token: String, repoFull: String) {
    private val base = "https://api.github.com/repos/$repoFull"

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val (code, text) = Http.request(
            base + path, method, body?.toString(),
            mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
        )
        if (code >= 400) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            throw HttpError(
                code, when (code) {
                    401 -> "GitHub rejected the token (expired or mistyped)."
                    403 -> "Token is missing a permission ($msg). It needs Contents, Secrets and Actions: read & write."
                    404 -> "Repo not found, or the token wasn't given access to it."
                    else -> "GitHub error $code: $msg"
                }
            )
        }
        return if (text.isBlank()) null else JSONObject(text)
    }

    /** Returns default branch. Throws a readable error if token/repo is wrong. */
    fun check(): String = call("GET", "")!!.getString("default_branch")

    fun getFile(path: String, ref: String): Pair<String, String>? = try {
        val j = call("GET", "/contents/$path?ref=$ref")!!
        val content = String(Base64.decode(j.getString("content").replace("\n", ""), Base64.DEFAULT))
        content to j.getString("sha")
    } catch (e: HttpError) {
        if (e.code == 404) null else throw e   // repo access is verified separately by check()
    }

    fun putFile(path: String, content: String, sha: String?, message: String, branch: String) {
        val b = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) b.put("sha", sha)
        call("PUT", "/contents/$path", b)
    }

    fun setSecret(name: String, value: String) {
        val pk = call("GET", "/actions/secrets/public-key")!!
        val sealed = SealedBox.seal(value.toByteArray(), Base64.decode(pk.getString("key"), Base64.DEFAULT))
        call(
            "PUT", "/actions/secrets/$name",
            JSONObject()
                .put("encrypted_value", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .put("key_id", pk.getString("key_id")),
        )
    }

    fun deleteSecret(name: String) {
        try { call("DELETE", "/actions/secrets/$name") } catch (e: HttpError) { if (e.code != 404) throw e }
    }

    fun dispatch(workflow: String, branch: String, inputs: Map<String, String>) {
        val i = JSONObject(); inputs.forEach { (k, v) -> i.put(k, v) }
        call("POST", "/actions/workflows/$workflow/dispatches", JSONObject().put("ref", branch).put("inputs", i))
    }

    /** (status, conclusion, html_url, created_at) of the newest watcher run. */
    fun latestRun(workflow: String): List<String>? {
        val runs = call("GET", "/actions/workflows/$workflow/runs?per_page=1")!!.getJSONArray("workflow_runs")
        if (runs.length() == 0) return null
        val r = runs.getJSONObject(0)
        return listOf(r.optString("status"), r.optString("conclusion"), r.optString("html_url"), r.optString("created_at"))
    }
}

// ------------------------------------------------------------------ config.json model
data class Watch(
    val name: String,
    val subj: String,
    val catlg: String,
    val accept: String = "open",          // "open" | "open_or_waitlist"
    val enabled: Boolean = true,
    val onlyPrimary: List<String>? = null,
    val excludeSecondary: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("subj", subj).put("catlg", catlg)
        .put("accept", accept).put("enabled", enabled)
        .put("only_primary", onlyPrimary?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("exclude_secondary", JSONArray(excludeSecondary))

    companion object {
        fun from(j: JSONObject) = Watch(
            name = j.optString("name"),
            subj = j.optString("subj"),
            catlg = j.optString("catlg"),
            accept = j.optString("accept", "open"),
            enabled = j.optBoolean("enabled", true),
            onlyPrimary = j.optJSONArray("only_primary")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            excludeSecondary = j.optJSONArray("exclude_secondary")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

class WatchConfig(val raw: JSONObject, val sha: String?) {
    val term: String get() = raw.optString("term", "27W")
    val watches: List<Watch>
        get() = raw.optJSONArray("watches")?.let { a -> (0 until a.length()).map { Watch.from(a.getJSONObject(it)) } } ?: emptyList()

    fun with(term: String = this.term, watches: List<Watch> = this.watches): JSONObject {
        val j = JSONObject(raw.toString())
        j.put("term", term)
        j.put("watches", JSONArray().apply { watches.forEach { put(it.toJson()) } })
        return j
    }
}

// ------------------------------------------------------------------ UCLA public Schedule of Classes
data class Section(val id: String, val label: String, val status: String, val waitlist: String) {
    /** "open", "waitlist" or "none" */
    val avail: String
        get() {
            if (status.startsWith("Open")) return "open"
            val m = Regex("(\\d+) of (\\d+) Taken").find(waitlist)
            if (m != null && m.groupValues[1].toInt() < m.groupValues[2].toInt() &&
                !status.startsWith("Cancelled") && !status.startsWith("Closed by Dept")) return "waitlist"
            return "none"
        }
}

data class Primary(val sec: Section, val children: List<Section>)
data class CourseStatus(val title: String, val asOf: String, val primaries: List<Primary>)

object Soc {
    private const val BASE = "https://sa.ucla.edu/ro/public/soc"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    fun normCatlg(input: String): String {
        val c = input.trim().uppercase()
        if (Regex("\\d{4}.*").matches(c)) return c
        val m = Regex("([A-Z]*)0*(\\d+)([A-Z]*)").matchEntire(c.replace(" ", "")) ?: return c
        val (pre, num, suf) = m.destructured
        val out = "%04d%s".format(num.toInt(), suf)
        return if (pre.isNotEmpty()) out.padEnd(6) + pre else out
    }

    private fun get(url: String, ajax: Boolean = false): String {
        val h = mutableMapOf("User-Agent" to UA, "Accept" to "text/html,*/*")
        if (ajax) h["X-Requested-With"] = "XMLHttpRequest"
        val (code, text) = Http.request(url, headers = h, timeoutMs = 45000)
        if (code >= 400) throw HttpError(code, "UCLA site returned $code")
        return text
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun clean(frag: String) = unescape(
        frag.replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    /** Subject list [(label, code)] and term list [(code, label)] from the public search page. */
    fun subjectsAndTerms(): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val t = unescape(get(BASE))
        val subs = Regex("\\{\"label\":\"([^\"]+)\",\"value\":\"([^\"]+)\"\\}").findAll(t)
            .map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()
        val terms = Regex("<option[^>]*value=\"(\\d\\d[A-Z0-9])\"[^>]*>([^<]+)<").findAll(t)
            .map { it.groupValues[1] to it.groupValues[2].trim() }.distinct().toList()
        return subs to terms
    }

    private fun rows(page: String): List<Pair<Section, String>> {
        val out = mutableListOf<Pair<Section, String>>()
        Regex("for=\"([^\"]+)-checkbox\" class=\"screenReaderOnly\">Select ([^<]*)</label>").findAll(page).forEach { m ->
            val id = m.groupValues[1]
            val full = unescape(m.groupValues[2]).trim()
            val lab = Regex("\\b([A-Z][a-z]{1,3}) (\\w+)$").find(full)
            val label = lab?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: full
            val st = Regex("id=\"${Regex.escape(id)}-status_data\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(page)
            val wl = Regex("id=\"${Regex.escape(id)}-waitlist_data\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(page)
            out += Section(id, label, st?.let { clean(it.groupValues[1]) } ?: "?", wl?.let { clean(it.groupValues[1]) } ?: "?") to full
        }
        return out
    }

    fun course(term: String, subj: String, catlg: String, withChildren: Boolean = true): CourseStatus {
        val url = "$BASE/Results?t=${Http.enc(term)}&sBy=subject&subj=${Http.enc(subj)}" +
            "&catlg=${Http.enc(normCatlg(catlg))}&cls_no=%25&btnIsInIndex=btn_inIndex"
        val page = get(url)
        val asOf = Regex("Status as of ([0-9:]+\\s*[AP]M)").find(page)?.groupValues?.get(1) ?: "?"
        val models = Regex("AddToCourseData\\(\"([^\"]+)\",(\\{.*?\\})\\)").findAll(page)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val prim = rows(page)
        if (prim.isEmpty()) throw IOException("No sections found for $subj $catlg in $term. Check the subject and course number.")
        val title = prim.first().second.let { full ->
            val after = full.substringAfter(") ", full)
            after.removeSuffix(prim.first().first.label).trim()
        }
        val filter = """{"enrollment_status":"O,W,C,X,T,S","advanced":"n"}"""
        val primaries = prim.map { (sec, _) ->
            val kids = if (withChildren && models[sec.id] != null) {
                val frag = get("$BASE/Results/GetCourseSummary?model=${Http.enc(models[sec.id]!!)}&FilterFlags=${Http.enc(filter)}", ajax = true)
                rows(frag).map { it.first }.filter { it.id != sec.id }
            } else emptyList()
            Primary(sec, kids)
        }
        return CourseStatus(title, asOf, primaries)
    }

    /** One-line human summary. */
    fun summarize(c: CourseStatus, acceptWaitlist: Boolean): String {
        val ok = if (acceptWaitlist) setOf("open", "waitlist") else setOf("open")
        val parts = c.primaries.map { p ->
            val kidsOpen = p.children.count { it.avail in ok }
            val lec = when (p.sec.avail) {
                "open" -> "open"
                "waitlist" -> "waitlist room"
                else -> "full"
            }
            if (p.children.isEmpty()) "${p.sec.label}: $lec"
            else "${p.sec.label}: $lec, $kidsOpen/${p.children.size} " + when (p.children.first().label.substringBefore(' ')) {
                "Lab" -> "labs"; "Dis" -> "discussions"; else -> "sections"
            } + " open"
        }
        val enrollable = c.primaries.sumOf { p ->
            if (p.children.isEmpty()) (if (p.sec.avail in ok) 1 else 0).toInt()
            else if (p.sec.avail in ok) p.children.count { it.avail in ok } else 0
        }
        val head = if (enrollable > 0) "✅ $enrollable enrollable option(s)" else "No enrollable seats"
        return "$head (as of ${c.asOf})\n" + parts.joinToString("\n")
    }
}
