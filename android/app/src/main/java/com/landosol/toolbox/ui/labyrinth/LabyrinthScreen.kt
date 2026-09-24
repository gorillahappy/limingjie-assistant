package com.landosol.toolbox.ui.labyrinth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.labyrinth.debug.DebugDashboardEndpoint
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.rerollSettings
import com.landosol.toolbox.labyrinth.labyrinthRerollStatusText
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunProgress
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchCheckpoint
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoalMode
import com.landosol.toolbox.labyrinth.LabyrinthGuildOption
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage
import com.landosol.toolbox.labyrinth.LabyrinthBossOption
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthRerollOptions
import com.landosol.toolbox.labyrinth.LabyrinthRouteEvaluationMode
import com.landosol.toolbox.labyrinth.LabyrinthRouteProgress
import com.landosol.toolbox.labyrinth.LabyrinthRouteVerdict
import com.landosol.toolbox.labyrinth.LabyrinthThirdBlockChoice
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.automation.session.GameSessionResetState
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.ui.account.GeetestCaptchaDialog
import kotlinx.coroutines.launch

private enum class LabyrinthTab(val label: String) {
    REROLL("刷开局"),
    CURRENT("当前开局"),
    AUTOMATION("自动执行"),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
fun LabyrinthScreen(
    state: LabyrinthUiState,
    entryRecognitionState: LabyrinthEntryRecognitionSessionState,
    sessionResetState: GameSessionResetState,
    autoRunProgress: LabyrinthAutoRunProgress,
    batchCheckpoint: LabyrinthBatchCheckpoint? = null,
    batchHaltReason: LabyrinthBatchHaltReason? = null,
    onBack: (() -> Unit)? = null,
    onGuildSelected: (Int) -> Unit,
    onDifficultySelected: (Int) -> Unit,
    onPerfectStartChange: (Boolean) -> Unit,
    onThirdBlockChoiceSelected: (LabyrinthThirdBlockChoice) -> Unit,
    onArea3BossToggle: (Int) -> Unit,
    onArea5BossToggle: (Int) -> Unit,
    onMaxAttemptsChange: (String) -> Unit,
    onRerollUntilFoundChange: (Boolean) -> Unit,
    onRetireExistingChange: (Boolean) -> Unit,
    onSaveSettings: (LabyrinthRerollSettings) -> Unit,
    onCheckStatus: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartEntryRecognition: () -> Unit,
    onStartEntryAutomation: () -> Unit,
    onStopEntryRecognition: () -> Unit,
    onStartSessionReset: () -> Unit,
    onStopSessionReset: () -> Unit,
    onStartAutoRun: (List<LabyrinthBatchGoal>) -> Unit,
    onStopAutoRun: () -> Unit,
    onCaptchaSolved: (String) -> Unit,
    onCaptchaError: (String) -> Unit,
    onCancelCaptcha: () -> Unit,
    onOpenStrategies: () -> Unit = {},
) {
    var selectedTabName by rememberSaveable { mutableStateOf(LabyrinthTab.REROLL.name) }
    val selectedTab = LabyrinthTab.valueOf(selectedTabName)
    var confirmRetreat by remember(state.selectedAccount?.id) { mutableStateOf(false) }
    val requestStart: () -> Unit = { if (state.retireExisting) confirmRetreat = true else onStart() }
    if (confirmRetreat) {
        AlertDialog(onDismissRequest = { confirmRetreat = false },
            title = { Text("允许彻底撤退当前开局？") },
            text = { Text("本次刷取可能放弃当前开局的进度和未领取奖励。只撤退已确认不符合目标的开局；状态不明确时会停止。") },
            confirmButton = { TextButton(onClick = { confirmRetreat = false; onStart() }) { Text("确认并开始") } },
            dismissButton = { TextButton(onClick = { confirmRetreat = false }) { Text("取消") } })
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("黎明界")
                        Text(
                            AppVersion.display,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    onBack?.let { back -> TextButton(onClick = back) { Text("返回") } }
                },
                actions = { TextButton(onClick = onOpenStrategies) { Text("策略设置") } },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (scrollState.value > 0) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { scrollState.animateScrollTo(0) } },
                    ) {
                        Text("回到顶部")
                    }
                }
                if (selectedTab == LabyrinthTab.REROLL) {
                    ExtendedFloatingActionButton(
                        onClick = { if (state.isWorking) onStop() else if (state.settingsReady && state.captcha == null) requestStart() },
                    ) {
                        Text(if (state.isWorking) "停止刷取" else "开始刷开局")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 152.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "黎明界助手 · 作者 wbero",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "交流群：1065226139",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "测试版本，有较多 bug，可能会卡在某些流程。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { uriHandler.openUri(DebugDashboardEndpoint.baseUrl) },
                        ) {
                            Text("打开实时日志控制台")
                        }
                        TextButton(
                            onClick = { uriHandler.openUri(DebugDashboardEndpoint.logsUrl) },
                        ) {
                            Text("下载日志 ZIP")
                        }
                    }
                }
            }
            AccountCard(state)
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                LabyrinthTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTabName = tab.name
                            scope.launch { scrollState.scrollTo(0) }
                        },
                        text = { Text(tab.label) },
                    )
                }
            }

            when (selectedTab) {
                LabyrinthTab.REROLL -> RerollContent(
                    state = state,
                    onGuildSelected = onGuildSelected,
                    onSaveSettings = onSaveSettings,
                    onDifficultySelected = onDifficultySelected,
                    onPerfectStartChange = onPerfectStartChange,
                    onThirdBlockChoiceSelected = onThirdBlockChoiceSelected,
                    onArea3BossToggle = onArea3BossToggle,
                    onArea5BossToggle = onArea5BossToggle,
                    onMaxAttemptsChange = onMaxAttemptsChange,
                    onRerollUntilFoundChange = onRerollUntilFoundChange,
                    onRetireExistingChange = onRetireExistingChange,
                )

                LabyrinthTab.CURRENT -> CurrentRunContent(
                    state = state,
                    onCheckStatus = onCheckStatus,
                    onStop = onStop,
                )

                LabyrinthTab.AUTOMATION -> {
                    EntryRecognitionCard(
                        state = entryRecognitionState,
                        onStart = onStartEntryRecognition,
                        onStartAutomation = onStartEntryAutomation,
                        onStop = onStopEntryRecognition,
                    )
                    BatchRunCard(
                        checkpoint = batchCheckpoint,
                        haltReason = batchHaltReason,
                        routeProgress = entryRecognitionState.routeProgress,
                        guildOptions = state.guildOptions.take(5),
                        selectedDifficulty = state.selectedDifficulty,
                        enabled = state.selectedAccount != null && state.settingsReady && !state.isWorking,
                        onStart = onStartAutoRun,
                        onStop = onStopAutoRun,
                    )
                }
            }

            if (selectedTab != LabyrinthTab.REROLL) state.progress?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (selectedTab != LabyrinthTab.REROLL) state.message?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissMessage) { Text("关闭") }
                    }
                }
            }
        }
    }

    state.captcha?.let { captcha ->
        GeetestCaptchaDialog(
            state = captcha,
            isWorking = state.isWorking,
            onSolved = onCaptchaSolved,
            onError = onCaptchaError,
            onDismiss = onCancelCaptcha,
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun RerollContent(
    state: LabyrinthUiState,
    onGuildSelected: (Int) -> Unit,
    onSaveSettings: (LabyrinthRerollSettings) -> Unit,
    onDifficultySelected: (Int) -> Unit,
    onPerfectStartChange: (Boolean) -> Unit,
    onThirdBlockChoiceSelected: (LabyrinthThirdBlockChoice) -> Unit,
    onArea3BossToggle: (Int) -> Unit,
    onArea5BossToggle: (Int) -> Unit,
    onMaxAttemptsChange: (String) -> Unit,
    onRerollUntilFoundChange: (Boolean) -> Unit,
    onRetireExistingChange: (Boolean) -> Unit,
) {
    var showSettings by remember(state.selectedAccount?.id) { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isWorking) {
        while (state.isWorking) { now = System.currentTimeMillis(); delay(1_000) }
        now = System.currentTimeMillis()
    }
    SelectionCard("刷取状态", "切换到后台后可在通知栏查看进度和停止任务") {
        Text(labyrinthRerollStatusText(state, now), style = MaterialTheme.typography.titleSmall)
        if (state.isWorking && state.message != null) Text(state.message)
        val routeSummary = when (state.routeEvaluationMode) {
            LabyrinthRouteEvaluationMode.VALUE_ROUTE -> "价值路线v2·每区允许少${state.valueAllowance}格"
            LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE -> if (state.perfectStart) "旧版完美模板" else "旧版自定义路线"
        }
        Text("当前条件：难度${state.selectedDifficulty} · $routeSummary · " +
            if (state.rerollUntilFound) "刷到出" else "最多${state.maxAttempts}次")
        Text("区域3 Boss：${state.area3BossOptions.filter { it.unitId in state.selectedArea3BossIds }.joinToString { it.name }.ifEmpty { "不限" }}")
        Text("区域5 Boss：${state.area5BossOptions.filter { it.unitId in state.selectedArea5BossIds }.joinToString { it.name }.ifEmpty { "不限" }}")
    }
    SelectionCard("公会", "选择进入黎明界时使用的公会") {
        state.guildOptions.take(5).forEach { guild ->
            RadioOption(label = guild.name, selected = guild.guildId == state.selectedGuildId,
                enabled = state.settingsReady && !state.isWorking && state.captcha == null,
                onClick = { onGuildSelected(guild.guildId) })
        }
        TextButton(onClick = { showSettings = true },
            enabled = state.settingsReady && !state.isWorking && state.captcha == null) { Text("刷开局设置") }
    }
    if (showSettings) RerollSettingsDialog(state, onDismiss = { showSettings = false },
        onSave = { onSaveSettings(it); showSettings = false })
}

@Composable
private fun RerollSettingsDialog(
    state: LabyrinthUiState,
    onDismiss: () -> Unit,
    onSave: (LabyrinthRerollSettings) -> Unit,
) {
    var draft by remember { mutableStateOf(state.rerollSettings()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刷开局设置 · 当前账号") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保存后下次自动恢复；运行中使用开始时的固定配置。")
                TextButton(onClick = { draft = LabyrinthRerollSettings(guildId = state.selectedGuildId,
                    difficulty = LabyrinthRerollOptions.DEFAULT_DIFFICULTY.coerceAtMost(state.availableDifficulties.last()));
                    error = null }) { Text("恢复默认（保存后生效）") }
                RerollSettingsFields(
                    state = state.copy(selectedDifficulty = draft.difficulty, perfectStart = draft.perfectStart,
                        routeEvaluationMode = draft.routeEvaluationMode, valueAllowance = draft.valueAllowance,
                        thirdBlockChoice = draft.thirdBlockChoice, selectedArea3BossIds = draft.area3BossIds,
                        selectedArea5BossIds = draft.area5BossIds, maxAttempts = draft.maxAttempts,
                        rerollUntilFound = draft.rerollUntilFound, retireExisting = draft.retireExisting),
                    onDifficultySelected = { draft = draft.copy(difficulty = it) },
                    onPerfectStartChange = { draft = draft.copy(perfectStart = it) },
                    onRouteEvaluationModeSelected = { draft = draft.copy(routeEvaluationMode = it) },
                    onValueAllowanceSelected = { draft = draft.copy(valueAllowance = it) },
                    onThirdBlockChoiceSelected = { draft = draft.copy(thirdBlockChoice = it) },
                    onArea3BossToggle = { draft = draft.copy(area3BossIds = if (it in draft.area3BossIds) draft.area3BossIds - it else draft.area3BossIds + it) },
                    onArea5BossToggle = { draft = draft.copy(area5BossIds = if (it in draft.area5BossIds) draft.area5BossIds - it else draft.area5BossIds + it) },
                    onMaxAttemptsChange = { draft = draft.copy(maxAttempts = it) },
                    onRerollUntilFoundChange = { draft = draft.copy(rerollUntilFound = it) },
                    onRetireExistingChange = { draft = draft.copy(retireExisting = it) },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            error = draft.validationError() ?: if (draft.difficulty !in state.availableDifficulties) "所选难度尚未解锁" else null
            if (error == null) onSave(draft)
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RerollSettingsFields(
    state: LabyrinthUiState,
    onDifficultySelected: (Int) -> Unit,
    onPerfectStartChange: (Boolean) -> Unit,
    onRouteEvaluationModeSelected: (LabyrinthRouteEvaluationMode) -> Unit,
    onValueAllowanceSelected: (Int) -> Unit,
    onThirdBlockChoiceSelected: (LabyrinthThirdBlockChoice) -> Unit,
    onArea3BossToggle: (Int) -> Unit,
    onArea5BossToggle: (Int) -> Unit,
    onMaxAttemptsChange: (String) -> Unit,
    onRerollUntilFoundChange: (Boolean) -> Unit,
    onRetireExistingChange: (Boolean) -> Unit,
) {
    SelectionCard("难度", "检查当前开局后只显示已解锁难度") {
        ChoiceRow {
            state.availableDifficulties.forEach { difficulty ->
                FilterChip(
                    selected = difficulty == state.selectedDifficulty,
                    onClick = { onDifficultySelected(difficulty) },
                    label = { Text(difficulty.toString()) },
                    enabled = !state.isWorking,
                )
            }
        }
    }
    SelectionCard(
        title = "路线要求",
        description = "价值路线 v2 按当前地图自身上界选最大收益路径；旧版固定模板保留用于 A/B 对照。",
    ) {
        Text("路线判定", style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            LabyrinthRouteEvaluationMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == state.routeEvaluationMode,
                    onClick = { onRouteEvaluationModeSelected(mode) },
                    label = { Text(mode.label) },
                    enabled = !state.isWorking,
                )
            }
        }
        if (state.routeEvaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE) {
            Text("每个区域允许少拿几格", style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                (0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE).forEach { allowance ->
                    FilterChip(
                        selected = allowance == state.valueAllowance,
                        onClick = { onValueAllowanceSelected(allowance) },
                        label = { Text(allowance.toString()) },
                        enabled = !state.isWorking,
                    )
                }
            }
            Text(
                "0=必须拿到该地图自身可达的全部贵重格；1=每区最多少1格。Boss仍是硬条件，不会用少拿额度换掉。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            CheckOption(
                label = "完美开局（旧版模板）",
                checked = state.perfectStart,
                enabled = !state.isWorking,
                onCheckedChange = onPerfectStartChange,
            )
        }
        Text("区域 3 / 5 遗物 vs 事件", style = MaterialTheme.typography.labelLarge)
        ChoiceRow {
            LabyrinthThirdBlockChoice.entries.forEach { choice ->
                FilterChip(
                    selected = choice == state.thirdBlockChoice,
                    onClick = { onThirdBlockChoiceSelected(choice) },
                    label = { Text(choice.label) },
                    enabled = !state.isWorking,
                )
            }
        }
        Text(
            if (state.routeEvaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE)
                "价值路线中该偏好不绑定列号；选择“两者都行”时不会增加刷图门槛，同分路线优先遗物。"
            else
                "旧版模板中该条件仅在勾选完美开局时生效。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    BossSelectionCard(
        title = "区域 3 Boss",
        options = state.area3BossOptions,
        selectedIds = state.selectedArea3BossIds,
        enabled = !state.isWorking,
        onToggle = onArea3BossToggle,
    )
    BossSelectionCard(
        title = "区域 5 Boss",
        options = state.area5BossOptions,
        selectedIds = state.selectedArea5BossIds,
        enabled = !state.isWorking,
        onToggle = onArea5BossToggle,
    )
    CheckOption(
        label = "刷到出（持续刷新直到目标路线）",
        checked = state.rerollUntilFound,
        enabled = !state.isWorking,
        onCheckedChange = onRerollUntilFoundChange,
    )
    Text(
        "开启后忽略最大尝试次数。同一非目标开局撤退后若短暂残留，会退避并最多重试撤退 3 次；状态无法确认、出现不同开局或连续撤退失败时仍会安全停止。",
        style = MaterialTheme.typography.bodySmall,
    )
    NumericField(
        label = "最大尝试次数（1-${LabyrinthRerollOptions.MAX_ATTEMPTS}）",
        value = state.maxAttempts,
        enabled = !state.isWorking && !state.rerollUntilFound,
        onValueChange = onMaxAttemptsChange,
    )
    CheckOption(
        label = "允许彻底撤退当前开局（开始前确认）",
        checked = state.retireExisting,
        enabled = !state.isWorking,
        onCheckedChange = onRetireExistingChange,
    )
    Text(
        "进入请求发出后若连接中断，不会再次进入或撤退；应用会保留待验证检查点，重新登录后读取服务端现有开局再判定。",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun CurrentRunContent(
    state: LabyrinthUiState,
    onCheckStatus: () -> Unit,
    onStop: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCheckStatus, enabled = !state.isWorking) {
            Text(if (state.routeVerdict == LabyrinthRouteVerdict.PENDING_VERIFICATION) "重新验证" else "读取当前开局")
        }
        if (state.isWorking) Button(onClick = onStop) { Text("停止") }
    }
    SelectionCard(
        title = "路线判定",
        description = "联网读取 top 与 resume，并使用当前页面选择的公会、难度和路线条件重新验证开局。",
    ) {
        val verdict = state.routeVerdict
        val currentGuildName = state.currentGuildId?.let { guildId ->
            state.guildOptions.firstOrNull { it.guildId == guildId }?.name ?: "ID $guildId"
        }
        Text(
            verdict?.label ?: "尚未记录开局",
            style = MaterialTheme.typography.titleLarge,
            color = when (verdict) {
                LabyrinthRouteVerdict.TARGET -> MaterialTheme.colorScheme.primary
                LabyrinthRouteVerdict.NOT_TARGET -> MaterialTheme.colorScheme.error
                LabyrinthRouteVerdict.PENDING_VERIFICATION, null -> MaterialTheme.colorScheme.onSurface
            },
        )
        state.checkpointEnterId?.let { Text("Enter ID 尾号：${it % 10_000}") }
        Text("当前公会：${currentGuildName ?: "点击上方按钮读取"}")
        Text("当前难度：${state.currentDifficulty?.toString() ?: "点击上方按钮读取"}")
        state.verdictMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (verdict == LabyrinthRouteVerdict.PENDING_VERIFICATION) {
            Text(
                "状态未确认前不会自动重试进入或撤退。恢复网络后点“重新验证”。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (state.routeBlockIds.isNotEmpty()) {
        SelectionCard("已保存执行路线") {
            Text("共 ${state.routeBlockIds.size} 格，自动执行可以继续使用这份结构化路线。")
        }
    }
}

@Composable
private fun EntryRecognitionCard(
    state: LabyrinthEntryRecognitionSessionState,
    onStart: () -> Unit,
    onStartAutomation: () -> Unit,
    onStop: () -> Unit,
) {
    SelectionCard(
        "半自动路线执行",
        "程序负责进入黎明界、按保存路线点节点并推进结算；角色、战斗编组与遗物由你选择",
    ) {
        val result = state.lastResult
        if (result == null) {
            Text("等待识别帧", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(result.observation.state.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "置信度 ${"%.3f".format(result.observation.confidence)} · ${result.elapsedMillis} ms · 第 ${state.frameCount} 帧",
                style = MaterialTheme.typography.bodySmall,
            )
            if (result.nodeClassifications.isNotEmpty()) {
                Text(
                    result.nodeClassifications
                        .groupingBy { it.blockType }
                        .eachCount()
                        .entries
                        .sortedBy { it.key }
                        .joinToString("、") { (type, count) ->
                            "${LabyrinthNodeTypes.labelOf(type)}×$count"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result.battleTeamSelection?.let { team ->
                Text(
                    "编组筛选：${team.currentFilter.label} · 可见角色 ${team.visibleCharacters.size} · " +
                        "已识别 ${team.recognizedCharacterCount} · " +
                        if (team.scrollbar.canScroll) "需要时可滚动" else "滚动条未测得，仍可滑动",
                    style = MaterialTheme.typography.bodySmall,
                )
                team.selectedCharacters
                    .mapNotNull { it.displayName }
                    .takeIf(List<String>::isNotEmpty)
                    ?.let { Text("当前队伍：${it.joinToString("、")}", style = MaterialTheme.typography.bodySmall) }
            }
            if (result.matchedFeatures.isNotEmpty()) {
                Text(result.matchedFeatures.take(4).joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.joinedCharacters.isNotEmpty()) {
            Text(
                "已记录队伍：" + state.joinedCharacters.joinToString("、") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!state.dryRun || state.actionCount > 0) {
            Text(
                "已执行 ${state.actionCount} 次${state.lastActionLabel?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            Button(onClick = onStop) { Text(if (state.dryRun) "停止识别" else "停止入口流程") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStart) { Text("只读识别") }
                Button(onClick = onStartAutomation) { Text("执行入口流程") }
            }
        }
    }
}

@Composable
private fun BatchRunCard(
    checkpoint: LabyrinthBatchCheckpoint?,
    haltReason: LabyrinthBatchHaltReason?,
    routeProgress: LabyrinthRouteProgress?,
    guildOptions: List<LabyrinthGuildOption>,
    selectedDifficulty: Int,
    enabled: Boolean,
    onStart: (List<LabyrinthBatchGoal>) -> Unit,
    onStop: () -> Unit,
) {
    val active = checkpoint?.stage in setOf(
        LabyrinthBatchStage.REROLLING,
        LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
        LabyrinthBatchStage.RUNNING_LABYRINTH,
        LabyrinthBatchStage.RECORDING_RESULT,
    )
    SelectionCard(
        title = "批量自动执行",
        description = "一次屏幕授权跑完全部目标，按列表顺序逐个公会执行：刷开局 → 底栏主页触发旧会话失效 → " +
            "进入并通关 → 记录 → 下一轮。难度沿用上方设置（$selectedDifficulty）；" +
            "战斗连续失败按策略设置放弃本局并重刷。",
    ) {
        // Per-guild target text and mode; the guild list itself is fixed (top five).
        val counts = remember(guildOptions) {
            mutableStateOf(guildOptions.associate { it.guildId to "" })
        }
        val modes = remember(guildOptions) {
            mutableStateOf(guildOptions.associate { it.guildId to LabyrinthBatchGoalMode.CLEARS })
        }

        checkpoint?.let { cp ->
            Text(
                "批次 ${cp.batchId} · 阶段 ${cp.stage.name}" +
                    (cp.currentRunId?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            cp.goals.forEachIndexed { index, goal ->
                val name = guildOptions.firstOrNull { it.guildId == goal.guildId }?.name ?: "ID ${goal.guildId}"
                val marker = when {
                    index == cp.activeGoalIndex && cp.stage != LabyrinthBatchStage.COMPLETED -> "▶ "
                    goal.isSatisfied -> "✓ "
                    else -> "· "
                }
                val modeLabel = if (goal.mode == LabyrinthBatchGoalMode.CLEARS) "通关" else "开局"
                Text(
                    "$marker$name：$modeLabel ${goal.completedCount}/${goal.targetCount}" +
                        (if (goal.failedCount > 0) " · 放弃 ${goal.failedCount}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (cp.abnormalRuns > 0) {
                Text("异常中止 ${cp.abnormalRuns} 局（未计入）", style = MaterialTheme.typography.bodySmall)
            }
            cp.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            haltReason?.let {
                Text("停止原因：${it.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
        routeProgress?.let { route ->
            Text(
                "本轮路线：区域${route.currentArea} · 已走 ${route.visitedCount}/${route.routeNodeCount}" +
                    (route.nextNodeLabel?.let { " · 下一节点 $it" } ?: "") +
                    if (route.complete) " · 已完成" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (active) {
            Button(onClick = onStop) { Text("停止批量执行") }
            return@SelectionCard
        }

        Text("目标（留空 = 跳过该公会）", style = MaterialTheme.typography.titleSmall)
        guildOptions.forEach { guild ->
            val text = counts.value[guild.guildId].orEmpty()
            val mode = modes.value[guild.guildId] ?: LabyrinthBatchGoalMode.CLEARS
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        counts.value = counts.value + (guild.guildId to value.filter(Char::isDigit).take(2))
                    },
                    label = { Text(guild.name) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = mode == LabyrinthBatchGoalMode.CLEARS,
                    onClick = {
                        modes.value = modes.value + (
                            guild.guildId to if (mode == LabyrinthBatchGoalMode.CLEARS) {
                                LabyrinthBatchGoalMode.ATTEMPTS
                            } else {
                                LabyrinthBatchGoalMode.CLEARS
                            }
                            )
                    },
                    label = { Text(if (mode == LabyrinthBatchGoalMode.CLEARS) "按通关计" else "按开局计") },
                )
            }
        }
        val goals = guildOptions.mapNotNull { guild ->
            counts.value[guild.guildId]?.toIntOrNull()?.takeIf { it >= 1 }?.let { count ->
                LabyrinthBatchGoal(
                    guildId = guild.guildId,
                    targetCount = count,
                    mode = modes.value[guild.guildId] ?: LabyrinthBatchGoalMode.CLEARS,
                )
            }
        }
        val total = goals.sumOf { it.targetCount }
        Button(
            onClick = { onStart(goals) },
            enabled = enabled && goals.isNotEmpty(),
        ) { Text(if (goals.isEmpty()) "开始批量执行" else "开始批量执行（${goals.size} 个公会 · 共 $total 轮）") }
        if (!enabled) {
            Text("需要已选账号且刷开局设置就绪、无进行中的刷取任务。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutoRunCard(
    progress: LabyrinthAutoRunProgress,
    routeProgress: LabyrinthRouteProgress?,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
) {
    SelectionCard(
        title = "自动执行循环",
        description = "按已保存路线自动完成节点、事件与结算，达到刷取次数后停止；轮间自动重置会话",
    ) {
        var targetRunsText by remember { mutableStateOf("1") }
        if (progress.targetRuns > 0 || progress.stage.isNotEmpty()) {
            Text(
                "进度：${progress.completedRuns}/${progress.targetRuns} 轮 · ${progress.stage}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        routeProgress?.let { route ->
            Text(
                "本轮路线：区域${route.currentArea} · 已走 ${route.visitedCount}/${route.routeNodeCount}" +
                    (route.nextNodeLabel?.let { " · 下一节点 $it" } ?: "") +
                    if (route.complete) " · 已完成" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (progress.running) {
            Button(onClick = onStop) { Text("停止自动执行") }
        } else {
            OutlinedTextField(
                value = targetRunsText,
                onValueChange = { value -> targetRunsText = value.filter(Char::isDigit).take(2) },
                label = { Text("刷取次数（完整通关次数）") },
                singleLine = true,
            )
            Button(
                onClick = { targetRunsText.toIntOrNull()?.let(onStart) },
                enabled = targetRunsText.toIntOrNull()?.let { it >= 1 } == true,
            ) { Text("开始自动执行") }
        }
    }
}

@Composable
private fun SessionResetCard(
    state: GameSessionResetState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SelectionCard("会话失效重置", "不杀进程：触发「会话失效」弹窗并返回标题页，等待重新进入黎明界") {
        Text("状态：${state.status}", style = MaterialTheme.typography.bodyMedium)
        if (state.status == GameSessionResetStatus.RUNNING) {
            Text("阶段：${state.stage}", style = MaterialTheme.typography.bodySmall)
            Text("识别帧：${state.frameCount}", style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            Button(onClick = onStop) { Text("停止重置") }
        } else {
            Button(onClick = onStart) { Text("触发会话失效重置") }
        }
    }
}

@Composable
private fun AccountCard(state: LabyrinthUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前账号", style = MaterialTheme.typography.titleMedium)
            Text(state.selectedAccount?.alias ?: "未选择账号")
            Text(
                if (state.selectedAccount?.gameUid != null) "已绑定游戏 UID" else "请先完成原生登录",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun BossSelectionCard(
    title: String,
    options: List<LabyrinthBossOption>,
    selectedIds: Set<Int>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    SelectionCard(title, "可多选；不选择任何 Boss 表示都可以。") {
        options.forEach { boss ->
            CheckOption(
                label = "【${boss.difficulty.label}】${boss.name}",
                checked = boss.unitId in selectedIds,
                enabled = enabled,
                onCheckedChange = { onToggle(boss.unitId) },
            )
        }
    }
}

@Composable
private fun CheckOption(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { changed -> if (changed.all(Char::isDigit)) onValueChange(changed) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
