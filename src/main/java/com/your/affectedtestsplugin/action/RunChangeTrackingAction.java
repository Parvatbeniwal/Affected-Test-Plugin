package com.your.affectedtestsplugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.your.affectedtestsplugin.custom.CustomDialog;
import com.your.affectedtestsplugin.custom.CustomUtil;
import com.your.affectedtestsplugin.service.ChangeTrackingService;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import static org.apache.commons.lang.BooleanUtils.isFalse;

/**
 * An action to track code changes and run tests on the current state and optionally on the previous commit.
 */
public class RunChangeTrackingAction extends AnAction {
    private static final Logger logger = Logger.getInstance(RunChangeTrackingAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project != null) {
            CustomUtil.displayNotification(project,"Affected Tests Plugin",
                    "The plugin begins. If you click the check previous option, then the report generated first will be of unchanged files.");
            handleUserInputAndRunTasks(project);
        } else {
            logger.info("Inside actionPerformed, project is null");
        }
    }

    /**
     * Handles user input from the dialog and initiates the tasks based on the input.
     *
     * @param project the current project
     */
    private void handleUserInputAndRunTasks(Project project) {
        CustomDialog dialog = new CustomDialog();
        if (dialog.showAndGet()) {
            String input = dialog.getDepth();
            boolean checkPrevious = dialog.isCheckPreviousCommit();
            if (isValidInput(input)) {
                int depth = Integer.parseInt(input);
                startChangeTrackingTask(project, depth, checkPrevious);
            } else {
                CustomUtil.showErrorDialog(project, "Depth level input is required and must be a valid number.", "Invalid Input");
            }
        }
    }

    /**
     * Validates the user input.
     *
     * @param input the user input
     * @return true if the input is valid, false otherwise
     */
    private boolean isValidInput(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Starts the background task for change tracking.
     *
     * @param project       the current project
     * @param depth         the depth level for tracking changes
     * @param checkPrevious flag to indicate if tests on the previous commit should be run
     */
    private void startChangeTrackingTask(Project project, int depth, boolean checkPrevious) {
        final ChangeTrackingService changeTrackingService = project.getService(ChangeTrackingService.class);
        changeTrackingService.trackChangesAndRunTests(depth);

        CountDownLatch latch = new CountDownLatch(1);

        Task.Backgroundable task = new Task.Backgroundable(project, "Running change tracking") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runChangeTracking(project, checkPrevious, latch);
            }
        };
        ProgressManager.getInstance().run(task);
    }

    /**
     * Runs the change tracking process and handles the latch synchronization.
     *
     * @param project       the current project
     * @param checkPrevious flag to indicate if tests on the previous commit should be run
     * @param latch         the latch to synchronize tasks
     */
    private void runChangeTracking(Project project, boolean checkPrevious, CountDownLatch latch) {

        checkPreviousCommitTests(project, checkPrevious, latch);
        awaitLatch(project, latch);

        applyStash(project, checkPrevious);

        trackChangesAndNotify(project);
    }

    /**
     * Awaits the latch release.
     *
     * @param project the current project
     * @param latch   the latch to be awaited
     */
    private void awaitLatch(Project project, CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            logger.info(ex.getMessage());
            Thread.currentThread().interrupt();
            ApplicationManager.getApplication().invokeLater(() -> CustomUtil.showErrorDialog(project, "Await interrupted: " + ex.getMessage(), "Error"));
        }
    }

    /**
     * Applies the stashed changes.
     *
     * @param project the current project
     */
    private void applyStash(Project project, boolean checkPrevious) {
        if (isFalse(checkPrevious)) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            try (Git git = Git.open(new File(Objects.requireNonNull(project.getBasePath())))) {
                git.stashApply().setStashRef("stash@{0}").call();
            } catch (Exception ex) {
                CustomUtil.showErrorDialog(project, "Error in Popping", "Popping Error");
            }
        });
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

    /**
     * Checks the tests on the previous commit if the option is selected.
     *
     * @param project       the current project
     * @param checkPrevious flag to indicate if tests on the previous commit should be run
     * @param latch         the latch to synchronize tasks
     */
    private void checkPreviousCommitTests(Project project, boolean checkPrevious, CountDownLatch latch) {
        if (checkPrevious) {
            final ChangeTrackingService changeTrackingService = project.getService(ChangeTrackingService.class);
            changeTrackingService.runTestsOnHeadFiles(latch);
        } else {
            latch.countDown();
        }
    }
}
