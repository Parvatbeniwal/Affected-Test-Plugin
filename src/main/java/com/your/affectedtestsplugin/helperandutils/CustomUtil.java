package com.your.affectedtestsplugin.helperandutils;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Utility class for various operations related to method signatures, class names, and PsiElements.
 */
public class CustomUtil {

    /**
     * Generates the signature of a method declaration given its signature and class name.
     *
     * @param method    The method.
     * @param className The name of the class declaring the method.
     * @return The full signature of the method in the format "className.signature".
     */
    public static String getSignOfMethodDeclaration(MethodDeclaration method, String className) {
        NodeList<Parameter> list = method.getParameters();
        StringBuilder parameterList = new StringBuilder("(");
        for (int i = 0; i < list.size(); i++) {
            Parameter para = list.get(i);
            parameterList.append(para.getType().asString());
            if (i != list.size() - 1) {
                parameterList.append(',');
            }
        }
        parameterList.append(')');
        return className + "." + method.getName().asString() + parameterList;
    }

    /**
     * Finds the name of the class declaring a given PsiElement.
     *
     * @param element The PsiElement whose declaring class is to be found.
     * @return The fully qualified name of the declaring class, or null if not found.
     */
    public static String findDeclaringClassName(PsiElement element) {
        PsiReference reference = element.getReference();
        if (reference == null) return null;
        PsiElement resolvedElement = reference.resolve();
        if (resolvedElement != null) {
            PsiClass declaringClass = PsiTreeUtil.getParentOfType(resolvedElement, PsiClass.class);
            if (declaringClass != null) return declaringClass.getName();
        }
        return null;
    }

    /**
     * Generates the method signature for a given PsiMethod and class name.
     *
     * @param method    The PsiMethod whose signature is to be generated.
     * @param className The name of the class declaring the method.
     * @return The full method signature in the format "className.methodName(parameterTypes)".
     */
    public static String getMethodSignatureForPsiElement(PsiMethod method, String className) {
        StringBuilder signatureBuilder = new StringBuilder();
        signatureBuilder.append(method.getName()).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            signatureBuilder.append(parameters[i].getType().getPresentableText());
            if (i < parameters.length - 1) {
                signatureBuilder.append(", ");
            }
        }
        signatureBuilder.append(')');
        return className + "." + signatureBuilder;
    }

    /**
     * Extracts the method name from a given method signature.
     *
     * @param methodSignature The full method signature.
     * @return The extracted method name, or the original signature if parsing fails.
     */
    public static String extractMethodName(String methodSignature) {
        if (methodSignature == null || methodSignature.isEmpty()) {
            return methodSignature; // Return the original if it's null or empty
        }

        int startIndex = methodSignature.lastIndexOf('.');
        int lastIndex = methodSignature.indexOf('(');

        // Ensure that both '.' and '(' exist in the string and are in the correct order
        if (startIndex != -1 && lastIndex != -1 && lastIndex > startIndex) {
            return methodSignature.substring(startIndex + 1, lastIndex);
        }

        // Fallback to the whole signature if parsing fails
        return methodSignature;
    }

    /**
     * Extracts the parameter types from a given method signature.
     *
     * @param methodSignature The full method signature.
     * @return An array of parameter types as strings.
     */
    public static String[] extractParameterTypes(String methodSignature) {
        // Find the indices of the parameter start and end parentheses
        int startIndex = methodSignature.indexOf('(');
        int endIndex = methodSignature.indexOf(')');

        // Return an empty array if parentheses are not found
        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
            return new String[0];
        }

        // Extract the parameters substring
        String params = methodSignature.substring(startIndex + 1, endIndex);

        // Return an empty array if there are no parameters
        if (params.isEmpty()) {
            return new String[0];
        }

        List<String> paramList = new ArrayList<>();
        StringBuilder currentParam = new StringBuilder();
        int angleBracketDepth = 0;

        // Iterate over each character in the parameter substring
        for (char ch : params.toCharArray()) {
            if (ch == '<') {
                angleBracketDepth++;
            } else if (ch == '>') {
                angleBracketDepth--;
            } else if (ch == ',' && angleBracketDepth == 0) {
                paramList.add(currentParam.toString().trim());
                currentParam.setLength(0);
                continue;
            }
            currentParam.append(ch);
        }

        // Add the last parameter if not empty
        if (!currentParam.isEmpty()) {
            paramList.add(currentParam.toString().trim());
        }

        // Convert the list to an array and return
        return paramList.toArray(new String[0]);
    }


    /**
     * Checks if the parameter types of a given PsiMethod match the specified parameter types.
     *
     * @param method         The PsiMethod to be checked.
     * @param parameterTypes The expected parameter types.
     * @return True if the parameter types match, false otherwise.
     */
    public static boolean isMatchingParameters(PsiMethod method, String[] parameterTypes) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (!parameters[i].getType().getPresentableText().replaceAll("\\s+", "").equals(parameterTypes[i].replaceAll("\\s+", ""))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a given PsiMethod is a test method.
     *
     * @param method The PsiMethod to be checked.
     * @return True if the method is annotated with @Test, false otherwise.
     */
    public static boolean isTestMethod(PsiMethod method) {
        String Junit5 = "org.junit.jupiter.api.Test";
        String Junit4 = "org.junit.Test";
        PsiAnnotation testAnnotation1 = method.getAnnotation(Junit5);
        PsiAnnotation testAnnotation2 = method.getAnnotation(Junit4);
        return (testAnnotation1 != null) || (testAnnotation2 != null);
    }

    /**
     * Extracts ClassName from File path
     *
     * @param sourceFilePath The path of file
     * @return The className as String
     */
    public static String getClassNameFromFilePath(String sourceFilePath) {
        int lastInd = sourceFilePath.lastIndexOf('.');
        int startInd = sourceFilePath.lastIndexOf('/');
        return sourceFilePath.substring(startInd + 1, lastInd);
    }

    /**
     * Converts the absolute file path to a relative file path based on the project base path.
     *
     * @param file            The virtual file.
     * @param projectBasePath The base path of the project.
     * @return The relative file path.
     */
    public static String getRelativeFilePath(VirtualFile file, String projectBasePath) {
        String absoluteFilePath = file.getPath();
        return absoluteFilePath.substring(projectBasePath.length() + 1);
    }

    /**
     * Displays a notification in the IDE.
     *
     * @param project the current project
     */
    public static void displayNotification(Project project, String title, String content) {
        final NotificationGroup notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("CustomNotifications");
        if (notificationGroup != null) {
            final Notification notification = notificationGroup.createNotification(
                    title.toUpperCase(),
                    content,
                    NotificationType.INFORMATION
            );
            Notifications.Bus.notify(notification, project);
        } else {
            System.err.println("Notification group 'CustomNotifications' not found");
        }
    }

    /**
     * Shows an error dialog.
     *
     * @param project the current project
     * @param message the error message
     * @param title   the dialog title
     */
    public static void showErrorDialog(Project project, String message, String title) throws RuntimeException{
        ApplicationManager.getApplication().invokeLater(()->{
            Messages.showErrorDialog(project, message, title);
            throw new RuntimeException();
        });
    }

    /**
     * Pops up a notification showing user the changes and affected tests
     *
     * @param project  the current project
     * @param title    the title of notification
     * @param changes  the set of changed methods
     * @param affected the set of affected tests
     */
    public static void displayFlow(Project project, String title, Set<String> changes, Set<PsiMethod> affected) {
        StringBuilder changesLog = new StringBuilder();
        int count = 0;
        if (changes != null) {
            for (String method : changes) {
                String temp = (++count) + ") " + method + "     \n";
                changesLog.append(temp);
            }
        } else {
            for (PsiMethod method : affected) {
                String temp = (++count) + ") " + method.getName() + "     \n";
                changesLog.append(temp);
            }
        }
        CustomUtil.displayNotification(project, title, String.valueOf(changesLog));
    }

    /**
     * Extracts the class name from a given method signature.
     *
     * @param methodSignature The full method signature.
     * @return The extracted class name.
     */
    /**
     * Extracts the class name from a given method signature.
     *
     * @param methodSignature The full method signature.
     * @return The extracted class name, or an empty string if parsing fails.
     */
    public static String extractClassName(String methodSignature) {
        if (methodSignature == null || methodSignature.isEmpty()) {
            return ""; // Return an empty string if the input is null or empty
        }

        int lastDotIndex = methodSignature.lastIndexOf('.');
        if (lastDotIndex != -1) {
            return methodSignature.substring(0, lastDotIndex);
        }
        return ""; // Return an empty string if there's no dot in the signature
    }

}
