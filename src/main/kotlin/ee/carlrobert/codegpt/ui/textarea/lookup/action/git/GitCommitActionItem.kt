package ee.carlrobert.codegpt.ui.textarea.lookup.action.git

import com.intellij.icons.AllIcons
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.GitCommitTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.InsertsDisplayNameLookupItem
import ee.carlrobert.codegpt.util.GitUtil

class GitCommitActionItem(
    private val project: Project,
    private val repositoryCommit: GitUtil.RepositoryCommit,
) : AbstractLookupActionItem(), InsertsDisplayNameLookupItem {

    val description: String = repositoryCommit.commit.id.asString().take(6)

    override val displayName: String = repositoryCommit.commit.subject
    override val icon = AllIcons.Vcs.CommitNode

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)
        presentation.typeText = GitUtil.getRepositoryDisplayPath(project, repositoryCommit.repository)
        presentation.isTypeGrayed = true
    }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(
            GitCommitTagDetails(
                repositoryCommit.commit.id.asString(),
                repositoryCommit.commit.fullMessage,
                repositoryCommit.repository.root.path
            )
        )
    }
}
