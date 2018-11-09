/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.ide.DataManager
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.util.Disposer
import org.rust.cargo.RustWithToolchainTestBase
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.test.CargoTestRunConfigurationProducer
import org.rust.stdext.removeLast

class TestRunnerTest : RustWithToolchainTestBase() {

    fun `test not executed tests are not shown`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    #[test]
                    fn test_should_pass() {}

                    #[test]
                    fn test_should_fail() {
                        /*caret*/
                        assert_eq!(1, 2)
                    }

                    #[test]
                    #[ignore]
                    fn test_ignored() {}
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        runTestsAndCheckTestTree("""
            [root](-)
            .sandbox(-)
            ..test_should_fail(-)
        """)
    }

    fun `test multiple failed tests`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    /*caret*/

                    #[test]
                    fn test_should_fail_1() {
                        assert_eq!(1, 2)
                    }

                    #[test]
                    fn test_should_fail_2() {
                        assert_eq!(2, 3)
                    }
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        runTestsAndCheckTestTree("""
            [root](-)
            .sandbox(-)
            ..test_should_fail_1(-)
            ..test_should_fail_2(-)
        """)
    }

    fun `test tests in submodules`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    #[cfg(test)]
                    mod suite_should_fail {
                        /*caret*/

                        #[test]
                        fn test_should_pass() {}

                        mod nested_suite_should_fail {
                            #[test]
                            fn test_should_pass() {}

                            #[test]
                            fn test_should_fail() { panic!(":(") }
                        }

                        mod nested_suite_should_pass {
                            #[test]
                            fn test_should_pass() {}
                        }
                    }
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        runTestsAndCheckTestTree("""
            [root](-)
            .sandbox(-)
            ..suite_should_fail(-)
            ...nested_suite_should_fail(-)
            ....test_should_fail(-)
            ....test_should_pass(+)
            ...nested_suite_should_pass(+)
            ....test_should_pass(+)
            ...test_should_pass(+)
        """)
    }

    fun `test test in custom bin target`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []

                [[bin]]
                name = "main"
                path = "src/main.rs"
            """)

            dir("src") {
                rust("main.rs", """
                    fn main() {}

                    #[test]
                    fn test_should_pass() { /*caret*/ }
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        runTestsAndCheckTestTree("""
            [root](+)
            .main(+)
            ..test_should_pass(+)
        """)
    }

    fun `test test in custom test target`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []

                [[test]]
                name = "tests"
                path = "tests/tests.rs"
            """)

            dir("tests") {
                rust("tests.rs", """
                    #[test]
                    fn test_should_pass() { /*caret*/ }
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        runTestsAndCheckTestTree("""
            [root](+)
            .tests(+)
            ..test_should_pass(+)
        """)
    }

    fun `test test location`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []

                [[test]]
                name = "tests"
                path = "tests/tests.rs"
            """)

            dir("tests") {
                rust("tests.rs", """
                    /*caret*/

                    #[cfg(test)]
                    mod test_mod {
                        #[test]
                        fn test() {}
                    }

                    #[test]
                    fn test() {}
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)
        val configuration = createTestRunConfigurationFromContext()
        val root = runConfigurationAndGetTestRoot(configuration)

        val test = findTestByName("tests::test", root)!!
        assertEquals("cargo:test://tests::test", test.locationUrl)

        val mod = findTestByName("tests::test_mod", root)!!
        assertEquals("cargo:suite://tests::test_mod", mod.locationUrl)

        val testInner = findTestByName("tests::test_mod::test", root)!!
        assertEquals("cargo:test://tests::test_mod::test", testInner.locationUrl)
    }

    fun `test test duration`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "sandbox"
                version = "0.1.0"
                authors = []

                [[test]]
                name = "tests"
                path = "tests/tests.rs"
            """)

            dir("tests") {
                rust("tests.rs", """
                    use std::thread;
                    /*caret*/

                    #[test]
                    fn test1() {
                        thread::sleep_ms(2000);
                    }

                    #[test]
                    fn test2() {}
                """)
            }
        }
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)
        val configuration = createTestRunConfigurationFromContext()
        val root = runConfigurationAndGetTestRoot(configuration)

        val test1 = findTestByName("tests::test1", root)!!
        assert(test1.duration!! > 1000)

        val test2 = findTestByName("tests::test2", root)!!
        assert(test2.duration!! < 100)

        val mod = findTestByName("tests", root)!!
        assert(mod.duration!! == test1.duration!! + test2.duration!!)
    }

    private fun createTestRunConfigurationFromContext(): CargoCommandConfiguration {
        val context = DataManager.getInstance().getDataContext(myFixture.editor.component)
        return CargoTestRunConfigurationProducer()
            .createConfigurationFromContext(ConfigurationContext.getFromContext(context))
            ?.configuration as? CargoCommandConfiguration
            ?: error("Can't create run configuration")
    }

    private fun runConfigurationAndGetTestRoot(configuration: RunConfiguration): SMTestProxy.SMRootTestProxy {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val state = ExecutionEnvironmentBuilder
            .create(executor, configuration)
            .build()
            .state!!
        val result = state.execute(executor, RsRunner())!!
        val executionConsole = result.executionConsole as SMTRunnerConsoleView
        val testsRootNode = executionConsole.resultsViewer.testsRootNode

        with(result.processHandler) {
            startNotify()
            waitFor()
        }
        LaterInvocator.dispatchPendingFlushes()
        Disposer.dispose(executionConsole)

        return testsRootNode
    }

    private fun runTestsAndCheckTestTree(expectedFormattedTestTree: String) {
        val configuration = createTestRunConfigurationFromContext()
        val root = runConfigurationAndGetTestRoot(configuration)
        assertEquals(expectedFormattedTestTree.trimIndent(), getFormattedTestTree(root))
    }

    companion object {
        private fun getFormattedTestTree(testTreeRoot: SMTestProxy.SMRootTestProxy): String =
            buildString {
                if (testTreeRoot.wasTerminated()) {
                    append("Test terminated")
                    return@buildString
                }
                formatLevel(testTreeRoot)
            }

        private fun StringBuilder.formatLevel(test: SMTestProxy, level: Int = 0) {
            append(".".repeat(level))
            append(test.name)
            when {
                test.wasTerminated() -> append("[T]")
                test.isPassed -> append("(+)")
                test.isIgnored -> append("(~)")
                else -> append("(-)")
            }

            for (child in test.children) {
                append('\n')
                formatLevel(child, level + 1)
            }
        }

        private fun findTestByName(testFullName: String, root: SMTestProxy.SMRootTestProxy): SMTestProxy? {
            for (child in root.children) {
                val result = findTestByName(testFullName, child)
                if (result != null) return result
            }
            return null
        }

        private fun findTestByName(
            testFullName: String,
            test: SMTestProxy,
            fullNameBuffer: MutableList<String> = mutableListOf()
        ): SMTestProxy? {
            fullNameBuffer.add(test.name)
            if (testFullName == fullNameBuffer.joinToString("::")) return test
            for (child in test.children) {
                val result = findTestByName(testFullName, child, fullNameBuffer)
                if (result != null) return result
            }
            fullNameBuffer.removeLast()
            return null
        }
    }
}
