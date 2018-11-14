/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.DataManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import org.rust.cargo.RustWithToolchainTestBase
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.CargoCommandConfigurationType
import org.rust.cargo.runconfig.test.CargoTestRunConfigurationProducer
import org.rust.fileTree

class RunConfigurationTestCase : RustWithToolchainTestBase() {

    fun `test application configuration`() {
        fileTree {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    fn main() {
                        println!("Hello, world!");
                    }
                """)
            }
        }.create()
        val configuration = createConfiguration()
        val result = execute(configuration)

        check("Hello, world!" in result.stdout)
    }

    fun `test single test configuration 1`() {
        val testProject = fileTree {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    fn main() {
                        println!("Hello, world!");
                    }

                    #[test]
                    fn foo() {
                        /*caret*/
                    }
                """)
            }
        }.create()
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        val configuration = createTestRunConfigurationFromContext()
        val result = execute(configuration)
        check("""{ "type": "test", "event": "started", "name": "foo" }""" in result.stdout)
    }

    fun `test single test configuration 2`() {
        val testProject = fileTree {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    fn main() {
                        println!("Hello, world!");
                    }

                    #[cfg(test)]
                    mod tests {
                        #[test]
                        fn foo() {
                            /*caret*/
                        }

                        #[test]
                        fn bar() {}
                    }
                """)
            }
        }.create()
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        val configuration = createTestRunConfigurationFromContext()
        val result = execute(configuration)
        check("""{ "type": "test", "event": "started", "name": "tests::foo" }""" in result.stdout)
        check("""{ "type": "test", "event": "started", "name": "tests::bar" }""" !in result.stdout)
    }

    fun `test mod test configuration`() {
        val testProject = fileTree {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                rust("main.rs", """
                    fn main() {
                        println!("Hello, world!");
                    }

                    #[cfg(test)]
                    mod tests {
                        /*caret*/
                        #[test]
                        fn foo() {}

                        #[test]
                        fn bar() {}
                    }
                """)
            }
        }.create()
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)

        val configuration = createTestRunConfigurationFromContext()
        val result = execute(configuration)
        check("""{ "type": "test", "event": "started", "name": "tests::foo" }""" in result.stdout)
        check("""{ "type": "test", "event": "started", "name": "tests::bar" }""" in result.stdout)
    }

    private fun createConfiguration(): CargoCommandConfiguration {
        val configurationType = ConfigurationTypeUtil.findConfigurationType(CargoCommandConfigurationType::class.java)
        val factory = configurationType.factory
        return factory.createTemplateConfiguration(myModule.project) as CargoCommandConfiguration
    }

    private fun createTestRunConfigurationFromContext(): CargoCommandConfiguration =
        createRunConfigurationFromContext(CargoTestRunConfigurationProducer())

    private fun createRunConfigurationFromContext(producer: RunConfigurationProducer<CargoCommandConfiguration>): CargoCommandConfiguration {
        val context = DataManager.getInstance().getDataContext(myFixture.editor.component)
        return producer.createConfigurationFromContext(ConfigurationContext.getFromContext(context))
                ?.configuration as? CargoCommandConfiguration
                ?: error("Can't create run configuration")

    }

    private fun execute(configuration: RunConfiguration): ProcessOutput {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val state = ExecutionEnvironmentBuilder
            .create(executor, configuration)
            .build()
            .state!!

        val result = state.execute(executor, RsRunner())!!

        val listener = AnsiAwareCapturingProcessAdapter()
        with(result.processHandler) {
            addProcessListener(listener)
            startNotify()
            waitFor()
        }
        Disposer.dispose(result.executionConsole)
        return listener.output
    }
}

/**
 * Capturing adapter that removes ANSI escape codes from the output
 */
class AnsiAwareCapturingProcessAdapter : ProcessAdapter(), AnsiEscapeDecoder.ColoredTextAcceptor {
    val output = ProcessOutput()

    private val decoder = object : AnsiEscapeDecoder() {
        override fun getCurrentOutputAttributes(outputType: Key<*>) = outputType
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
        decoder.escapeText(event.text, outputType, this)

    private fun addToOutput(text: String, outputType: Key<*>) {
        if (outputType === ProcessOutputTypes.STDERR) {
            output.appendStderr(text)
        } else {
            output.appendStdout(text)
        }
    }

    override fun processTerminated(event: ProcessEvent) {
        output.exitCode = event.exitCode
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) =
        addToOutput(text, attributes)
}
