package com.your.affectedtestsplugin.helperandutils;

import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Custom dialog for inputting depth level and selecting the option to check the previous commit.
 */
public class CustomDialog extends DialogWrapper {
    private JTextField depthField;
    private JCheckBox checkPreviousCommitCheckBox;

    /**
     * Constructs a CustomDialog.
     */
    public CustomDialog() {
        super(true); // use current window as parent
        init();
        setTitle("Input Depth Level and Check Previous Commit");
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel dialogPanel = new JPanel(new BorderLayout());

        depthField = new JTextField();
        checkPreviousCommitCheckBox = new JCheckBox("Check Previous Commit");

        JPanel inputPanel = new JPanel(new GridLayout(3, 1)); // Adjusted GridLayout to have 3 rows
        inputPanel.add(new JLabel("Enter the depth level for the method usage search:"));
        inputPanel.add(depthField);
        inputPanel.add(checkPreviousCommitCheckBox); // Directly adding checkbox without alignment

        dialogPanel.add(inputPanel, BorderLayout.CENTER);

        return dialogPanel;
    }

    /**
     * Gets the depth level input by the user.
     *
     * @return the depth level as a string
     */
    public String getDepth() {
        return depthField.getText();
    }

    /**
     * Checks if the "Check Previous Commit" option is selected.
     *
     * @return true if the checkbox is selected, false otherwise
     */
    public boolean isCheckPreviousCommit() {
        return checkPreviousCommitCheckBox.isSelected();
    }
}
