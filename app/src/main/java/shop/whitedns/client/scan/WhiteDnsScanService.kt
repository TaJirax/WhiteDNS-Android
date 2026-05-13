package shop.whitedns.client.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import shop.whitedns.client.MainActivity
import shop.whitedns.client.R
import shop.whitedns.client.model.StormDnsServerProfile
import shop.whitedns.client.model.WhiteDnsScanState
import shop.whitedns.client.model.WhiteDnsScanStatus
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.runtime.RuntimeLaunchRequestStore
import shop.whitedns.client.runtime.parseStormDnsConnectionProgressLine
import shop.whitedns.client.storm.StormDnsBinaryInstaller
import shop.whitedns.client.storm.StormDnsConfigRenderer

class WhiteDnsScanService : Service() {

    private var foregroundStarted = false
    private var scanJob: Job? = null
    private var currentSessionId = ""
    @Volatile
    private var stopRequested = false
    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<Process>())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binaryInstaller by lazy {
        StormDnsBinaryInstaller(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ActionStop -> {
                stopRequested = true
                scanJob?.cancel()
                stopScanRuntime("Scan stopped")
                exitForeground()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                enterForeground("Preparing scan")
                startScan(intent)
                START_REDELIVER_INTENT
            }
        }
    }

    override fun onDestroy() {
        stopRequested = true
        scanJob?.cancel()
        stopScanRuntime("Scan service stopped")
        exitForeground()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startScan(intent: Intent?) {
        val previousJob = scanJob
        val sessionId = intent?.getStringExtra(ExtraSessionId).orEmpty()
        stopRequested = false
        scanJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            currentSessionId = sessionId
            try {
                val runtimeRequest = RuntimeLaunchRequestStore.load(applicationContext, sessionId)
                    ?: throw IllegalStateException("Runtime launch request is missing")
                val scanRequest = WhiteDnsScanRequestStore.load(applicationContext, sessionId)
                    ?: throw IllegalStateException("Scan launch request is missing")
                val resolvers = WhiteDnsScannerResultStore.normalizeScanResolverText(
                    File(scanRequest.resolverFilePath).readText(Charsets.UTF_8),
                ).normalizedResolvers
                if (resolvers.isEmpty()) {
                    throw IllegalStateException("Resolver file is empty")
                }
                runScan(
                    sessionId = sessionId,
                    sourceName = scanRequest.sourceName,
                    serverProfile = runtimeRequest.serverProfile,
                    settings = runtimeRequest.settings,
                    resolvers = resolvers,
                    requestedWorkerCount = scanRequest.workerCount,
                    initialValidResolvers = scanRequest.initialValidResolvers,
                    initialRejectedResolvers = scanRequest.initialRejectedResolvers,
                    initialCompletedResolvers = scanRequest.initialCompletedResolvers,
                    requestedTotalResolvers = scanRequest.totalResolvers,
                )
            } catch (error: CancellationException) {
                stopScanRuntime("Scan stopped")
                throw error
            } catch (error: Exception) {
                val message = "Scan failed: ${error.message ?: error::class.java.simpleName}"
                Log.e(Tag, message, error)
                publishState(
                    WhiteDnsScanStateStore.read(applicationContext).copy(
                        sessionId = sessionId,
                        status = WhiteDnsScanStatus.Failed,
                        updatedAtMillis = System.currentTimeMillis(),
                        message = message,
                    ),
                )
                exitForeground()
                stopSelf()
            }
        }
    }

    private suspend fun runScan(
        sessionId: String,
        sourceName: String,
        serverProfile: StormDnsServerProfile,
        settings: WhiteDnsSettings,
        resolvers: List<String>,
        requestedWorkerCount: Int,
        initialValidResolvers: List<String>,
        initialRejectedResolvers: List<String>,
        initialCompletedResolvers: Int,
        requestedTotalResolvers: Int,
    ) = coroutineScope {
        val binaryFile = binaryInstaller.installExecutable()
        val chunks = chunkResolversRoundRobin(resolvers, requestedWorkerCount)
        val workerStats = Array(chunks.size) { WorkerScanStats(total = chunks[it].size) }
        val validResolvers = WhiteDnsScannerResultStore.normalizeResolverEntries(initialValidResolvers)
            .toCollection(linkedSetOf())
        val rejectedResolvers = WhiteDnsScannerResultStore.normalizeResolverEntries(initialRejectedResolvers)
            .filterNot(validResolvers::contains)
            .toCollection(linkedSetOf())
        val workerFailures = mutableListOf<String>()
        val stateLock = Any()
        val initialValidResolverCount = validResolvers.size
        val initialRejectedResolverCount = rejectedResolvers.size
        val initialEntryCount = (validResolvers + rejectedResolvers).distinct().size
        val initialProcessedCount = maxOf(initialCompletedResolvers, initialEntryCount)
        val totalResolverCount = maxOf(requestedTotalResolvers, resolvers.size + initialProcessedCount)
        val scanRoot = File(File(applicationContext.noBackupFilesDir, "stormdns/scan"), sessionId).apply {
            mkdirs()
        }
        val startedAtMillis = System.currentTimeMillis()
        var lastAggregatePublishMillis = 0L

        fun aggregateState(status: String, message: String): WhiteDnsScanState {
            val completed = (initialProcessedCount + workerStats.sumOf { it.completed })
                .coerceAtMost(totalResolverCount)
            val validEntries = validResolvers.toList()
            val rejectedEntries = rejectedResolvers.toList()
            val liveValidCount = maxOf(validEntries.size, initialValidResolverCount + workerStats.sumOf { it.valid })
            val liveRejectedCount = maxOf(
                rejectedEntries.size,
                initialRejectedResolverCount + workerStats.sumOf { it.rejected },
            )
            return WhiteDnsScanState(
                sessionId = sessionId,
                status = status,
                sourceName = sourceName,
                totalResolvers = totalResolverCount,
                completedResolvers = completed,
                validResolvers = liveValidCount.coerceAtMost(totalResolverCount),
                rejectedResolvers = liveRejectedCount.coerceAtMost(totalResolverCount),
                workerCount = chunks.size,
                startedAtMillis = startedAtMillis,
                updatedAtMillis = System.currentTimeMillis(),
                durationMillis = System.currentTimeMillis() - startedAtMillis,
                message = message,
                validResolverEntries = validEntries,
                rejectedResolverEntries = rejectedEntries,
                workerFailures = workerFailures.toList(),
            )
        }

        fun publishAggregate(status: String, message: String, forcePublish: Boolean = false) {
            if (stopRequested) {
                return
            }
            val now = System.currentTimeMillis()
            val state = synchronized(stateLock) {
                val nextState = aggregateState(status, message)
                val shouldPublish = forcePublish ||
                    status != WhiteDnsScanStatus.Running ||
                    now - lastAggregatePublishMillis >= ScanUiPublishMinIntervalMillis ||
                    nextState.completedResolvers >= nextState.totalResolvers
                if (shouldPublish) {
                    lastAggregatePublishMillis = now
                    nextState
                } else {
                    null
                }
            }
            state?.let(::publishState)
        }

        WhiteDnsScannerResultStore.mergeValidResolvers(applicationContext, validResolvers)
        publishAggregate(WhiteDnsScanStatus.Running, "Scanning ${resolvers.size} resolvers", true)

        val jobs = chunks.mapIndexed { index, chunk ->
            async(Dispatchers.IO) {
                val workerId = index + 1
                try {
                    runWorker(
                        workerId = workerId,
                        scanRoot = scanRoot,
                        binaryFile = binaryFile,
                        serverProfile = serverProfile,
                        settings = settings,
                        resolvers = chunk,
                    ) { line ->
                        handleWorkerOutput(
                            workerIndex = index,
                            line = line,
                            workerStats = workerStats,
                            validResolvers = validResolvers,
                            rejectedResolvers = rejectedResolvers,
                            stateLock = stateLock,
                            publishAggregate = ::publishAggregate,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (stopRequested || !isActive) {
                        throw CancellationException("Worker stopped")
                    }
                    synchronized(stateLock) {
                        workerFailures += "Worker $workerId: ${error.message ?: error::class.java.simpleName}"
                    }
                    publishAggregate(WhiteDnsScanStatus.Running, "Worker $workerId failed", true)
                    false
                }
            }
        }

        val results = jobs.awaitAll()
        val successfulWorkers = results.count { it }
        val finalStatus = if (successfulWorkers == chunks.size && workerFailures.isEmpty()) {
            WhiteDnsScanStatus.Completed
        } else if (successfulWorkers > 0) {
            WhiteDnsScanStatus.Failed
        } else {
            WhiteDnsScanStatus.Failed
        }
        val finalMessage = if (finalStatus == WhiteDnsScanStatus.Completed) {
            "Scan completed with ${validResolvers.size} valid resolvers"
        } else if (validResolvers.isNotEmpty()) {
            "Scan ended with ${validResolvers.size} valid resolvers"
        } else {
            "Scan failed"
        }
        val finalState = synchronized(stateLock) {
            aggregateState(finalStatus, finalMessage)
        }
        writeScanResults(scanRoot, finalState)
        publishState(finalState)
        if (finalStatus == WhiteDnsScanStatus.Completed) {
            WhiteDnsScanRequestStore.delete(applicationContext, sessionId)
        }
        exitForeground()
        stopSelf()
    }

    private fun handleWorkerOutput(
        workerIndex: Int,
        line: String,
        workerStats: Array<WorkerScanStats>,
        validResolvers: MutableSet<String>,
        rejectedResolvers: MutableSet<String>,
        stateLock: Any,
        publishAggregate: (String, String, Boolean) -> Unit,
    ) {
        if (stopRequested) {
            return
        }
        parseStormDnsConnectionProgressLine(line)?.let { progress ->
            synchronized(stateLock) {
                workerStats[workerIndex].completed = progress.completed
                workerStats[workerIndex].total = progress.total.takeIf { it > 0 } ?: workerStats[workerIndex].total
                workerStats[workerIndex].valid = progress.valid
                workerStats[workerIndex].rejected = progress.rejected
            }
            publishAggregate(WhiteDnsScanStatus.Running, progress.label, false)
            return
        }

        when (val telemetry = parseStormDnsScanLine(line)) {
            is StormDnsScanTelemetry.Valid -> {
                val resolver = WhiteDnsScannerResultStore.normalizeResolverEntry(telemetry.resolver) ?: return
                var validCount = 0
                var added = false
                synchronized(stateLock) {
                    added = validResolvers.add(resolver)
                    rejectedResolvers -= resolver
                    validCount = validResolvers.size
                }
                if (added) {
                    WhiteDnsScannerResultStore.mergeValidResolvers(applicationContext, listOf(resolver))
                }
                publishAggregate(WhiteDnsScanStatus.Running, "Found $validCount valid resolvers", false)
            }
            is StormDnsScanTelemetry.Rejected -> {
                val resolver = WhiteDnsScannerResultStore.normalizeResolverEntry(telemetry.resolver) ?: return
                var completed = 0
                synchronized(stateLock) {
                    if (!validResolvers.contains(resolver)) {
                        rejectedResolvers += resolver
                    }
                    completed = workerStats.sumOf { it.completed }
                }
                publishAggregate(WhiteDnsScanStatus.Running, "Processed $completed resolvers", false)
            }
            is StormDnsScanTelemetry.Complete -> {
                synchronized(stateLock) {
                    workerStats[workerIndex].completed = telemetry.total
                    workerStats[workerIndex].total = telemetry.total
                    workerStats[workerIndex].valid = telemetry.valid
                    workerStats[workerIndex].rejected = telemetry.rejected
                }
                publishAggregate(WhiteDnsScanStatus.Running, "Worker ${workerIndex + 1} completed", false)
            }
            null -> Unit
        }
    }

    private fun runWorker(
        workerId: Int,
        scanRoot: File,
        binaryFile: File,
        serverProfile: StormDnsServerProfile,
        settings: WhiteDnsSettings,
        resolvers: List<String>,
        onOutput: (String) -> Unit,
    ): Boolean {
        val workerDir = File(scanRoot, "worker-$workerId").apply { mkdirs() }
        val configFile = File(workerDir, "client.toml")
        val resolversFile = File(workerDir, "resolvers.txt")
        configFile.writeText(
            StormDnsConfigRenderer.renderScanClientToml(
                serverProfile = serverProfile,
                settings = settings,
            ),
            Charsets.UTF_8,
        )
        resolversFile.writeText(resolvers.joinToString(separator = "\n"), Charsets.UTF_8)

        var process: Process? = null
        val outputLock = Any()
        var firstFailureOutput: String? = null
        val recentFailureOutput = java.util.ArrayDeque<String>()
        try {
            val startedProcess = ProcessBuilder(
                binaryFile.absolutePath,
                "-config",
                configFile.absolutePath,
                "-resolvers",
                resolversFile.absolutePath,
                "-scan-only",
            )
                .directory(workerDir)
                .redirectErrorStream(true)
                .start()
            process = startedProcess
            activeProcesses += startedProcess
            val drainThread = drainProcessOutput(startedProcess) { line ->
                val failureLine = sanitizeFailureOutputLine(line)
                if (failureLine.isNotBlank() && shouldCaptureFailureOutput(failureLine)) {
                    synchronized(outputLock) {
                        if (firstFailureOutput == null) {
                            firstFailureOutput = failureLine
                        }
                        recentFailureOutput.addLast(failureLine)
                        while (recentFailureOutput.size > MaxWorkerFailureOutputLines) {
                            recentFailureOutput.removeFirst()
                        }
                    }
                }
                onOutput(line)
            }
            drainThread.start()
            val exitCode = startedProcess.waitFor()
            drainThread.join(OutputDrainJoinMillis)
            if (exitCode != 0) {
                if (stopRequested || Thread.currentThread().isInterrupted) {
                    throw CancellationException("Worker stopped")
                }
                val outputSummary = synchronized(outputLock) {
                    buildFailureOutputSummary(firstFailureOutput, recentFailureOutput.toList())
                }
                throw IllegalStateException("exited with code $exitCode$outputSummary")
            }
            return true
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Worker interrupted")
        } catch (error: IOException) {
            throw IllegalStateException("failed to start: ${error.message ?: error::class.java.simpleName}", error)
        } finally {
            process?.let { activeProcess ->
                activeProcesses -= activeProcess
                if (activeProcess.isAlive) {
                    activeProcess.destroy()
                    if (activeProcess.isAlive) {
                        activeProcess.destroyForcibly()
                    }
                }
            }
        }
    }

    private fun sanitizeFailureOutputLine(line: String): String {
        val cleaned = AnsiEscapeRegex.replace(line, "").trim()
        return if (cleaned.length > MaxWorkerFailureOutputChars) {
            cleaned.take(MaxWorkerFailureOutputChars) + "..."
        } else {
            cleaned
        }
    }

    private fun shouldCaptureFailureOutput(line: String): Boolean {
        return !line.contains("WD_PROGRESS ") && !line.contains("WD_SCAN ")
    }

    private fun buildFailureOutputSummary(
        firstLine: String?,
        recentLines: List<String>,
    ): String {
        val first = firstLine?.takeIf(String::isNotBlank) ?: return ""
        val latest = recentLines.lastOrNull { it.isNotBlank() && it != first }
        return if (latest == null) {
            ": $first"
        } else {
            ": $first | $latest"
        }
    }

    private fun drainProcessOutput(
        process: Process,
        onOutput: (String) -> Unit,
    ): Thread {
        return thread(
            name = "stormdns-scan-output",
            isDaemon = true,
            start = false,
        ) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onOutput(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Destroying the process closes this stream during normal shutdown.
            }
        }
    }

    private fun writeScanResults(scanRoot: File, state: WhiteDnsScanState) {
        val resultsDir = File(scanRoot, "results").apply { mkdirs() }
        val scannerResultResolvers = WhiteDnsScannerResultStore.mergeValidResolvers(
            applicationContext,
            state.validResolverEntries,
        )
        File(resultsDir, WhiteDnsScannerResultStore.ResultFileName).writeText(
            scannerResultResolvers.joinToString(separator = "\n"),
            Charsets.UTF_8,
        )
        File(resultsDir, "valid_resolvers.txt").writeText(
            state.validResolverEntries.joinToString(separator = "\n"),
            Charsets.UTF_8,
        )
        File(resultsDir, "rejected_resolvers.txt").writeText(
            state.rejectedResolverEntries.joinToString(separator = "\n"),
            Charsets.UTF_8,
        )
        val failures = JSONArray().also { array ->
            state.workerFailures.forEach(array::put)
        }
        File(resultsDir, "scan_summary.json").writeText(
            JSONObject()
                .put("sessionId", state.sessionId)
                .put("sourceName", state.sourceName)
                .put("totalResolvers", state.totalResolvers)
                .put("validResolvers", state.validResolvers)
                .put("rejectedResolvers", state.rejectedResolvers)
                .put("completedResolvers", state.completedResolvers)
                .put("workerCount", state.workerCount)
                .put("durationMillis", state.durationMillis)
                .put("status", state.status)
                .put("validResolverEntries", JSONArray().also { array ->
                    state.validResolverEntries.forEach(array::put)
                })
                .put("rejectedResolverEntries", JSONArray().also { array ->
                    state.rejectedResolverEntries.forEach(array::put)
                })
                .put("workerFailures", failures)
                .toString(),
            Charsets.UTF_8,
        )
    }

    private fun stopScanRuntime(message: String) {
        val processes = synchronized(activeProcesses) {
            activeProcesses.toList()
        }
        processes.forEach { process ->
            runCatching {
                process.destroy()
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }
        activeProcesses.clear()
        if (currentSessionId.isNotBlank()) {
            val previous = WhiteDnsScanStateStore.read(applicationContext)
            if (
                previous.sessionId == currentSessionId &&
                !previous.isRunning &&
                message != "Scan stopped"
            ) {
                return
            }
            publishState(
                WhiteDnsScanStateStore.stopped(
                    context = applicationContext,
                    sessionId = currentSessionId,
                    message = message,
                    clearWorkerFailures = message == "Scan stopped",
                ),
            )
        }
    }

    private fun publishState(state: WhiteDnsScanState) {
        if (stopRequested && state.status != WhiteDnsScanStatus.Stopped) {
            return
        }
        WhiteDnsScanStateStore.write(applicationContext, state)
        WhiteDnsScanEvents.state(state)
        updateForegroundNotification(notificationTextFor(state))
        sendBroadcast(
            Intent(BroadcastAction)
                .setPackage(packageName)
                .putExtra(BroadcastExtraType, BroadcastTypeState)
                .putExtra(BroadcastExtraSessionId, state.sessionId)
                .putExtra(BroadcastExtraMessage, state.message),
        )
    }

    private fun notificationTextFor(state: WhiteDnsScanState): String {
        return when (state.status) {
            WhiteDnsScanStatus.Running,
            WhiteDnsScanStatus.Starting -> "Scanning ${state.completedResolvers}/${state.totalResolvers}"
            WhiteDnsScanStatus.Completed -> "Scan completed: ${state.validResolvers} valid"
            WhiteDnsScanStatus.Failed -> "Scan failed"
            else -> state.message.ifBlank { "Scan stopped" }
        }
    }

    private fun enterForeground(statusText: String) {
        createNotificationChannel()
        val notification = buildForegroundNotification(statusText)
        if (foregroundStarted) {
            updateForegroundNotification(statusText)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationId, notification)
        }
        foregroundStarted = true
    }

    private fun updateForegroundNotification(statusText: String) {
        if (!foregroundStarted) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NotificationId, buildForegroundNotification(statusText))
    }

    private fun exitForeground() {
        if (!foregroundStarted) {
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NotificationChannelId,
            "WhiteDNS Scan",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active WhiteDNS resolver scans"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            pendingIntentFlags,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WhiteDnsScanService::class.java).setAction(ActionStop),
            pendingIntentFlags,
        )
        return NotificationCompat.Builder(this, NotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WhiteDNS Scan")
            .setContentText(statusText)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notification, "Stop", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private data class WorkerScanStats(
        var total: Int = 0,
        var completed: Int = 0,
        var valid: Int = 0,
        var rejected: Int = 0,
    )

    companion object {
        private const val Tag = "WhiteDnsScanService"
        const val BroadcastAction = "shop.whitedns.client.scan.EVENT"
        const val BroadcastExtraType = "shop.whitedns.client.scan.extra.TYPE"
        const val BroadcastExtraSessionId = "shop.whitedns.client.scan.extra.SESSION_ID"
        const val BroadcastExtraMessage = "shop.whitedns.client.scan.extra.MESSAGE"
        const val BroadcastTypeState = "state"
        private const val ActionStart = "shop.whitedns.client.scan.START"
        private const val ActionStop = "shop.whitedns.client.scan.STOP"
        private const val ExtraSessionId = "shop.whitedns.client.scan.extra.SESSION_ID"
        private const val OutputDrainJoinMillis = 500L
        private const val NotificationId = 3301
        private const val NotificationChannelId = "whitedns_scan"
        private const val MaxWorkerFailureOutputLines = 6
        private const val MaxWorkerFailureOutputChars = 180
        private const val ScanUiPublishMinIntervalMillis = 750L
        private val AnsiEscapeRegex = Regex("${27.toChar()}\\[[;?0-9]*[ -/]*[@-~]")

        fun start(
            context: Context,
            sessionId: String,
            serverProfile: StormDnsServerProfile,
            settings: WhiteDnsSettings,
            sourceName: String,
            resolverFile: File,
            workerCount: Int,
            initialValidResolvers: List<String> = emptyList(),
            initialRejectedResolvers: List<String> = emptyList(),
            initialCompletedResolvers: Int = 0,
            totalResolvers: Int = 0,
        ) {
            RuntimeLaunchRequestStore.save(
                context = context,
                requestId = sessionId,
                serverProfile = serverProfile,
                settings = settings,
            )
            WhiteDnsScanRequestStore.save(
                context = context,
                request = WhiteDnsScanLaunchRequest(
                    id = sessionId,
                    sourceName = sourceName,
                    resolverFilePath = resolverFile.absolutePath,
                    workerCount = workerCount.coerceAtLeast(1),
                    initialValidResolvers = initialValidResolvers,
                    initialRejectedResolvers = initialRejectedResolvers,
                    initialCompletedResolvers = initialCompletedResolvers,
                    totalResolvers = totalResolvers,
                ),
            )
            startPrepared(context, sessionId)
        }

        fun startPrepared(
            context: Context,
            sessionId: String,
        ) {
            val intent = Intent(context, WhiteDnsScanService::class.java)
                .setAction(ActionStart)
                .putExtra(ExtraSessionId, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, WhiteDnsScanService::class.java)
                        .setAction(ActionStop),
                )
            }.onFailure { error ->
                Log.w(Tag, "Failed to request scan service stop", error)
                runCatching {
                    context.stopService(Intent(context, WhiteDnsScanService::class.java))
                }
            }
        }
    }
}
