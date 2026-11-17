package com.ericdu.seatwatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date

private val UclaBlue = Color(0xFF2774AE)
private val UclaGold = Color(0xFFFFD100)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = UclaBlue, secondary = UclaGold)) {
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var connected by remember { mutableStateOf(prefs.token.isNotEmpty() && prefs.topic.isNotEmpty()) }
    if (!connected) SetupScreen(prefs) { connected = true }
    else MainScreen(prefs) { prefs.token = ""; AlertService.stop(ctx); connected = false }
}

private fun openUrl(ctx: Context, url: String) =
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

// ================================================================== setup
@Composable
private fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(prefs.token) }
    var repo by remember { mutableStateOf(prefs.repoFull) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Seat Watch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = UclaBlue)
        Text("One-time setup. The app needs a GitHub token so it can change which classes the 24/7 checker watches.")
        Text("1. Tap below, then under Repository access choose “Only select repositories” → ucla-seat-watch. " +
            "The permissions are already filled in. Scroll down, tap Generate token, and copy it.")
        Button(onClick = {
            val owner = repo.substringBefore('/')
            openUrl(
                ctx, "https://github.com/settings/personal-access-tokens/new?name=Seat+Watch+app" +
                    "&description=Lets+the+Seat+Watch+phone+app+manage+ucla-seat-watch&target_name=$owner" +
                    "&expires_in=366&contents=write&secrets=write&actions=write"
            )
        }) { Text("Create GitHub token") }
        Text("2. Paste it here:")
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text("GitHub token (github_pat_…)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(repo, { repo = it.trim() }, label = { Text("Repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && token.length > 20 && repo.contains('/'),
            onClick = {
                busy = true; err = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val gh = GitHub(token, repo)
                            val branch = gh.check()
                            val topic = prefs.topic.ifEmpty { Prefs.newTopic() }
                            gh.setSecret("NTFY_TOPIC", topic)   // also proves Secrets permission
                            prefs.token = token; prefs.repoFull = repo; prefs.branch = branch; prefs.topic = topic
                        }
                        onDone()
                    } catch (e: Exception) {
                        err = e.message ?: e.toString()
                    } finally { busy = false }
                }
            },
        ) { Text(if (busy) "Connecting…" else "Connect") }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// ================================================================== main
@SuppressLint("BatteryLife")
@Composable
private fun MainScreen(prefs: Prefs, onDisconnect: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val gh = remember { GitHub(prefs.token, prefs.repoFull) }

    var config by remember { mutableStateOf<WatchConfig?>(null) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var instant by remember { mutableStateOf(prefs.instant) }
    var canPost by remember { mutableStateOf(Notifier.canPost(ctx)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var subjects by remember { mutableStateOf(loadSubjects(prefs)) }
    var terms by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var lastRun by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(prefs.history()) }
    val seatText = remember { mutableStateMapOf<String, String>() }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = it }

    fun msg(s: String) = scope.launch { snack.showSnackbar(s) }

    suspend fun reload() {
        try {
            val c = withContext(Dispatchers.IO) {
                gh.getFile("config.json", prefs.branch)?.let { (txt, sha) -> WatchConfig(JSONObject(txt), sha) }
                    ?: WatchConfig(JSONObject().put("term", "27W").put("notify_partial", true).put("watches", JSONArray()), null)
            }
            config = c; loadErr = null
        } catch (e: Exception) { loadErr = e.message }
        lastRun = withContext(Dispatchers.IO) {
            runCatching {
                gh.latestRun("watch.yml")?.let { (status, concl, _, created) ->
                    val ago = runCatching {
                        val m = (System.currentTimeMillis() - Instant.parse(created).toEpochMilli()) / 60000
                        if (m < 60) "${m} min ago" else "${m / 60} h ago"
                    }.getOrDefault(created)
                    "Last cloud check: ${if (status == "completed") concl else status}, $ago"
                } ?: "Cloud checker hasn't run yet"
            }.getOrNull()
        }
    }

    suspend fun save(newJson: JSONObject, what: String) {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                gh.putFile("config.json", newJson.toString(2) + "\n", config?.sha, "app: $what", prefs.branch)
            }
            reload(); msg("Saved: $what")
        } catch (e: Exception) {
            msg("Couldn't save: ${e.message}"); reload()
        } finally { busy = false }
    }

    LaunchedEffect(Unit) {
        if (!canPost && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.instant) AlertService.start(ctx)
        reload()
        withContext(Dispatchers.IO) { PollWorker.pollOnce(ctx) }
        history = prefs.history()
        runCatching {
            val (s, t) = withContext(Dispatchers.IO) { Soc.subjectsAndTerms() }
            if (s.isNotEmpty()) { subjects = s; prefs.subjectsCache = JSONArray(s.map { JSONArray(listOf(it.first, it.second)) }).toString() }
            terms = t
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seat Watch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = UclaBlue)
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.height(24.dp).width(24.dp))
                }
                lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            // ---------------- alerts
            item {
                Section("Alerts on this phone") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Instant alerts")
                            Text(
                                "Keeps a quiet connection open. If off, the app still checks every ~15 min.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(instant, {
                            instant = it; prefs.instant = it
                            if (it) AlertService.start(ctx) else AlertService.stop(ctx)
                        })
                    }
                    if (!canPost) Button({
                        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openAppSettings(ctx)
                    }) { Text("Allow notifications (required)") }
                    if (!batteryOk) OutlinedButton({
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                        batteryOk = true
                    }) { Text("Don't let Android pause it (recommended)") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { gh.dispatch("watch.yml", prefs.branch, mapOf("mode" to "test")) }
                                    msg("Test sent to the cloud. Push + email should arrive in 1–2 min.")
                                } catch (e: Exception) { msg("Test failed: ${e.message}") }
                            }
                        }) { Text("Send test alert") }
                        OutlinedButton({
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { PollWorker.pollOnce(ctx) }
                                history = prefs.history(); reload()
                                msg(if (n > 0) "$n new alert(s)" else "No new alerts")
                            }
                        }) { Text("Refresh") }
                    }
                }
            }

            // ---------------- classes
            item {
                val c = config
                Section("Classes being watched") {
                    if (c == null) {
                        Text(loadErr ?: "Loading…", color = if (loadErr != null) MaterialTheme.colorScheme.error else Color.Unspecified)
                        if (loadErr != null) TextButton({ scope.launch { reload() } }) { Text("Retry") }
                    } else {
                        TermPicker(c.term, terms) { t -> scope.launch { save(c.with(term = t), "term $t") } }
                        if (c.watches.isEmpty()) Text("No classes yet.")
                        c.watches.forEachIndexed { i, w ->
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${w.subj} ${w.catlg} · " +
                                            if (w.accept == "open") "alerts on open seats" else "alerts on open seats or waitlist room",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(w.enabled, { on ->
                                    val ws = c.watches.toMutableList(); ws[i] = w.copy(enabled = on)
                                    scope.launch { save(c.with(watches = ws), "${if (on) "resume" else "pause"} ${w.name}") }
                                })
                            }
                            Row {
                                TextButton({
                                    seatText[w.name] = "Checking…"
                                    scope.launch {
                                        seatText[w.name] = try {
                                            val st = withContext(Dispatchers.IO) { Soc.course(c.term, w.subj, w.catlg) }
                                            Soc.summarize(st, w.accept != "open")
                                        } catch (e: Exception) { "Couldn't check: ${e.message}" }
                                    }
                                }) { Text("Seats now") }
                                TextButton({
                                    val ws = c.watches.toMutableList(); ws.removeAt(i)
                                    scope.launch { save(c.with(watches = ws), "remove ${w.name}") }
                                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            }
                            seatText[w.name]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider()
                        Button({ showAdd = true }, Modifier.padding(top = 4.dp)) { Text("+ Add class") }
                    }
                }
            }

            // ---------------- email
            item { EmailSection(prefs, gh, ::msg) }

            // ---------------- history
            item {
                Section("Recent alerts") {
                    if (history.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                    history.take(15).forEach { h ->
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.optLong("time"))),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(h.optString("title"), fontWeight = FontWeight.SemiBold)
                        Text(h.optString("body"), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            item {
                Column(Modifier.padding(bottom = 32.dp)) {
                    Text("Repo: ${prefs.repoFull}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Seat data comes from UCLA's public Schedule of Classes, which updates about hourly. " +
                            "When an alert comes in, enroll in MyUCLA right away.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton({ openUrl(ctx, "https://github.com/${prefs.repoFull}/actions") }) { Text("Open cloud checker on GitHub") }
                    TextButton(onDisconnect) { Text("Disconnect GitHub token") }
                }
            }
        }
    }

    if (showAdd && config != null) {
        AddClassDialog(subjects, config!!.term, onDismiss = { showAdd = false }) { w ->
            showAdd = false
            val c = config!!
            scope.launch { save(c.with(watches = c.watches + w), "watch ${w.name}") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F7FB))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun TermPicker(term: String, terms: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = terms.firstOrNull { it.first == term }?.second ?: term
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Term: $label", Modifier.weight(1f))
        if (terms.isNotEmpty()) TextButton({ open = true }) { Text("Change") }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton({ open = false }) { Text("Cancel") } },
        title = { Text("Term") },
        text = {
            Column {
                terms.forEach { (code, name) ->
                    Text(name, Modifier.fillMaxWidth().clickable { open = false; if (code != term) onPick(code) }.padding(10.dp))
                }
            }
        },
    )
}

@Composable
private fun EmailSection(prefs: Prefs, gh: GitHub, msg: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(prefs.email) }
    var sender by remember { mutableStateOf(prefs.smtpUser) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Section("Email alerts") {
        Text(
            "Email is sent from a Gmail account using a free Google “app password”. " +
                "Your UCLA Gmail may block app passwords; a personal Gmail works.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            to, { to = it.trim() }, label = { Text("Send alerts to") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            sender, { sender = it.trim() }, label = { Text("Sender Gmail address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            pass, { pass = it.replace(" ", "") },
            label = { Text(if (prefs.smtpUser.isNotEmpty()) "App password (saved; retype to change)" else "Gmail app password (16 letters)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (to.isEmpty()) gh.deleteSecret("EMAIL_TO") else gh.setSecret("EMAIL_TO", to)
                            if (sender.isEmpty()) { gh.deleteSecret("SMTP_USER"); gh.deleteSecret("SMTP_PASS") }
                            else {
                                gh.setSecret("SMTP_USER", sender)
                                if (pass.isNotEmpty()) gh.setSecret("SMTP_PASS", pass)
                            }
                        }
                        prefs.email = to; prefs.smtpUser = sender; pass = ""
                        msg("Email settings saved (stored encrypted on GitHub)")
                    } catch (e: Exception) { msg("Couldn't save: ${e.message}") } finally { busy = false }
                }
            }) { Text("Save email") }
            TextButton({ openUrl(ctx, "https://myaccount.google.com/apppasswords") }) { Text("Get app password") }
        }
    }
}

@Composable
private fun AddClassDialog(
    subjects: List<Pair<String, String>>, term: String,
    onDismiss: () -> Unit, onAdd: (Watch) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var subj by remember { mutableStateOf<Pair<String, String>?>(null) }
    var num by remember { mutableStateOf("") }
    var wait by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val matches = remember(query, subjects) {
        if (query.length < 2 || subj != null) emptyList()
        else subjects.filter { it.first.contains(query, true) || it.second.contains(query, true) }.take(6)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch a class") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    subj?.first ?: query,
                    { query = it; subj = null; result = null },
                    label = { Text("Subject (e.g. Life Sciences, STATS)") }, singleLine = true,
                )
                if (subjects.isEmpty() && subj == null && query.isNotBlank()) {
                    TextButton({ subj = query.uppercase() to query.uppercase() }) { Text("Use “${query.uppercase()}” as subject code") }
                }
                matches.forEach { s ->
                    Text(s.first, Modifier.fillMaxWidth().clickable { subj = s; query = s.first }.padding(8.dp))
                }
                OutlinedTextField(num, { num = it.trim().uppercase(); result = null }, label = { Text("Course number (e.g. 7B, 13, M51A)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wait, { wait = it })
                    Text("Also alert when the waitlist has room")
                }
                OutlinedButton(enabled = subj != null && num.isNotEmpty() && !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val st = withContext(Dispatchers.IO) { Soc.course(term, subj!!.second, num) }
                            title = st.title
                            result = "${st.title}\n" + Soc.summarize(st, wait)
                        } catch (e: Exception) { result = "⚠ ${e.message}"; title = null } finally { checking = false }
                    }
                }) { Text(if (checking) "Checking…" else "Check it exists") }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = subj != null && num.isNotEmpty() && title != null, onClick = {
                onAdd(Watch(name = "${subj!!.second} $num", subj = subj!!.second, catlg = num, accept = if (wait) "open_or_waitlist" else "open"))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun loadSubjects(prefs: Prefs): List<Pair<String, String>> = runCatching {
    val a = JSONArray(prefs.subjectsCache)
    (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
}.getOrDefault(emptyList())

private fun isIgnoringBattery(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

private fun openAppSettings(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
