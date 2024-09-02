package com.your.affectedtestsplugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.your.affectedtestsplugin.helperandutils.CustomUtil;
import com.your.affectedtestsplugin.runner.IntelliJTestRunner;
import com.your.affectedtestsplugin.service.ChangeTrackingService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * An action to track code changes and run tests on the current state and optionally on the previous commit.
 */
public class RunChangeTrackingAction extends AnAction {
    private static final Icon ICON = IconLoader.getIcon("/META-INF/pluginIcon.svg", RunChangeTrackingAction.class);
    private static final Logger logger = Logger.getInstance(RunChangeTrackingAction.class);

    public RunChangeTrackingAction() {
        super("Run Affected Tests From Changes", "Tracks the changes and run the tests affected with feature of getting the conditions of tests before changes", ICON);
    }

    /**
     * Invokes the action performed by the plugin when we get an action event by the user like a click on plugin option
     *
     * @param e the action event made by the user
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project != null) {
            CustomUtil.displayNotification(project, "Affected Tests Plugin", "Stage : Started");
            runChangeDetectionTask(project);
        } else {
            logger.info("Inside actionPerformed, project is null");
        }
    }

    /**
     *
     * @param project the current project
     */
    private void runChangeDetectionTask(Project project) {
        try {
            final ChangeTrackingService changeTrackingService = project.getService(ChangeTrackingService.class);
            boolean changedDetected = changeTrackingService.trackChangesAndTests(2);
            if (!changedDetected) {
                return;
            }
            startChangeTrackingTask(project);
        }
        catch (Exception eX){
            logger.error("Error Unit Test Finder", eX);
            CustomUtil.displayNotification(project, "Error Occurred while Running", eX.getMessage() + " " + eX.getStackTrace());
        }
    }

    /**
     * Starts the background task for change tracking.
     *
     * @param project       the current project
     */
    private void startChangeTrackingTask(Project project) {
        Task.Backgroundable task = new Task.Backgroundable(project, "Running change tracking") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runChangeTracking(project);
            }
        };
        ProgressManager.getInstance().run(task);
    }

    /**
     * Runs the change tracking process and handles the latch synchronization
     * @param project       the current project
     */
    private void runChangeTracking(Project project) {
        IntelliJTestRunner.TEST_PATTERNS.clear();
        trackChangesAndNotify(project);
    }
    /**
     * Tracks changes and notifies the user.
     *
     * @param project the current project
     */
    private void trackChangesAndNotify(Project project) {
        final ChangeTrackingService changeTrackingService = project.getService(ChangeTrackingService.class);
        ApplicationManager.getApplication().invokeLater(changeTrackingService::runTestsOnCurrentState);
    }
}
