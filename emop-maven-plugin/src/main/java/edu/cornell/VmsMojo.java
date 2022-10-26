package edu.cornell;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.cornell.emop.util.Util;
import edu.illinois.starts.jdeps.DiffMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

@Mojo(name = "vms", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST, lifecycle = "vms")
public class VmsMojo extends DiffMojo {

    private final DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);

    // Represents the map between a file and a map representing the number of lines added or deleted at each line
    // of the file. If the value is 0, the line has been modified in place.
    // Note: If renames are involved, the old name of the file is used.
    //          file        line     lines modified
    private Map<String, Map<Integer, Integer>> lineChanges = new HashMap<>();

    // Represents the renamed file's original name
    private Map<String, String> renames = new HashMap<>();

    // Describes new classes which have been made between commits
    private Set<String> newClasses = new HashSet<>();

    private Set<List<String>> oldViolations;
    private Set<List<String>> newViolations;

    public void execute() throws MojoExecutionException {
        getLog().info("[eMOP] Invoking the VMS Mojo...");
        saveViolationCounts();
        findLineChangesAndRenames(getDiffs());
        getLog().info("Number of files renamed: " + renames.size());
        getLog().info("Number of changed files found: " + lineChanges.size());
        oldViolations = Util.parseViolations(getArtifactsDir() + File.separator + "violation-counts-old");
        newViolations = Util.parseViolations(getArtifactsDir() + File.separator + "violation-counts");
        getLog().info("Number of total violations found: " + newViolations.size());
        removeDuplicateViolations();
        getLog().info("Number of \"new\" violations found: " + newViolations.size());
        rewriteViolationCounts();
    }

    /**
     * Fetches the two most recent commits of the repository and finds the differences between them.
     *
     * @return List of differences between two most recent commits of the repository
     * @throws MojoExecutionException if error is encountered during runtime
     */
    private List<DiffEntry> getDiffs() throws MojoExecutionException {
        Git git;
        Iterable<RevCommit> commits;
        ObjectReader objectReader;
        List<DiffEntry> diffs;

        // Sets up repository and fetches commits
        try {
            git = Git.open(basedir.toPath().resolve(".git").toFile());
            commits = git.log().setMaxCount(1).call();
            objectReader = git.getRepository().newObjectReader();
        } catch (GitAPIException | IOException exception) {
            throw new MojoExecutionException("Failed to fetch two previous commits from repository");
        }

        // Creates trees to parse through to analyze for differences
        List<AbstractTreeIterator> trees = new ArrayList<>();
        trees.add(new FileTreeIterator(git.getRepository()));
        try {
            for (RevCommit commit : commits) {
                trees.add(new CanonicalTreeParser(null, objectReader, commit.getTree().getId()));
            }
        } catch (IOException exception) {
            throw new MojoExecutionException("Encountered an error when creating trees from commits");
        }

        // Sets up diffFormatter and analyzes for differences between the two trees
        diffFormatter.setRepository(git.getRepository());
        diffFormatter.setContext(0);
        diffFormatter.setDetectRenames(true);
        try {
            diffs = diffFormatter.scan(trees.get(1), trees.get(0));
        } catch (IOException exception) {
            throw new MojoExecutionException("Encountered an error when analyzing for differences between commits");
        }

        git.close();
        return diffs;
    }

    /**
     * Updates the lineChanges and renames based on found differences.
     *
     * @param diffs List of differences between two versions of the same program
     * @throws MojoExecutionException if error is encountered during runtime
     */
    private void findLineChangesAndRenames(List<DiffEntry> diffs) throws MojoExecutionException {
        try {
            for (DiffEntry diff : diffs) {
                // If the old path is /dev/null, then the file has been created between commits
                if (diff.getOldPath().equals("/dev/null")) {
                    newClasses.add(diff.getNewPath());
                } else if (!diff.getNewPath().equals("/dev/null")) { // Ignore if a deleted class
                    // Gets renamed classes
                    if (!diff.getOldPath().equals(diff.getNewPath())) {
                        renames.put(diff.getNewPath(), diff.getOldPath());
                    }

                    // For each file, find the replacements, additions, and deletions at each line
                    for (Edit edit : diffFormatter.toFileHeader(diff).toEditList()) {
                        Map<Integer, Integer> lineChange = new HashMap<>();
                        if (lineChanges.containsKey(diff.getOldPath())) {
                            lineChange = lineChanges.get(diff.getOldPath());
                        }
                        lineChange.put(edit.getBeginA() + 1, edit.getLengthB() - edit.getLengthA());
                        lineChanges.put(diff.getOldPath(), lineChange);
                    }
                }
            }
        } catch (IOException exception) {
            throw new MojoExecutionException("Encountered an error when comparing different files");
        }
    }

    /**
     * Scrubs newViolations of violations believed to be duplicates from violation-counts-old.
     */
    private void removeDuplicateViolations() {
        Set<List<String>> violationsToRemove = new HashSet<>();
        for (List<String> newViolation : newViolations) {
            Set<List<String>> relevantOldViolations = oldViolations.stream()
                    .filter(oldViolation -> oldViolation.get(0).equals(newViolation.get(0))) // same spec
                    .filter(oldViolation -> oldViolation.get(1).equals(newViolation.get(1))
                                         || isRenamed(oldViolation.get(1), newViolation.get(1))) // same class
                    .filter(oldViolation -> hasSameLineNumber(oldViolation.get(1), Integer.parseInt(oldViolation.get(2)),
                            Integer.parseInt(newViolation.get(2)))) // same line number
                    .collect(Collectors.toSet());

            if (!relevantOldViolations.isEmpty()) {
                violationsToRemove.add(newViolation);
            }
        }
        for (List<String> violationToRemove : violationsToRemove) {
            newViolations.remove(violationToRemove);
        }
    }

    /**
     * Determines whether an old class has been renamed to the new one or not.
     *
     * @param oldClass Previous possible name of a class
     * @param newClass Rename being considered
     * @return Whether the old class name was renamed to the new one
     */
    private boolean isRenamed(String oldClass, String newClass) {
        for (String renamedClass : renames.keySet()) {
            if (renamedClass.contains(newClass) && renames.get(renamedClass).contains(oldClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether an old line in a file can be mapped to the new line. There's room for optimization.
     *
     * @param classInfo Particular class being considered (if the class was renamed, this is the old name)
     * @param oldLine Original line number
     * @param newLine New line number
     * @return Whether the original line number can be mapped to the new line number in the updated version
     */
    private boolean hasSameLineNumber(String classInfo, int oldLine, int newLine) {
        for (String className : lineChanges.keySet()) {
            if (className.contains(classInfo)) {
                int offset = 0;
                for (Integer originalLine : lineChanges.get(className).keySet()) {
                    if (originalLine <= oldLine) {
                        offset += lineChanges.get(className).get(originalLine);
                    }
                }
                if (newLine >= oldLine) { // if lines have been inserted
                    return offset >= 0 && offset >= newLine - oldLine;
                } else { // if lines have been removed
                    return offset <= 0 && offset <= newLine - oldLine;
                }
            }
        }
        return oldLine == newLine;
    }

    /**
     * Rewrites violation-counts to only include violations in newViolations.
     */
    private void rewriteViolationCounts() throws MojoExecutionException {
        // for each line of violation-counts, if it can be mapped to a new violation it gets to stay (else it goes)
        try {
            Path vc = Paths.get(System.getProperty("user.dir"), "violation-counts");
            List<String> lines = Files.readAllLines(vc);
            PrintWriter writer = new PrintWriter(vc.toFile());
            for (String line : lines) {
                if (isNewViolation(line)) {
                    writer.println(line);
                }
            }
            writer.close();
        } catch (IOException exception) {
            throw new MojoExecutionException("Failure encountered when rewriting violation-counts");
        }
    }

    /**
     * Whether a violation line is a new violation.
     *
     * @param violation Violation line being considered
     * @return Whether the violation is a new violation
     */
    private boolean isNewViolation(String violation) {
        List<String> parsedViolation = Util.parseViolation(violation);
        for (List<String> newViolation : newViolations) {
            if (newViolation.equals(parsedViolation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves the most recent <code>violation-counts</code> created by RV-Monitor
     * into the artifacts directory, and backs up the previously saved violations
     * to <code>violation-counts-old</code>.
     */
    private void saveViolationCounts() throws MojoExecutionException {
        Path savedVC = Paths.get(getArtifactsDir(), "violation-counts");
        Path savedVCOld = Paths.get(getArtifactsDir(), "violation-counts-old");
        Path newVC = Paths.get(System.getProperty("user.dir"), "violation-counts");

        try {
            getLog().info("Saving previous violation-counts to violation-counts-old...");
            savedVC.toFile().createNewFile();
            Files.move(savedVC, savedVCOld, StandardCopyOption.REPLACE_EXISTING);

            getLog().info("Saving current violation-counts to violation-counts...");
            newVC.toFile().createNewFile();
            Files.copy(newVC, savedVC);
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new MojoExecutionException("Failed to save violation-counts", ex);
        }
    }
}
