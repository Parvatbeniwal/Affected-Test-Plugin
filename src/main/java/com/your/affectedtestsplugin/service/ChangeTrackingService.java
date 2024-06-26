package com.your.affectedtestsplugin.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.your.affectedtestsplugin.custom.CustomUtil;
import com.your.affectedtestsplugin.custom.PrivateMethodUsageFinder;
import com.your.affectedtestsplugin.runner.IntelliJTestRunner;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * This class contains the main logic of the plugin for tracking code changes and running relevant tests.
 * It tracks changes in local files, compares old and new versions, identifies affected methods,
 * and runs corresponding tests.
 */
@Service(Service.Level.PROJECT)
public final class ChangeTrackingService {

    private static final Logger LOG = Logger.getInstance(ChangeTrackingService.class);
    private final Project project;
    private final Set<String> CHANGES = new HashSet<>();
    private final Map<String, Integer> AFFECTED_METHODS = new HashMap<>();
    private final Set<PsiMethod> PRIVATE_METHODS = new HashSet<>();
    private final Set<PsiMethod> PUBLIC_METHOD_TESTS = new HashSet<>();
    private final Set<PsiMethod> ALL_AFFECTED_TESTS = new HashSet<>();

    /**
     * Constructs a ChangeTrackingService instance for the specified project.
     *
     * @param project The IntelliJ project instance.
     */
    public ChangeTrackingService(Project project) {
        this.project = project;
    }

    /**
     * Tracks changes in the project files and identifies affected methods.
     *
     * @param maxDepth The maximum depth for method usage search.
     */
    public synchronized void trackChangesAndRunTests(int maxDepth) {
        final ChangeListManager changeListManager = ChangeListManager.getInstance(project);

        // Get the list of local changes
        final List<LocalChangeList> changes = changeListManager.getChangeLists();

        // Iterate over changes and process them
        for (LocalChangeList changeList : changes) {
            for (Change change : changeList.getChanges()) {
                VirtualFile file = change.getVirtualFile();
                if (file != null) {
                    identifyChangedMethodsByComparing(file);
                } else {
                    CustomUtil.showErrorDialog(project,"No file are changed","NO CHANGES RECOGNIZED");
                    LOG.info("File is null");
                }
            }
        }

        CustomUtil.displayFlow(project,"Changed Methods",CHANGES,null);


        //DFS Traversal to get Usages
        findMethodUsages(maxDepth);

        //Getting the affected methods
        gettingAffectedTests();

        CustomUtil.displayFlow(project,"Affected Tests",null,ALL_AFFECTED_TESTS);
    }


    /**
     * Updates the list of changed methods by comparing the old and new versions of a given file.
     *
     * @param file The virtual file to be compared.
     */
    private void identifyChangedMethodsByComparing(VirtualFile file) {
        final String sourceFilePath = file.getPath();

        String className = CustomUtil.getClassNameFromFilePath(sourceFilePath);
        // Get old and new content of the file
        final String oldContent = getFileContent(file, this::getOldFileContent);
        final String newContent = getFileContent(file, this::getNewFileContent);

        if (oldContent != null) {
            compareFileContents(oldContent, newContent, className);
        } else {
            LOG.info("Past Commit Content is null");
        }
    }

    /**
     * Retrieves the content of the file using the provided content retriever.
     *
     * @param file             The virtual file whose content is to be retrieved.
     * @param contentRetriever The content retriever function.
     * @return The content of the file as a string.
     */
    private String getFileContent(VirtualFile file, ContentRetriever contentRetriever) {
        try {
            return contentRetriever.retrieve(file);
        } catch (IOException | RuntimeException e) {
            String message = e instanceof IOException ? "OLD" : "NEW";
            LOG.info("Cannot get " + message + " file content");
            return ""; //To handle if a completely new file is added Otherwise put null
        }
    }

    /**
     * Compares the contents of the old and new versions of a file.
     *
     * @param oldContent The content of the old version of the file.
     * @param newContent The content of the new version of the file.
     * @param className  The name of the class containing the methods.
     */
    private void compareFileContents(String oldContent, String newContent, String className) {
        final JavaParser parser = new JavaParser();

        final CompilationUnit oldCompilationUnit = parseContent(parser, oldContent);
        final CompilationUnit newCompilationUnit = parseContent(parser, newContent);

        // Compare methods
        compareMethods(oldCompilationUnit, newCompilationUnit, className);
    }

    /**
     * Parses the content of a file into a CompilationUnit.
     *
     * @param parser  The JavaParser instance.
     * @param content The content to be parsed.
     * @return The parsed CompilationUnit.
     */
    private CompilationUnit parseContent(JavaParser parser, String content) {
        return parser.parse(new ByteArrayInputStream(content.getBytes())).getResult().orElse(null);
    }

    /**
     * Functional interface for content retrievers.
     */
    @FunctionalInterface
    private interface ContentRetriever {
        String retrieve(VirtualFile file) throws IOException;
    }

    /**
     * Retrieves the content of the file from the last commit.
     *
     * @param file The virtual file whose content is to be retrieved.
     * @return The content of the file as a string.
     * @throws IOException If an I/O error occurs.
     */
    private String getOldFileContent(VirtualFile file) throws IOException {
        final String projectBasePath = project.getBasePath();
        if (projectBasePath == null) {
            LOG.info("Project's base path is null");
            throw new IOException("Project's base path is null");
        }

        final String relativeFilePath = CustomUtil.getRelativeFilePath(file, projectBasePath);
        final File repoDir = new File(projectBasePath);

        try (Git git = Git.open(repoDir)) {
            Repository repository = git.getRepository();
            ObjectId headId = resolveHead(repository);
            return getFileContentFromHeadCommit(repository, headId, relativeFilePath);
        }
    }


    /**
     * Resolves the HEAD commit object ID.
     *
     * @param repository The repository to resolve the HEAD commit from.
     * @return The ObjectId of the HEAD commit.
     * @throws IOException If the HEAD commit cannot be resolved.
     */
    private ObjectId resolveHead(Repository repository) throws IOException {
        final ObjectId headId = repository.resolve("HEAD");
        if (headId == null) {
            throw new IOException("Couldn't resolve HEAD");
        }
        return headId;
    }

    /**
     * Retrieves the content of the specified file from the HEAD commit.
     *
     * @param repository       The repository to retrieve the file content from.
     * @param headId           The ObjectId of the HEAD commit.
     * @param relativeFilePath The relative path of the file to retrieve.
     * @return The content of the file as a string.
     * @throws IOException If an I/O error occurs or the file is not found.
     */
    private String getFileContentFromHeadCommit(Repository repository, ObjectId headId, String relativeFilePath) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            final RevCommit headCommit = revWalk.parseCommit(headId);
            final ObjectId treeId = headCommit.getTree().getId();

            return findFileContentInTree(repository, treeId, relativeFilePath);
        }
    }

    /**
     * Finds the content of the specified file in the given tree.
     *
     * @param repository       The repository to search.
     * @param treeId           The ObjectId of the tree to search in.
     * @param relativeFilePath The relative path of the file to find.
     * @return The content of the file as a string.
     * @throws IOException If an I/O error occurs or the file is not found.
     */
    private String findFileContentInTree(Repository repository, ObjectId treeId, String relativeFilePath) throws IOException {
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(treeId);
            treeWalk.setRecursive(true);
            treeWalk.setFilter(PathFilter.create(relativeFilePath));

            while (treeWalk.next()) {
                final String path = treeWalk.getPathString();
                if (path.equals(relativeFilePath)) {
                    return getFileContentFromObjectId(repository, treeWalk.getObjectId(0));
                }
            }

            throw new IOException("File not found in the HEAD commit: " + relativeFilePath);
        }
    }

    /**
     * Retrieves the content of the file represented by the given ObjectId.
     *
     * @param repository The repository to read the file from.
     * @param objectId   The ObjectId of the file.
     * @return The content of the file as a string.
     * @throws IOException If an I/O error occurs.
     */
    private String getFileContentFromObjectId(Repository repository, ObjectId objectId) throws IOException {
        try (ObjectReader objectReader = repository.newObjectReader();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            objectReader.open(objectId).copyTo(outputStream);
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Retrieves the current content of the file.
     *
     * @param file The virtual file whose content is to be retrieved.
     * @return The content of the file as a string.
     */
    private String getNewFileContent(VirtualFile file) {
        try {
            byte[] content = file.contentsToByteArray();
            return new String(content);
        } catch (IOException e) {
            LOG.info("Error reading new file content");
            throw new RuntimeException("Error reading new file content", e);
        }
    }

    /**
     * Compares the methods in the old and new versions of a compilation unit.
     *
     * @param oldCompilationUnit The old compilation unit.
     * @param newCompilationUnit The new compilation unit.
     * @param className          The name of the class containing the methods.
     */
    private void compareMethods(CompilationUnit oldCompilationUnit, CompilationUnit newCompilationUnit, String className) {
        if (oldCompilationUnit == null || newCompilationUnit == null) {
            logCompilationUnitStatus(oldCompilationUnit, newCompilationUnit);
            return;
        }

        final Map<String, MethodDeclaration> oldMethodsMap = extractMethodsToMap(oldCompilationUnit, className);
        final List<MethodDeclaration> newMethods = extractMethods(newCompilationUnit);
        for (MethodDeclaration newMethod : newMethods) {
            String methodSignature = CustomUtil.getSignOfMethodDeclaration(newMethod, className);
            if (!oldMethodsMap.containsKey(methodSignature)) {
                CHANGES.add(methodSignature);
            } else {
                MethodDeclaration oldMethod = oldMethodsMap.get(methodSignature);
                if (!oldMethod.getBody().equals(newMethod.getBody())) {
                    CHANGES.add(methodSignature);
                }
                oldMethodsMap.remove(methodSignature);
            }
        }

        // Add removed methods to changes
        CHANGES.addAll(oldMethodsMap.keySet());
    }

    /**
     * Logs the status of the compilation units.
     *
     * @param oldCompilationUnit The old compilation unit.
     * @param newCompilationUnit The new compilation unit.
     */
    private void logCompilationUnitStatus(CompilationUnit oldCompilationUnit, CompilationUnit newCompilationUnit) {
        if (oldCompilationUnit == null) {
            LOG.info("Getting Old Compilation as null");
        }
        if (newCompilationUnit == null) {
            LOG.info("Getting New Compilation as null");
        }
    }

    /**
     * Extracts methods from the compilation unit and maps them by their signature.
     *
     * @param compilationUnit The compilation unit to extract methods from.
     * @param className       The name of the class containing the methods.
     * @return A map of method signatures to method declarations.
     */
    private Map<String, MethodDeclaration> extractMethodsToMap(CompilationUnit compilationUnit, String className) {
        List<MethodDeclaration> methods = extractMethods(compilationUnit);
        Map<String, MethodDeclaration> methodsMap = new HashMap<>();
        for (MethodDeclaration method : methods) {
            String methodSignature = CustomUtil.getSignOfMethodDeclaration(method, className);
            methodsMap.put(methodSignature, method);
        }
        return methodsMap;
    }

    /**
     * Extracts methods from the compilation unit.
     *
     * @param compilationUnit The compilation unit to extract methods from.
     * @return A list of method declarations.
     */
    private List<MethodDeclaration> extractMethods(CompilationUnit compilationUnit) {
        List<MethodDeclaration> methods = new ArrayList<>();
        compilationUnit.accept(new MethodVisitor(), methods);
        return methods;
    }


    /**
     * Visitor class for extracting method declarations from a compilation unit.
     */
    private static class MethodVisitor extends VoidVisitorAdapter<List<MethodDeclaration>> {
        @Override
        public void visit(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, List<MethodDeclaration> collector) {
            super.visit(classOrInterfaceDeclaration, collector);
            classOrInterfaceDeclaration.getMembers().forEach(member -> {
                if (member instanceof MethodDeclaration method) {
                    collector.add(method);
                }
            });
        }
    }

    /**
     * Finds the usages of changed methods and updates the affected methods map.
     *
     * @param maxDepth The maximum depth for method usage search.
     */
    private void findMethodUsages(int maxDepth) {
        for (String change : CHANGES) {
            findUsagesForMethod(change, maxDepth, 0, new HashSet<>());
        }
    }

    /**
     * Recursively finds the usages of a given method up to the specified depth.
     *
     * @param callingMethod The method whose usages are to be found.
     * @param maxDepth      The maximum depth for method usage search.
     * @param currentDepth  The current depth of the search.
     * @param currentPath   The set of methods visited in the current path to detect cycles.
     */
    private void findUsagesForMethod(String callingMethod, int maxDepth, int currentDepth, Set<String> currentPath) {
        if (shouldStopSearch(callingMethod, maxDepth, currentDepth, currentPath)) {
            return;
        }

        currentPath.add(callingMethod);
        AFFECTED_METHODS.put(callingMethod, currentDepth);

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiShortNamesCache shortNamesCache = PsiShortNamesCache.getInstance(project);

        String methodName = CustomUtil.extractMethodName(callingMethod);
        String className = CustomUtil.extractClassName(callingMethod);
        String[] parameterTypes = CustomUtil.extractParameterTypes(callingMethod);
        PsiMethod[] psiMethods = shortNamesCache.getMethodsByName(methodName, scope);
        for (PsiMethod method : psiMethods) {
            if (CustomUtil.isMatchingParameters(method, parameterTypes)) {
                addMethodToRelevantSets(method);
                gettingReferences(method,scope,className,maxDepth,currentDepth,currentPath);
            }
        }

        currentPath.remove(callingMethod);
    }

    /**
     * Getting the Usages of the method in a collection and traversing it.
     *
     * @param method        The method whose references are checked
     * @param scope         The scope of searching
     * @param className     The name of the class containing the method.
     * @param maxDepth      The maximum depth for the search.
     * @param currentDepth  The current depth of the search.
     * @param currentPath   The current path of visited methods to detect cycles.
     */
    private void gettingReferences(PsiMethod method,GlobalSearchScope scope,
                                   String className, int maxDepth, int currentDepth, Set<String> currentPath){
        Collection<PsiReference> references = ReferencesSearch.search(method, scope).findAll();
        for (PsiReference reference : references) {
            handleMethodReference(reference, className, maxDepth, currentDepth, currentPath);
        }
    }

    /**
     * Determines if the search should stop based on the current depth, maximum depth, and presence of cycles.
     *
     * @param callingMethod The method whose usages are being searched.
     * @param maxDepth      The maximum depth for the search.
     * @param currentDepth  The current depth of the search.
     * @param currentPath   The current path of visited methods to detect cycles.
     * @return True if the search should stop, false otherwise.
     */
    private boolean shouldStopSearch(String callingMethod, int maxDepth, int currentDepth, Set<String> currentPath) {
        if (currentDepth > maxDepth) {
            return true;
        }
        if (currentPath.contains(callingMethod)) {
            return true;
        }
        return AFFECTED_METHODS.containsKey(callingMethod) && AFFECTED_METHODS.get(callingMethod) <= currentDepth;
    }

    /**
     * Adds the given method to the relevant sets of private methods and public method tests.
     *
     * @param method The method to be added.
     */
    private void addMethodToRelevantSets(PsiMethod method) {
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
            PRIVATE_METHODS.add(method);
        }
        if (CustomUtil.isTestMethod(method)) {
            PUBLIC_METHOD_TESTS.add(method);
        }
    }

    /**
     * Handles a method reference by finding the containing method and recursively finding its usages.
     *
     * @param reference     The reference to the method.
     * @param className     The name of the class containing the method.
     * @param maxDepth      The maximum depth for the search.
     * @param currentDepth  The current depth of the search.
     * @param currentPath   The current path of visited methods to detect cycles.
     */
    private void handleMethodReference(PsiReference reference, String className, int maxDepth, int currentDepth, Set<String> currentPath) {
        PsiElement element = reference.getElement();
        String containingClass = CustomUtil.findDeclaringClassName(element);
        if (containingClass != null && Objects.equals(containingClass, className)) {
            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (containingMethod != null) {
                String methodClass = Objects.requireNonNull(containingMethod.getContainingClass()).getName();
                String methodSignature = CustomUtil.getMethodSignatureForPsiElement(containingMethod, methodClass);
                findUsagesForMethod(methodSignature, maxDepth, currentDepth + 1, currentPath);
            }
        }
    }

    /**
     * Makes up the set of all affected tests by the changes
     */
    public void gettingAffectedTests() {
        Set<PsiMethod> privateUsages = PrivateMethodUsageFinder.findPrivateMethodUsages(project, PRIVATE_METHODS);
        ALL_AFFECTED_TESTS.addAll(PUBLIC_METHOD_TESTS);
        ALL_AFFECTED_TESTS.addAll(privateUsages);
    }

    /**
     * Calls for the running tests for the stashed changes file while checking the test availability
     * @param latch For keeping track of lock
     */
    public void runTestsOnHeadCommitFiles(CountDownLatch latch) {
        if (!ALL_AFFECTED_TESTS.isEmpty()) {
            IntelliJTestRunner.runTests(project, ALL_AFFECTED_TESTS, latch);
        } else {
            CustomUtil.showErrorDialog(project,"No test are affected by the changes","No Test affected");
        }
    }

    /**
     * Calls for the running tests for the un-stashed files (with the changes)
     */
    public void runTestsOnCurrentState() {
        gettingAffectedTests();
        if (!ALL_AFFECTED_TESTS.isEmpty()) {
            IntelliJTestRunner.runTestsForPrevious(project, ALL_AFFECTED_TESTS);
        } else {
            CustomUtil.showErrorDialog(project,"No test are affected by the changes","No Test affected");
        }
    }

    /**
     * Method used for stashing the changes and
     * then calling for the running of the affected tests on stashed file
     * @param latch For keeping track of lock
     */
    public void runTestsOnHeadFiles(CountDownLatch latch) {
        try (Git git = Git.open(new File(Objects.requireNonNull(project.getBasePath())))) {
            //Stashing changes
            git.stashCreate().call();
            // Run tests on HEAD commit files
            runTestsOnHeadCommitFiles(latch);
        } catch (Exception e) {
            LOG.info(e.getMessage());
            CustomUtil.showErrorDialog(project,"Error in stashed file running: " + e.getMessage(),"First Run error");
        }
    }
}

