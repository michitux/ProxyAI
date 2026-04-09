package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.IncludeCurrentChangesActionItem
import ee.carlrobert.codegpt.util.GitUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.Icon

class GitGroupItem(private val project: Project) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.git.displayName")
    override val icon: Icon = Icons.VCS

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        withContext(Dispatchers.Default) {
            GitUtil.getAllRecentCommits(project, searchText, 50).forEach { repositoryCommit ->
                runInEdt {
                    LookupUtil.addLookupItem(
                        lookup,
                        GitCommitActionItem(project, repositoryCommit),
                        searchText = searchText
                    )
                }
            }
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return withContext(Dispatchers.Default) {
            val recentCommits = GitUtil.getAllRecentCommits(project, searchText, 10)
                .map { repositoryCommit -> GitCommitActionItem(project, repositoryCommit) }
            listOf(IncludeCurrentChangesActionItem()) + recentCommits
        }
    }
}
