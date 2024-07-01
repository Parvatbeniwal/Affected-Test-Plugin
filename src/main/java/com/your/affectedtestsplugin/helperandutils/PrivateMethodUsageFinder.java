package com.your.affectedtestsplugin.helperandutils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.psi.search.UsageSearchContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for finding usages of private methods within a project.
 */
public class PrivateMethodUsageFinder {

    /**
     * Finds test methods that use the given set of private methods within a project.
     *
     * @param project        The project in which to search for private method usages.
     * @param privateMethods The set of private methods to find usages for.
     * @return A set of PsiMethods representing the test methods that use the private methods.
     */
    public static Set<PsiMethod> findPrivateMethodUsages(Project project, Set<PsiMethod> privateMethods) {
        Set<PsiMethod> testMethodsUsingPrivateMethods = new HashSet<>();
        PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(project);
        GlobalSearchScope searchScope = GlobalSearchScope.projectScope(project);

        HashMap<String, PsiMethod> privateMethodNames = new HashMap<>();
        for (PsiMethod privateMethod : privateMethods) {
            privateMethodNames.put(privateMethod.getName(), privateMethod);
        }

        for (String methodName : privateMethodNames.keySet()) {
            TextOccurenceProcessor processor = (element, offsetInElement) -> {
                if (element.getText().contains(methodName)) {
                    PsiElement parent = element.getParent();
                    if (parent instanceof PsiMethod psiMethod && CustomUtil.isTestMethod(psiMethod)) {
                        testMethodsUsingPrivateMethods.add(psiMethod);
                    }
                }
                return true; // Continue searching
            };
            searchHelper.processElementsWithWord(processor, searchScope, methodName, UsageSearchContext.IN_STRINGS, true);
        }
        return testMethodsUsingPrivateMethods;
    }
}
