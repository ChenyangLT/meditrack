package com.meditrack.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meditrack.R
import com.meditrack.core.theme.prefs

/**
 * 知识 -> 基础知识库.
 *
 * A purely offline reference: the whole corpus lives in [KnowledgeContent] inside the APK, there is
 * no network call, no link and no account, so the screen works in a hospital corridor with no signal.
 *
 * ## The reading order is deliberate
 *
 * At the top sits the emergency card, because the one reader who needs it will not search for it. The
 * disclaimer comes next, before any content, so it is read rather than scrolled past. Only then do the
 * categories appear, each collapsed - the screen is a table of contents first and a document second,
 * which is the difference between "I'll look it up later" and actually finding the answer.
 *
 * ## Why the reader is a dialog
 *
 * An article opens over the whole window rather than as a route in the NavHost: it belongs to this
 * screen's state, the bottom bar must not become a way to navigate away mid-sentence, and a dialog
 * gives back-press dismissal for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen() {
    var query by remember { mutableStateOf("") }
    var openArticle by remember { mutableStateOf<KnowledgeArticle?>(null) }
    // Which category headers are open. Held here rather than inside the card so the state survives
    // the item being recycled while the user scrolls.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // The emergency article is pinned above the list, so it is left out of its own category to avoid
    // showing the same title twice on one screen.
    val emergency = remember {
        KnowledgeContent.articles.firstOrNull { it.id == KnowledgeContent.EMERGENCY_ARTICLE_ID }
    }
    val sections = remember {
        KnowledgeContent.grouped(
            KnowledgeContent.articles.filter { it.id != KnowledgeContent.EMERGENCY_ARTICLE_ID }
        )
    }

    val searching = query.isNotBlank()
    val results = remember(query) { KnowledgeContent.search(query) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.knowledge_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchField(query = query, onQueryChange = { query = it })

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("knowledge_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!searching) {
                    if (emergency != null) {
                        item(key = "pinned_emergency") {
                            EmergencyCard(article = emergency, onClick = { openArticle = emergency })
                        }
                    }
                    item(key = "disclaimer") { DisclaimerCard() }
                    sections.forEach { (category, categoryArticles) ->
                        item(key = category.name) {
                            CategoryCard(
                                category = category,
                                articles = categoryArticles,
                                expanded = expanded[category.name] == true,
                                onToggle = { expanded[category.name] = !(expanded[category.name] ?: false) },
                                onOpen = { openArticle = it },
                            )
                        }
                    }
                } else if (results.isEmpty()) {
                    item(key = "empty_result") { EmptyResult() }
                } else {
                    items(results, key = { it.id }) { article ->
                        SearchResultRow(article = article, onClick = { openArticle = article })
                    }
                    // The flat result list still has to close with the same caveat as a category.
                    item(key = "search_footer") {
                        Text(
                            text = KnowledgeContent.CATEGORY_FOOTER,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    openArticle?.let { article ->
        KnowledgeArticleScreen(article = article, onDismiss = { openArticle = null })
    }
}

/**
 * The search box.
 *
 * Single-line and always visible: the corpus is 29 articles, and typing two characters is faster than
 * expanding five categories. The clear button only exists while there is something to clear.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(R.string.knowledge_search_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.knowledge_search_clear),
                    )
                }
            }
        },
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
    )
}

/**
 * The pinned 紧急情况 card.
 *
 * The only card on the screen painted in `errorContainer`: if someone opens this tab while something
 * is wrong, the first thing they see has to be the thing that matters, not a table of contents.
 */
@Composable
private fun EmergencyCard(article: KnowledgeArticle, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.knowledge_emergency_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = article.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.knowledge_emergency_card_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(
                    R.string.knowledge_article_open,
                    article.title,
                ),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The one prominent disclaimer, above every article and every category. */
@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(R.string.knowledge_disclaimer_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = KnowledgeContent.DISCLAIMER,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.knowledge_offline_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One collapsible category.
 *
 * The whole header row is the target, not the chevron: the chevron is a 24dp square, and a header
 * that only responds when you hit the arrow is the classic way a list feels broken.
 */
@Composable
private fun CategoryCard(
    category: KnowledgeCategory,
    articles: List<KnowledgeArticle>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (KnowledgeArticle) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconFor(category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.knowledge_section_count, articles.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = category.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = stringResource(
                        if (expanded) R.string.knowledge_collapse else R.string.knowledge_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                articles.forEach { article ->
                    ArticleTitleRow(article = article, onClick = { onOpen(article) })
                }
                // Every category closes with the caveat, whatever it contains.
                Text(
                    text = KnowledgeContent.CATEGORY_FOOTER,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** One article title inside an expanded category. */
@Composable
private fun ArticleTitleRow(article: KnowledgeArticle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = article.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = stringResource(R.string.knowledge_article_open, article.title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One hit in the flat search result list, captioned with its category. */
@Composable
private fun SearchResultRow(article: KnowledgeArticle, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = article.category.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = article.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown when a query matches nothing, with a nudge towards words that do exist in the corpus. */
@Composable
private fun EmptyResult() {
    Column(modifier = Modifier.padding(vertical = 32.dp)) {
        Text(
            text = stringResource(R.string.knowledge_search_empty),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.knowledge_search_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One article, opened over the whole window.
 *
 * Rendered in a dialog without a platform width so it truly covers the screen - including the bottom
 * navigation bar - and dismissal costs the user nothing: back, the arrow, or a tap outside all close
 * it. The body is a lazy list so it always starts at the top on open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeArticleScreen(article: KnowledgeArticle, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        // The category, not the title: the title is long and is rendered in full
                        // below, where it may wrap instead of being cut off.
                        title = { Text(article.category.label) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.knowledge_back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    ArticleBody(article = article, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The body renderer, shared by every article.
 *
 * The conventions are the same ones the first-run documents use in `DocumentReader`: "## " is a
 * sub-heading, "- " is a bullet, everything else is a paragraph. [KnowledgeContent.ARTICLE_FOOTER] is
 * appended here, once, rather than written into 29 bodies - which is also why it cannot be forgotten
 * when an article is added later.
 */
@Composable
private fun ArticleBody(article: KnowledgeArticle, modifier: Modifier = Modifier) {
    val lines = remember(article.id) {
        article.body.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "article_title") {
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item(key = "article_summary") {
            Text(
                text = article.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "article_divider") {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        items(count = lines.size) { index -> BodyLine(lines[index]) }

        item(key = "article_footer") {
            Text(
                text = KnowledgeContent.ARTICLE_FOOTER,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** One line of an article body, in the same three shapes the documents use. */
@Composable
private fun BodyLine(line: String) {
    when {
        line.startsWith("## ") -> Text(
            text = line.removePrefix("## "),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        line.startsWith("# ") -> Text(
            text = line.removePrefix("# "),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        line.startsWith("- ") -> Row {
            Text("• ", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = line.removePrefix("- "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        else -> Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The icon of a category.
 *
 * Kept in the screen rather than on the enum so the content file stays plain Kotlin with no Compose
 * types in it, and so an icon can be changed without touching 29 articles.
 */
private fun iconFor(category: KnowledgeCategory): ImageVector = when (category) {
    KnowledgeCategory.TIMING -> Icons.Filled.Schedule
    KnowledgeCategory.MISSED_DOSE -> Icons.Filled.HourglassEmpty
    KnowledgeCategory.INTERACTION -> Icons.Filled.Medication
    KnowledgeCategory.STORAGE -> Icons.Filled.Inventory2
    KnowledgeCategory.MYTHS -> Icons.Filled.ErrorOutline
    KnowledgeCategory.LEAFLET -> Icons.Filled.Description
    KnowledgeCategory.EMERGENCY -> Icons.Filled.Warning
    KnowledgeCategory.PREPARATION -> Icons.Filled.MedicalServices
}
