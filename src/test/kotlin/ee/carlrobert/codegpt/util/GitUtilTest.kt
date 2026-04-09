package ee.carlrobert.codegpt.util

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.GitCommand
import org.assertj.core.api.Assertions.assertThat
import testsupport.VcsTestCase
import java.nio.file.Files
import java.nio.file.Path

class GitUtilTest : VcsTestCase() {

    fun `test get project repositories returns all vcs roots`() {
        val firstRepositoryDir = initializeRepository("repo-one", "first.txt", "first repository commit")
        val secondRepositoryDir = initializeRepository("repo-two", "second.txt", "second repository commit")

        registerRepositories(firstRepositoryDir, secondRepositoryDir)

        val repositories = GitUtil.getProjectRepositories(project)

        assertThat(repositories.map { it.root.path })
            .containsExactlyInAnyOrder(
                firstRepositoryDir.toString(),
                secondRepositoryDir.toString()
            )
    }

    fun `test get all recent commits aggregates commits from all vcs roots`() {
        val firstRepositoryDir = initializeRepository("repo-one", "first.txt", "first repository commit")
        val secondRepositoryDir = initializeRepository("repo-two", "second.txt", "second repository commit")

        registerRepositories(firstRepositoryDir, secondRepositoryDir)

        val commits = GitUtil.getAllRecentCommits(project, limit = 10)

        assertThat(commits.map { it.commit.subject })
            .contains("first repository commit", "second repository commit")
        assertThat(commits.map { it.repository.root.path })
            .contains(firstRepositoryDir.toString(), secondRepositoryDir.toString())
    }

    fun `test get current changes aggregates diffs from all vcs roots`() {
        val firstRepositoryDir = initializeRepository("repo-one", "first.txt", "first repository commit")
        val secondRepositoryDir = initializeRepository("repo-two", "second.txt", "second repository commit")

        registerRepositories(firstRepositoryDir, secondRepositoryDir)

        Files.writeString(firstRepositoryDir.resolve("first.txt"), "first repository change\n")
        Files.writeString(secondRepositoryDir.resolve("second.txt"), "second repository change\n")
        refreshChanges(firstRepositoryDir.resolve("first.txt"), secondRepositoryDir.resolve("second.txt"))

        val diff = GitUtil.getCurrentChanges(project)

        assertThat(diff).contains("first repository change")
        assertThat(diff).contains("second repository change")
    }

    private fun initializeRepository(
        repositoryName: String,
        fileName: String,
        commitMessage: String
    ): Path {
        val repositoryDir = projectDir.resolve(repositoryName)
        Files.createDirectories(repositoryDir)

        git(repositoryDir, GitCommand.INIT)
        git(repositoryDir, GitCommand.CONFIG, listOf("user.email", "proxyai@example.com"))
        git(repositoryDir, GitCommand.CONFIG, listOf("user.name", "ProxyAI Tests"))

        Files.writeString(repositoryDir.resolve(fileName), "$commitMessage\n")
        git(repositoryDir, GitCommand.ADD, listOf(fileName))
        git(repositoryDir, GitCommand.COMMIT, listOf("-m", commitMessage))

        return repositoryDir
    }

    private fun refreshChanges(vararg paths: Path) {
        paths.forEach { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        ChangeListManager.getInstance(project).invokeAfterUpdate(
            {},
            InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE,
            "Refresh VCS changes",
            ModalityState.defaultModalityState()
        )
    }
}
