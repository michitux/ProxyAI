package testsupport

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import git4idea.GitVcs
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert
import java.nio.file.Files
import java.nio.file.Path

open class VcsTestCase : HeavyPlatformTestCase() {

    protected lateinit var projectDir: Path

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        projectDir = tempDir.createDir()
    }

    fun git(command: GitCommand, parameters: List<String> = emptyList()) {
        git(projectDir, command, parameters)
    }

    fun git(repositoryDir: Path, command: GitCommand, parameters: List<String> = emptyList()) {
        val checkoutHandler = GitLineHandler(project, repositoryDir.toFile(), command)
        checkoutHandler.addParameters(parameters)
        service<Git>().runCommand(checkoutHandler).throwOnError()
    }

    fun registerRepository(): GitRepository =
        registerRepositories(projectDir).first()

    fun registerRepositories(vararg repositoryDirs: Path): List<GitRepository> =
        ProjectLevelVcsManager.getInstance(project).run {
            directoryMappings = repositoryDirs.map { VcsDirectoryMapping(it.toString(), GitVcs.NAME) }
            repositoryDirs.forEach { Files.createDirectories(it) }
            Assert.assertFalse(
                "There are no VCS roots. Active VCSs: $allActiveVcss",
                allVcsRoots.isEmpty()
            )

            runBlocking(Dispatchers.IO) {
                repositoryDirs.map { repositoryDir ->
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repositoryDir)
                    val repository = project.service<GitRepositoryManager>().getRepositoryForRoot(file)
                    assertThat(repository).describedAs("Couldn't find repository for root $repositoryDir")
                        .isNotNull()
                    repository!!
                }
            }
        }
}
