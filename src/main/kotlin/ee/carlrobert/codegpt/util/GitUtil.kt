package ee.carlrobert.codegpt.util

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import ee.carlrobert.codegpt.codecompletions.truncateText
import git4idea.GitCommit
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.StringWriter
import kotlin.Throws

object GitUtil {

    private val logger = thisLogger()

    data class RepositoryCommit(
        val repository: GitRepository,
        val commit: GitCommit
    )

    @JvmStatic
    fun getProjectRepositories(project: Project): List<GitRepository> {
        val repositoryManager = project.service<GitRepositoryManager>()
        return try {
            val primaryRepository = project.guessProjectDir()?.let {
                repositoryManager.getRepositoryForFile(it)
            }
            val primaryRepositoryRootPath = primaryRepository?.root?.path

            buildList {
                primaryRepository?.let { add(it) }
                addAll(repositoryManager.repositories.filter { it.root.path != primaryRepositoryRootPath })
            }
        } catch (e: Exception) {
            logger.warn("Failed to get git repositories", e)
            repositoryManager.repositories
        }
    }

    @Throws(VcsException::class)
    @JvmStatic
    fun getProjectRepository(project: Project): GitRepository? {
        return getProjectRepositories(project).firstOrNull()
    }

    @JvmStatic
    fun getRepositoryForRoot(project: Project, repositoryRootPath: String): GitRepository? {
        return getProjectRepositories(project).firstOrNull { it.root.path == repositoryRootPath }
    }

    @JvmStatic
    fun getRepositoryDisplayPath(project: Project, repository: GitRepository): String {
        val projectDir = project.guessProjectDir()
        return if (projectDir != null) {
            VfsUtil.getRelativePath(repository.root, projectDir)
                ?.takeIf { it.isNotBlank() }
                ?: repository.root.name
        } else {
            repository.root.path
        }
    }

    fun getCurrentChanges(project: Project): String? {
        try {
            val repositories = getProjectRepositories(project)
            if (repositories.isEmpty()) {
                return null
            }

            val changes = ChangeListManager.getInstance(project).allChanges
                .filter { change ->
                    change.virtualFile?.let { !it.fileType.isBinary } ?: false
                }

            val includeRepositoryLabels = repositories.size > 1
            return repositories.mapNotNull { repository ->
                buildRepositoryDiff(
                    project,
                    repository,
                    changes.filter { belongsToRepository(it, repository) }
                )?.takeIf { it.isNotBlank() }?.let { diff ->
                    if (includeRepositoryLabels) {
                        "# Repository: ${getRepositoryDisplayPath(project, repository)}\n$diff"
                    } else {
                        diff
                    }
                }
            }
                .joinToString("\n\n")
                .truncateText(16_000, true)
        } catch (e: VcsException) {
            logger.error("Failed to get git context", e)
            return null
        }
    }

    @Throws(VcsException::class)
    fun getCommitsForHashes(
        project: Project,
        repository: GitRepository,
        commitHashes: List<String>
    ): List<GitCommit> {
        val result = mutableListOf<GitCommit>()

        GitHistoryUtils
            .loadDetails(project, repository.root, { commit ->
                if (commitHashes.contains(commit.id.asString())) {
                    result.add(commit)
                }
            })

        return result
    }

    @Throws(VcsException::class)
    fun getCommitsForHashes(
        project: Project,
        commitHashes: List<String>
    ): List<RepositoryCommit> {
        if (commitHashes.isEmpty()) {
            return emptyList()
        }

        val matchingCommits = mutableMapOf<String, MutableList<RepositoryCommit>>()

        getProjectRepositories(project).forEach { repository ->
            getCommitsForHashes(project, repository, commitHashes)
                .forEach { commit ->
                    matchingCommits.getOrPut(commit.id.asString()) { mutableListOf() }
                        .add(RepositoryCommit(repository, commit))
                }
        }

        return commitHashes.flatMap { matchingCommits[it].orEmpty() }
    }

    @Throws(VcsException::class)
    fun getCommitDiffs(
        project: Project,
        gitRepository: GitRepository,
        commitHash: String
    ): List<String> {
        val handler = GitLineHandler(project, gitRepository.root, GitCommand.SHOW)
        handler.addParameters(
            commitHash,
            "--unified=2",
            "--no-prefix",
            "--no-color"
        )

        val commandResult = Git.getInstance().runCommand(handler)
        return filterDiffOutput(commandResult.output)
    }

    @Throws(VcsException::class)
    fun visitRepositoryCommits(
        project: Project,
        repository: GitRepository,
        onVisit: (GitCommit) -> Unit
    ) {
        try {
            GitHistoryUtils.loadDetails(project, repository.root, { onVisit(it) })
        } catch (e: VcsException) {
            logger.error("Error fetching commit history: {}", e.message)
        }
    }

    @Throws(VcsException::class)
    fun getAllRecentCommits(
        project: Project,
        searchText: String? = "",
        limit: Int = 250
    ): List<RepositoryCommit> {
        return getProjectRepositories(project)
            .flatMap { repository ->
                getAllRecentCommits(project, repository, searchText, limit)
                    .map { RepositoryCommit(repository, it) }
            }
            .sortedByDescending { it.commit.commitTime }
            .take(limit)
    }

    @Throws(VcsException::class)
    fun getAllRecentCommits(
        project: Project,
        repository: GitRepository,
        searchText: String? = "",
        limit: Int = 250
    ): List<GitCommit> {
        val result = mutableListOf<GitCommit>()

        try {
            GitHistoryUtils
                .loadDetails(project, repository.root, { commit ->
                    if (searchText.isNullOrEmpty()) {
                        result.add(commit)
                    } else {
                        if (commit.id.asString().contains(searchText, true)
                            || commit.fullMessage.contains(searchText, true)
                        ) {
                            result.add(commit)
                        }
                    }
                }, "-n", "$limit")
        } catch (e: VcsException) {
            logger.error("Error fetching commit history: {}", e.message)
        }

        return result
    }

    private fun filterDiffOutput(output: List<String>): List<String> {
        return output.filter {
            !it.startsWith("diff --git") &&
                    !it.startsWith("index ") &&
                    !it.startsWith("---") &&
                    !it.startsWith("+++") &&
                    !it.startsWith("- ") &&
                    !it.startsWith("commit ")
        }
    }

    private fun buildRepositoryDiff(
        project: Project,
        repository: GitRepository,
        changes: List<Change>
    ): String? {
        if (changes.isEmpty()) {
            return null
        }

        val repositoryRootPath = repository.root.toNioPath()
        val patches = IdeaTextPatchBuilder.buildPatch(
            project,
            changes,
            repositoryRootPath,
            false,
            true
        ).sortedByDescending { patch ->
            patch.afterVersionId?.let {
                it.substringAfter("(date ")
                    .substringBefore(")")
                    .toLongOrNull() ?: 0L
            } ?: 0L
        }

        val diffWriter = StringWriter()
        UnifiedDiffWriter.write(
            null,
            repositoryRootPath,
            patches,
            diffWriter,
            "\n\n",
            null,
            null
        )
        return diffWriter.toString()
    }

    private fun belongsToRepository(change: Change, repository: GitRepository): Boolean {
        val path = change.virtualFile?.path
            ?: change.afterRevision?.file?.path
            ?: change.beforeRevision?.file?.path
            ?: return false

        return FileUtil.isAncestor(repository.root.path, path, false)
    }
}
