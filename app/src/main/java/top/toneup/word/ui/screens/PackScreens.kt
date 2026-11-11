package top.toneup.word.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.AppContainer
import top.toneup.word.data.local.entity.PlanEntity
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.repository.DictionaryHit
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.ui.ImportWordsViewModel
import top.toneup.word.ui.PlanDetailViewModel
import top.toneup.word.ui.PlanListViewModel
import top.toneup.word.ui.PlanRowUi
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.ui.WriteWordViewModel
import top.toneup.word.ui.WriteWordUiState
import top.toneup.word.ui.WrittenWordEntry
import top.toneup.word.ui.components.AccentPanel
import top.toneup.word.ui.components.AppCard
import top.toneup.word.ui.components.ConfirmDialog
import top.toneup.word.ui.components.EmptyState
import top.toneup.word.ui.components.PrimaryButton
import top.toneup.word.ui.components.ReviewBadge
import top.toneup.word.ui.components.SecondaryButton
import top.toneup.word.ui.components.SectionHeader
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.Spacing

// ---------------------------------------------------------------------------------------------
// Plans and their packs (schema v4)
// ---------------------------------------------------------------------------------------------

/**
 * Plan management: list, create, rename, delete, switch (schema v4, D5).
 *
 * A plan is the study scope; its packs are only content sources. The built-in default plan may be
 * renamed but not deleted — the repository guards that too, but the menu hides the action so the
 * learner never taps into a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlan: (String) -> Unit,
) {
    val viewModel: PlanListViewModel = viewModel(factory = ViewModelFactories.planList(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<PlanNameDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<PlanEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // One-shot messages (rename ok, delete refused …) surface as snackbars; null clears.
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("计划管理") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "plans_create_panel") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("自建计划", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "把一个用途的词库放进同一个计划，例如「外国文献单词计划」下放《时代周刊》《经济学人》词库。学习、复习、考试与统计都按当前计划合并汇总。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        PrimaryButton(
                            text = "新建计划",
                            onClick = {
                                nameDialog = PlanNameDialogState(
                                    mode = PlanNameMode.Create,
                                    planId = null,
                                    initial = "",
                                )
                            },
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("plans_create"),
                        )
                    }
                }
            }

            if (state.plans.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有计划",
                        description = "内置的「默认计划」会在首次启动时自动创建；也可以新建自己的计划。",
                        actionLabel = "新建计划",
                        onAction = {
                            nameDialog = PlanNameDialogState(
                                mode = PlanNameMode.Create,
                                planId = null,
                                initial = "",
                            )
                        },
                    )
                }
            }

            items(state.plans, key = { it.plan.id }) { row ->
                PlanRow(
                    row = row,
                    isActive = row.plan.id == state.activePlanId,
                    onOpen = { onOpenPlan(row.plan.id) },
                    onSetCurrent = { viewModel.activate(row.plan.id) },
                    onRename = {
                        nameDialog = PlanNameDialogState(
                            mode = PlanNameMode.Rename,
                            planId = row.plan.id,
                            initial = row.plan.name,
                        )
                    },
                    onDelete = { pendingDelete = row.plan },
                )
            }
        }
    }

    nameDialog?.let { dialog ->
        NameDialog(
            title = if (dialog.mode == PlanNameMode.Create) "新建计划" else "重命名计划",
            label = "计划名称",
            fieldTag = "plan_name_field",
            initialName = dialog.initial,
            onConfirm = { name ->
                when (dialog.mode) {
                    PlanNameMode.Create -> viewModel.createPlan(name, onCreated = onOpenPlan)
                    PlanNameMode.Rename -> dialog.planId?.let { viewModel.renamePlan(it, name) }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }

    pendingDelete?.let { plan ->
        ConfirmDialog(
            title = "删除计划「${plan.name}」？",
            message = "计划本身不含单词；如果计划下还有词库，需要先删除或移出这些词库。删除计划不影响其他计划里的单词与进度。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deletePlan(plan.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Dialog mode: a fresh plan or renaming an existing one. */
private enum class PlanNameMode { Create, Rename }

private data class PlanNameDialogState(
    val mode: PlanNameMode,
    val planId: String?,
    val initial: String,
)

@Composable
private fun PlanRow(
    row: PlanRowUi,
    isActive: Boolean,
    onOpen: () -> Unit,
    onSetCurrent: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val plan = row.plan

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_row_${plan.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = plan.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isActive) {
                        ReviewBadge(text = "当前计划")
                    }
                }
                Text(
                    text = buildString {
                        append(if (plan.isBuiltIn) "内置 · " else "自建 · ")
                        append("${row.packCount} 个词库")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
            }

            TextButton(onClick = onOpen) {
                Text("词库", style = MaterialTheme.typography.labelLarge)
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作：${plan.name}",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("查看词库") },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text("设为当前计划") },
                    onClick = {
                        menuOpen = false
                        onSetCurrent()
                    },
                    enabled = !isActive,
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                if (!plan.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One plan's packs: create, rename, delete, move between plans, import into a pack.
 *
 * The「当前计划」badge lives on the header, so a pack row carries no "active" state — switching
 * is a plan-level action (schema v4, D1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(
    container: AppContainer,
    planId: String,
    onBack: () -> Unit,
    onOpenImport: (String) -> Unit,
) {
    val viewModel: PlanDetailViewModel =
        viewModel(factory = ViewModelFactories.planDetail(container, planId))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<PackNameDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<WordPackEntity?>(null) }
    var pendingMove by remember { mutableStateOf<WordPackEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.planName.isBlank()) "计划" else state.planName) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "plan_header") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.planName.isBlank()) "计划" else state.planName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (state.planId == state.activePlanId) {
                                ReviewBadge(text = "当前计划")
                            }
                        }
                        Text(
                            text = "本计划共 ${state.packs.size} 个词库。学习、复习、考试与统计按整个计划汇总，计划内重复的单词只算一次、共用一份进度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        if (state.planId != state.activePlanId) {
                            SecondaryButton(
                                text = "设为当前计划",
                                onClick = viewModel::activate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("plan_set_current"),
                            )
                        }
                    }
                }
            }

            item(key = "pack_create_panel") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("新建词库", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "在这个计划下新建一个词库，再粘贴或导入单词；词库只是内容来源，学习范围始终是整个计划。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        PrimaryButton(
                            text = "新建词库",
                            onClick = {
                                nameDialog = PackNameDialogState(
                                    mode = PackNameMode.Create,
                                    packId = null,
                                    initial = "",
                                )
                            },
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("packs_create"),
                        )
                    }
                }
            }

            if (state.packs.isEmpty()) {
                item {
                    EmptyState(
                        title = "这个计划里还没有词库",
                        description = "新建词库并导入单词后，这个计划就有内容可以学习了。",
                        actionLabel = "新建词库",
                        onAction = {
                            nameDialog = PackNameDialogState(
                                mode = PackNameMode.Create,
                                packId = null,
                                initial = "",
                            )
                        },
                    )
                }
            }

            items(state.packs, key = { it.id }) { pack ->
                PackRow(
                    pack = pack,
                    onImport = { onOpenImport(pack.id) },
                    onMove = { pendingMove = pack },
                    onRename = {
                        nameDialog = PackNameDialogState(
                            mode = PackNameMode.Rename,
                            packId = pack.id,
                            initial = pack.name,
                        )
                    },
                    onDelete = { pendingDelete = pack },
                )
            }
        }
    }

    nameDialog?.let { dialog ->
        NameDialog(
            title = if (dialog.mode == PackNameMode.Create) "新建词库" else "重命名词库",
            label = "词库名称",
            fieldTag = "pack_name_field",
            initialName = dialog.initial,
            onConfirm = { name ->
                when (dialog.mode) {
                    PackNameMode.Create -> viewModel.createPack(name, onCreated = onOpenImport)
                    PackNameMode.Rename -> dialog.packId?.let { viewModel.renamePack(it, name) }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }

    pendingDelete?.let { pack ->
        ConfirmDialog(
            title = "删除词库「${pack.name}」？",
            message = "词库中的 ${pack.wordCount} 个单词以及它们的学习进度、错题记录、考试记录会一并删除，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deletePack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingMove?.let { pack ->
        MovePackDialog(
            packName = pack.name,
            targets = state.otherPlans,
            onMove = { targetPlanId ->
                viewModel.movePackTo(pack.id, targetPlanId)
                pendingMove = null
            },
            onDismiss = { pendingMove = null },
        )
    }
}

/** Dialog mode: a fresh pack or renaming an existing one. */
private enum class PackNameMode { Create, Rename }

private data class PackNameDialogState(
    val mode: PackNameMode,
    val packId: String?,
    val initial: String,
)

@Composable
private fun PackRow(
    pack: WordPackEntity,
    onImport: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pack_row_${pack.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(if (pack.isBuiltIn) "内置 · " else "自定义 · ")
                        append(CetLevel.fromId(pack.level).shortLabel)
                        append(" · ${pack.wordCount} 词")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
            }

            TextButton(onClick = onImport) {
                Text("导入", style = MaterialTheme.typography.labelLarge)
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作：${pack.name}",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("导入单词") },
                    onClick = {
                        menuOpen = false
                        onImport()
                    },
                )
                DropdownMenuItem(
                    text = { Text("移动到其他计划") },
                    onClick = {
                        menuOpen = false
                        onMove()
                    },
                )
                if (!pack.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Target picker of「移动到其他计划」; the destination list never includes the current plan. */
@Composable
private fun MovePackDialog(
    packName: String,
    targets: List<PlanEntity>,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动词库「$packName」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (targets.isEmpty()) {
                    Text(
                        text = "没有其他计划可以移动。先新建一个计划，再回来移动词库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                } else {
                    Text(
                        text = "选择目标计划：单词与学习进度会一起移动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                    targets.forEach { target ->
                        TextButton(onClick = { onMove(target.id) }) {
                            Text(target.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Create / rename dialog with inline blank-name validation, shared by plans and packs. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    fieldTag: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val tooLong = trimmed.length > MAX_PACK_NAME_FIELD_LENGTH

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                isError = trimmed.isEmpty() || tooLong,
                supportingText = {
                    when {
                        tooLong -> Text("名称过长，最多 $MAX_PACK_NAME_FIELD_LENGTH 个字符")
                        trimmed.isEmpty() -> Text("名称不能为空")
                        else -> Text("${name.length} / $MAX_PACK_NAME_FIELD_LENGTH")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (trimmed.isNotEmpty() && !tooLong) onConfirm(trimmed)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(fieldTag),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !tooLong,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Mirrors the repository's name cap so the dialog can validate before a round-trip. */
private const val MAX_PACK_NAME_FIELD_LENGTH = 40

// ---------------------------------------------------------------------------------------------
// Import words (M3)
// ---------------------------------------------------------------------------------------------

/**
 * Paste plain text → live preview → import (plan §6.2).
 *
 * The preview counts ("可导入 N · 过滤 M · 重复 K") are produced by the pure
 * `PlainTextWordParser` on every change, so nothing is written until the learner confirms;
 * the write itself needs no network — meanings are completed later by the gateway (M4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWordsScreen(
    container: AppContainer,
    packId: String,
    onBack: () -> Unit,
) {
    val viewModel: ImportWordsViewModel =
        viewModel(factory = ViewModelFactories.importWords(container, packId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.packName.isBlank()) "导入单词" else "导入单词 · ${state.packName}") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val result = state.lastResult
            if (result != null) {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("导入完成", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "新增 ${result.inserted} · 跳过 ${result.skipped}（共提交 ${result.total} 个）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "单词已入库，释义与音标稍后自动补全。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                PrimaryButton(
                    text = "继续导入",
                    onClick = {
                        text = ""
                        viewModel.resetForMore()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_continue"),
                )
                SecondaryButton(
                    text = "完成",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AccentPanel {
                    Text(
                        text = "粘贴任意包含英文单词的文本：逗号、分号、空格或换行分隔均可；中文、数字、网址等无效内容会被自动过滤。导入不需要联网。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        viewModel.onTextChanged(it)
                    },
                    label = { Text("在这里粘贴单词") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_text_field"),
                )

                val preview = state.preview
                val parseError = state.parseError
                when {
                    parseError != null -> Text(
                        text = parseError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("import_parse_error"),
                    )

                    preview != null -> Text(
                        text = "可导入 ${preview.importableCount} · 过滤 ${preview.dropped} · 重复 ${preview.duplicates}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                        modifier = Modifier.testTag("import_preview_counts"),
                    )

                    else -> Text(
                        text = "等待粘贴内容…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                    )
                }

                state.message?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                PrimaryButton(
                    text = "导入",
                    onClick = viewModel::importWords,
                    enabled = preview != null && !preview.isEmpty,
                    loading = state.importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_confirm"),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Write word (手写录词) — the「写词库」tab (plan §B / §D)
// ---------------------------------------------------------------------------------------------

/**
 * The inline status slot under the「写入」button reserves this many `bodySmall` lines: one for
 * the usual message, a second so a longer error can wrap without moving anything around it.
 */
private const val InlineStatusLines = 2

/**
 * Hand-type the words met while reading, one at a time, with an optional Chinese note.
 *
 * The note is the answer to "the dictionary meaning is not what I need here": it is stored in
 * `user_note` and shown **before** the looked-up meanings on the study card (see
 * `WordCard.WordMeaningBlock`). A word left without a note is completed by the gateway exactly
 * like an imported word — this screen therefore stays fully offline until the gateway runs.
 *
 * Two modes share one ViewModel and one screen (`AnimatedContent`, no extra navigation route):
 * the form, and the full-screen gateway picker opened by tapping the word field. The picker only
 * fills the word — the write itself keeps its original semantics (identity, note, lazy pack).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteWordScreen(
    container: AppContainer,
) {
    val viewModel: WriteWordViewModel =
        viewModel(factory = ViewModelFactories.writeWord(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val motion = AppTheme.motion

    AnimatedContent(
        targetState = state.searchVisible,
        transitionSpec = {
            val duration = motion.durationMillis(WriteWordTransitionMillis)
            if (targetState) {
                // Entering the picker: slide in from the reading direction, form slides back.
                (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 16 })
                    .togetherWith(
                        fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { -it / 16 },
                    )
            } else {
                // Returning to the form: the same axis, reversed.
                (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { -it / 16 })
                    .togetherWith(
                        fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 16 },
                    )
            }
        },
        label = "write-word-mode",
        modifier = Modifier.fillMaxSize(),
    ) { searching ->
        if (searching) {
            WordSearchMode(
                query = state.searchQuery,
                hits = state.searchHits,
                loading = state.searchLoading,
                error = state.searchError,
                errorIsConfig = state.searchErrorIsConfig,
                settled = state.searchSettled,
                onQueryChange = viewModel::onSearchQueryChanged,
                onClear = viewModel::clearSearchQuery,
                onBack = viewModel::closeSearch,
                onRetry = viewModel::retrySearch,
                onSelect = viewModel::selectSearchHit,
                onUseTyped = viewModel::useTypedWord,
            )
        } else {
            WriteWordForm(
                state = state,
                onOpenSearch = viewModel::openSearch,
                onSelectPack = viewModel::selectPack,
                onCreatePack = viewModel::createPack,
                onNoteChanged = viewModel::onNoteChanged,
                onWrite = viewModel::write,
            )
        }
    }
}

/** One shared-axis pass between the form and the picker (disabled with the app's motion setting). */
private const val WriteWordTransitionMillis = 220

@Composable
private fun WriteWordForm(
    state: WriteWordUiState,
    onOpenSearch: () -> Unit,
    onSelectPack: (String) -> Unit,
    onCreatePack: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onWrite: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    // Fixed status-slot height, derived from the typography line height: the line height is in
    // sp, and `toDp()` folds the system font scale in, so large-font users get a taller fixed
    // slot instead of clipped glyphs. The height still never depends on the message itself, so
    // the "appears without moving anything" guarantee is kept.
    val statusSlotHeight = with(LocalDensity.current) {
        (MaterialTheme.typography.bodySmall.lineHeight * InlineStatusLines.toFloat()).toDp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("写词库") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "write_location") {
                WriteLocationCard(
                    state = state,
                    onSelectPack = onSelectPack,
                    onCreatePack = { showCreateDialog = true },
                )
            }

            item(key = "write_input") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        // The word itself is never typed here: all text entry happens in the
                        // picker, so the form only shows the chosen word and an entry point.
                        WordPickerField(word = state.wordInput, onOpenSearch = onOpenSearch)
                        OutlinedTextField(
                            value = state.noteInput,
                            onValueChange = onNoteChanged,
                            label = { Text("我的释义 / 注释（可选，中文）") },
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onWrite() }),
                            supportingText = {
                                Text("填写后会优先展示，覆盖词典里不符合语境的意思。")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_note_field"),
                        )
                        PrimaryButton(
                            text = "写入",
                            onClick = onWrite,
                            enabled = state.wordInput.isNotBlank() && !state.saving,
                            loading = state.saving,
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_submit"),
                        )
                        // Fixed-height status slot: the confirmation / error line appears and
                        // disappears inside a box that is always laid out at the same height, so
                        // nothing around it moves (no snackbar covering the form, no jump when
                        // the message changes).
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(statusSlotHeight),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val message = state.message
                            if (message != null) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = InlineStatusLines,
                                    // Long messages truncate with dots instead of hard-clipping
                                    // half a glyph.
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (state.messageIsError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        AppTheme.colors.mutedText
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "write_written_header") {
                SectionHeader(
                    title = if (state.sessionCount > 0) {
                        "本次已写入 · ${state.sessionCount} 词"
                    } else {
                        "本次已写入"
                    },
                )
            }

            if (state.written.isEmpty()) {
                item(key = "write_written_empty") {
                    EmptyState(
                        title = "还没有写入单词",
                        description = "点击上方的单词框搜索并选择单词，可附上自己的中文释义；写入后会立即出现在这里。",
                    )
                }
            } else {
                items(state.written, key = { it.wordId }) { entry ->
                    WrittenWordRow(
                        entry = entry,
                        modifier = Modifier.testTag("write_written_${entry.wordId}"),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        NameDialog(
            title = "新建词库",
            label = "词库名称",
            fieldTag = "write_new_pack_name",
            initialName = "",
            onConfirm = { name ->
                showCreateDialog = false
                onCreatePack(name)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/**
 * 「写入位置」: the read-only plan, the target pack dropdown, and the in-card「新建」action.
 *
 * A dropdown instead of chips because the number of packs grows with every import, while the
 * row must keep a fixed height; the picker stays paired with the create button on its right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WriteLocationCard(
    state: WriteWordUiState,
    onSelectPack: (String) -> Unit,
    onCreatePack: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (state.targetPackId.isEmpty()) {
        "${PlanIds.WRITE_PACK_NAME}（默认）"
    } else {
        state.packs.firstOrNull { it.id == state.targetPackId }?.name ?: state.targetPackId
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(text = "写入位置", style = MaterialTheme.typography.titleSmall)

            // The plan is read-only context here: switching plans belongs to the plan screen.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = "所属计划",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
                Text(
                    text = state.planName.ifBlank { state.planId },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("写入词库") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("write_pack_dropdown"),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("${PlanIds.WRITE_PACK_NAME}（默认）") },
                            onClick = {
                                expanded = false
                                onSelectPack("")
                            },
                        )
                        state.packs.forEach { pack ->
                            DropdownMenuItem(
                                text = { Text(pack.name) },
                                onClick = {
                                    expanded = false
                                    onSelectPack(pack.id)
                                },
                            )
                        }
                    }
                }
                SecondaryButton(
                    text = "新建",
                    onClick = onCreatePack,
                    leadingIcon = Icons.Filled.Add,
                    modifier = Modifier.testTag("write_new_pack"),
                )
            }

            Text(
                text = "不选则写入计划自带的「${PlanIds.WRITE_PACK_NAME}」，也可以写进任意已有词库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Read-only word row; tapping it opens the full-screen picker (the only text-input surface). */
@Composable
private fun WordPickerField(
    word: String,
    onOpenSearch: () -> Unit,
) {
    Surface(
        onClick = onOpenSearch,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, AppTheme.colors.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("write_word_field"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = "单词",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
                Text(
                    text = word.ifBlank { "点击搜索并选择单词" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (word.isBlank()) {
                        AppTheme.colors.mutedText
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppTheme.colors.mutedText,
            )
        }
    }
}

/**
 * Full-screen picker over the gateway's `/dict` suggestions.
 *
 * Offline is not a dead end: the「直接使用」action is always available for a non-blank query, and
 * the form never depends on a search having succeeded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordSearchMode(
    query: String,
    hits: List<DictionaryHit>,
    loading: Boolean,
    error: String?,
    errorIsConfig: Boolean,
    settled: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (DictionaryHit) -> Unit,
    onUseTyped: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler {
        // First press clears the text, the next leaves the picker — never pops the page.
        if (query.isNotEmpty()) {
            onClear()
        } else {
            keyboard?.hide()
            onBack()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        keyboard?.hide()
                        onBack()
                    },
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("输入单词的前几个字母") },
                    singleLine = true,
                    // Ascii keeps mainstream Chinese IMEs on a plain English layout, so the
                    // letters stay visible while typing (same reasoning as the old word field).
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Search,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("write_search_field"),
                )
            }
            HorizontalDivider(color = AppTheme.colors.hairline)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    query.isBlank() -> Text(
                        text = "输入单词的前几个字母进行搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                        modifier = Modifier.testTag("write_search_guide"),
                    )

                    loading -> CircularProgressIndicator(
                        modifier = Modifier.testTag("write_search_loading"),
                    )

                    error != null -> SearchErrorState(
                        message = error,
                        showRetry = !errorIsConfig,
                        onRetry = onRetry,
                    )

                    settled && hits.isEmpty() -> Text(
                        text = "没有匹配单词，可直接使用当前输入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = Spacing.xl)
                            .testTag("write_search_empty"),
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(hits, key = { hit -> hit.word }) { hit ->
                            DictionaryHitRow(
                                hit = hit,
                                onClick = {
                                    keyboard?.hide()
                                    onSelect(hit)
                                },
                            )
                            HorizontalDivider(color = AppTheme.colors.hairline)
                        }
                    }
                }
            }

            if (query.isNotBlank()) {
                SecondaryButton(
                    text = "直接使用「${query.trim()}」",
                    onClick = {
                        keyboard?.hide()
                        onUseTyped()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                        .testTag("write_search_manual"),
                )
            }
        }
    }
}

@Composable
private fun SearchErrorState(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(horizontal = Spacing.xl),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (showRetry) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag("write_search_retry"),
            ) { Text("重试") }
        }
    }
}

/** One gateway candidate: word + phonetic on the first line, Chinese meaning on the second. */
@Composable
private fun DictionaryHitRow(
    hit: DictionaryHit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .testTag("write_search_hit_${hit.word}"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = hit.word,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.phonetic.isNotBlank()) {
                Text(
                    text = hit.phonetic,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hit.translation.isNotBlank()) {
            Text(
                text = hit.translation,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.mutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** One row of "本次已写入": the word on the left, note-or-meaning on the right. */
@Composable
private fun WrittenWordRow(
    entry: WrittenWordEntry,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = entry.word,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.secondaryText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                // A note is the learner's own wording and must stay distinguishable from the
                // dictionary meaning (which is why it is never shown alongside it).
                color = if (entry.secondaryIsNote) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    AppTheme.colors.mutedText
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
