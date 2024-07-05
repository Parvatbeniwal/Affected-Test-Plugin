package com.your.affectedtestsplugin.reportListener;

import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestStatusListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Stack;

/**
 * Listener for test suite completion to generate test reports.
 */
public class TestReportListener extends TestStatusListener {

    private static int count = 0;
    private static final Logger logger = Logger.getInstance(TestReportListener.class);

    @Override
    public void testSuiteFinished(AbstractTestProxy root) {
        Project project = getCurrentProject();
        if (project != null) {
            generateTestReport(project, root);
        }
    }

    /**
     * Generates the test report and writes it to a file.
     *
     * @param project the current project
     * @param root    the root test proxy
     */
    private void generateTestReport(Project project, AbstractTestProxy root) {
        try (FileWriter writer = new FileWriter(project.getBasePath() + "/testReport" + count++ + ".txt")) {
            writer.write("Test Report\n");
            writer.write("===========\n\n");
            generateReport(root, writer);
        } catch (IOException e) {
            logger.error("Exception encountered in report generation for /testReport" + count, e);
        }
    }

    /**
     * Generates the test report content.
     *
     * @param root   the root test proxy
     * @param writer the file writer
     * @throws IOException if an I/O error occurs
     */
    private void generateReport(AbstractTestProxy root, FileWriter writer) throws IOException {
        Stack<AbstractTestProxy> stack = new Stack<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AbstractTestProxy current = stack.pop();
            if (current.isLeaf()) {
                String result = current.isPassed() ? "PASSED" : "FAILED";
                writer.write(current + ": " + result + "\n");
            }
            for (AbstractTestProxy child : current.getChildren()) {
                stack.push(child);
            }
        }
    }

    /**
     * Gets the current project.
     *
     * @return the first open project, or null if no projects are open
     */
    private Project getCurrentProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }
}
