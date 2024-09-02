package com.your.affectedtestsplugin.runner;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.testframework.TestSearchScope;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Utility class for running JUnit tests within an IntelliJ project.
 */
public class IntelliJTestRunner {
    private static final Logger logger = Logger.getInstance(IntelliJTestRunner.class);
    public static final LinkedHashSet<String> TEST_PATTERNS = new LinkedHashSet<>();

    public static final String TEST_MODULE = "sprinklr.test.test";

    /**
     * Runs the specified set of JUnit test methods within the given IntelliJ project.
     *
     * @param project     The IntelliJ project in which to run the tests.
     * @param testMethods The set of test methods to be run.
     * @param latch       The CountDownLatch to synchronize the test run completion.
     */
    public void runTests(Project project, Set<PsiMethod> testMethods, CountDownLatch latch) {
        RunnerAndConfigurationSettings settings = createTestConfiguration(project, testMethods, "AffectedTestConfigurationNoChange");
        ExecutionEnvironment environment = buildExecutionEnvironment(settings, latch);
        logger.info("Invoking Run Configuration");
        ApplicationManager.getApplication().invokeLater(() -> startingRunProfile(project, environment, latch));
    }

    /**
     * Runs the specified set of JUnit test methods within the given IntelliJ project for the previous state.
     *
     * @param project     The IntelliJ project in which to run the tests.
     * @param testMethods The set of test methods to be run.
     */
    public void runTestsForPrevious(Project project, Set<PsiMethod> testMethods) {
        RunnerAndConfigurationSettings settings = createTestConfiguration(project, testMethods, "AffectedTestConfigurationChanges");
        ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
    }

    /**
     * Creates a JUnit test configuration for the specified test methods.
     *
     * @param project     The IntelliJ project.
     * @param testMethods The set of test methods to be included in the configuration.
     * @param configName  The name for the configuration.
     * @return The created RunnerAndConfigurationSettings.
     */
    private RunnerAndConfigurationSettings createTestConfiguration(Project project, Set<PsiMethod> testMethods, String configName) {
        final Set<PsiMethod> testMethodsSubSet = createSafeSubset(testMethods);

        final RunManager runManager = RunManager.getInstance(project);
        final ConfigurationType junitConfigType = ConfigurationTypeUtil.findConfigurationType(JUnitConfigurationType.class);
        final ConfigurationFactory junitConfigFactory = junitConfigType.getConfigurationFactories()[0];

        final RunnerAndConfigurationSettings settings = runManager.createConfiguration(configName, Objects.requireNonNull(junitConfigFactory));
        final JUnitConfiguration configuration = (JUnitConfiguration) settings.getConfiguration();

        ApplicationManager.getApplication().runReadAction(() -> setupTestConfigurationData(configuration, testMethodsSubSet));

        configuration.setWorkingDirectory(project.getBasePath());

        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);

        return settings;
    }

    /**
     * Sets up the test configuration data with the given test methods.
     *
     * @param configuration The JUnit configuration to set up.
     * @param testMethods   The set of test methods to be run.
     */
    private void setupTestConfigurationData(JUnitConfiguration configuration, Set<PsiMethod> testMethods) {
        final JUnitConfiguration.Data data = configuration.getPersistentData();
        data.TEST_OBJECT = JUnitConfiguration.TEST_PATTERN;
        final Set<PsiMethod> filteredMethods = filterTestMethodsByModule(testMethods, TEST_MODULE);
        collectMethodPatterns(filteredMethods);
        data.setPatterns(TEST_PATTERNS);
        data.setScope(TestSearchScope.SINGLE_MODULE);

        final ModuleManager moduleManager = ModuleManager.getInstance(configuration.getProject());
        final Module module = moduleManager.findModuleByName(TEST_MODULE);
        configuration.setModule(module);
        data.setWorkingDirectory(configuration.getProject().getBasePath());
        String vmOptions = buildVMOptions();
        configuration.setVMParameters(vmOptions);
    }

    private Set<PsiMethod> filterTestMethodsByModule(Set<PsiMethod> testMethods, String moduleName) {
        // Get the project from one of the test methods (assuming all belong to the same project)
        Project project = testMethods.iterator().next().getProject();

        // Obtain the ModuleManager and find the desired module
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        Module module = moduleManager.findModuleByName(moduleName);
        if (module == null) {
            throw new IllegalArgumentException("Module not found: " + moduleName);
        }

        // Get the module's file system and check the module's content roots
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        // Filter test methods based on whether they belong to the specified module
        return testMethods.stream()
                .filter(method -> {
                    PsiFile containingFile = method.getContainingFile();
                    if (containingFile == null) {
                        return false;
                    }
                    VirtualFile file = containingFile.getVirtualFile();
                    if (file == null) {
                        return false;
                    }

                    // Check if the file belongs to the module's content roots
                    for (VirtualFile root : contentRoots) {
                        if (VfsUtilCore.isAncestor(root, file, false)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toSet());
    }


    private String buildVMOptions() {
        final String vmOptions = "-ea --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED "
                + "--add-opens java.base/java.lang.annotation=ALL-UNNAMED --add-opens java.base/java.lang.constant=ALL-UNNAMED "
                + "--add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.module=ALL-UNNAMED "
                + "--add-opens java.base/java.lang.ref=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED "
                + "--add-opens java.base/java.lang.runtime=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED "
                + "--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.net.spi=ALL-UNNAMED "
                + "--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.nio.channels=ALL-UNNAMED "
                + "--add-opens java.base/java.nio.channels.spi=ALL-UNNAMED --add-opens java.base/java.nio.charset=ALL-UNNAMED "
                + "--add-opens java.base/java.nio.charset.spi=ALL-UNNAMED --add-opens java.base/java.nio.file=ALL-UNNAMED "
                + "--add-opens java.base/java.nio.file.attribute=ALL-UNNAMED --add-opens java.base/java.nio.file.spi=ALL-UNNAMED "
                + "--add-opens java.base/java.security=ALL-UNNAMED --add-opens java.base/java.security.cert=ALL-UNNAMED "
                + "--add-opens java.base/java.security.interfaces=ALL-UNNAMED --add-opens java.base/java.security.spec=ALL-UNNAMED "
                + "--add-opens java.base/java.text=ALL-UNNAMED --add-opens java.base/java.text.spi=ALL-UNNAMED "
                + "--add-opens java.base/java.time=ALL-UNNAMED --add-opens java.base/java.time.chrono=ALL-UNNAMED "
                + "--add-opens java.base/java.time.format=ALL-UNNAMED --add-opens java.base/java.time.temporal=ALL-UNNAMED "
                + "--add-opens java.base/java.time.zone=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED "
                + "--add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED "
                + "--add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED --add-opens java.base/java.util.function=ALL-UNNAMED "
                + "--add-opens java.base/java.util.jar=ALL-UNNAMED --add-opens java.base/java.util.random=ALL-UNNAMED "
                + "--add-opens java.base/java.util.regex=ALL-UNNAMED --add-opens java.base/java.util.spi=ALL-UNNAMED "
                + "--add-opens java.base/java.util.stream=ALL-UNNAMED --add-opens java.base/java.util.zip=ALL-UNNAMED "
                + "--add-opens java.base/javax.crypto=ALL-UNNAMED --add-opens java.base/javax.crypto.interfaces=ALL-UNNAMED "
                + "--add-opens java.base/javax.crypto.spec=ALL-UNNAMED --add-opens java.base/javax.net=ALL-UNNAMED "
                + "--add-opens java.base/javax.net.ssl=ALL-UNNAMED --add-opens java.base/javax.security.auth=ALL-UNNAMED "
                + "--add-opens java.base/javax.security.auth.callback=ALL-UNNAMED --add-opens java.base/javax.security.auth.login=ALL-UNNAMED "
                + "--add-opens java.base/javax.security.auth.spi=ALL-UNNAMED --add-opens java.base/javax.security.auth.x500=ALL-UNNAMED "
                + "--add-opens java.base/javax.security.cert=ALL-UNNAMED --add-opens java.xml/com.sun.org.apache.xerces.internal.parsers=ALL-UNNAMED "
                + "--add-opens java.xml/com.sun.org.apache.xerces.internal.util=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED "
                + "--add-opens java.base/sun.net.www=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED "
                + "--add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED --add-opens java.base/sun.util.calendar=ALL-UNNAMED "
                + "--add-opens java.desktop/java.beans=ALL-UNNAMED --add-opens java.base/sun.util.calendar=ALL-UNNAMED "
                + "--add-opens java.scripting/javax.script=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED "
                + "--add-opens java.base/sun.util.locale.provider=ALL-UNNAMED -Djava.locale.providers=SPI,CLDR,COMPAT";

        return vmOptions;
    }


    /**
     * Collects method patterns from the given set of test methods.
     *
     * @param testMethods The set of test methods to collect patterns from.
     */
    private void collectMethodPatterns(Set<PsiMethod> testMethods) {
        if (!TEST_PATTERNS.isEmpty()) {
            return;
        }

        for (PsiMethod method : testMethods) {
            PsiClass psiClass = method.getContainingClass();
            if (psiClass == null || isExcludedClass(psiClass)) {
                continue;
            }

            final String className = psiClass.getQualifiedName();
            final String methodName = method.getName();
            if (className != null) {
                TEST_PATTERNS.add(className + "," + methodName);
            }
        }
    }

    /**
     * Builds the execution environment for running the tests.
     *
     * @param settings The RunnerAndConfigurationSettings.
     * @param latch    The CountDownLatch to synchronize the test run completion.
     * @return The built ExecutionEnvironment.
     */
    private static ExecutionEnvironment buildExecutionEnvironment(RunnerAndConfigurationSettings settings, CountDownLatch latch) {
        try {
            return ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build();
        } catch (ExecutionException e) {
            latch.countDown();
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts the run profile for the specified execution environment.
     *
     * @param project     The IntelliJ project.
     * @param environment The ExecutionEnvironment.
     * @param latch       The CountDownLatch to synchronize the test run completion.
     */
    private static void startingRunProfile(Project project, ExecutionEnvironment environment, CountDownLatch latch) {
        try {
            ExecutionManager.getInstance(project).startRunProfile(environment, state -> {
                try {
                    var handler = state.execute(environment.getExecutor(), environment.getRunner());
                    if (handler != null) {
                        handler.getProcessHandler().addProcessListener(new ProcessAdapter() {
                            @Override
                            public void processTerminated(@NotNull ProcessEvent event) {
                                latch.countDown(); // Release the latch when the process terminates
                            }
                        });
                        return new RunContentDescriptor(handler.getExecutionConsole(), handler.getProcessHandler(),
                                handler.getExecutionConsole().getComponent(), "Run Tests");
                    } else {
                        latch.countDown(); // Release the latch if the process handler is null
                        throw new ExecutionException("Failed to start the run configuration.");
                    }
                } catch (ExecutionException e) {
                    logger.error("Execution failed", e);
                    latch.countDown(); // Ensure latch is released in case of exception
                    throw e;
                }
            });
        } catch (ExecutionException e) {
            logger.error("Starting run profile failed", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Determines if a PsiClass should be excluded from the test run.
     *
     * @param psiClass The PsiClass to check.
     * @return True if the class should be excluded; otherwise, false.
     */
    private boolean isExcludedClass(PsiClass psiClass) {
        String className = psiClass.getQualifiedName();
        return className != null && (className.contains("AbstractJUnit4SpringContextTests") ||
                isSubclassOf(psiClass, "org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests"));
    }

    /**
     * Determines if a PsiClass is a subclass of a specific superclass.
     *
     * @param psiClass      The PsiClass to check.
     * @param superClassName The name of the superclass to check against.
     * @return True if the PsiClass is a subclass of the specified superclass; otherwise, false.
     */
    private boolean isSubclassOf(PsiClass psiClass, String superClassName) {
        if (psiClass == null) {
            return false;
        }

        // Traverse the class hierarchy
        for (PsiClass superClass = psiClass.getSuperClass(); superClass != null; superClass = superClass.getSuperClass()) {
            if (superClassName.equals(superClass.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a safe subset of the given set of test methods.
     *
     * @param testMethods The original set of test methods.
     * @return A subset containing up to 20 elements.
     */
    private static Set<PsiMethod> createSafeSubset(Set<PsiMethod> testMethods) {
        if (testMethods == null || testMethods.isEmpty()) {
            return new HashSet<>();  // Return an empty set if input is null or empty
        }
        //return testMethods;
        // Return a subset with at most 100 elements
        return testMethods.stream()
                .limit(100)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
