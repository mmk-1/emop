package edu.cornell;

import java.io.BufferedReader;
import java.io.FileReader;
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

import edu.cornell.emop.util.Violation;
import edu.illinois.starts.jdeps.DiffMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

@Mojo(name = "vms", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST, lifecycle = "vms")
public class VmsMojo extends DiffMojo {

    /**
     * Specific SHA to use when analyzing differences between versions. Should correspond to the previous run of VMS.
     */
    @Parameter(property = "lastSha", required = false)
    private String lastSha;

    /**
     * Whether to use the current working tree as the updated version of code or to use the most recent commit as the
     * updated version of code when making code comparisons.
     */
    @Parameter(property = "useWorkingTree", required = false, defaultValue = "true")
    private boolean useWorkingTree;

    /**
     * Whether to treat all found violations as new, regardless of previous runs.
     */
    @Parameter(property = "firstRun", required = false, defaultValue = "false")
    private boolean firstRun;

    private Path gitDir;
    private Path oldVC;
    private Path newVC;

    // DiffFormatter is used to analyze differences between versions of code including both renames and line insertions
    // and deletions
    private final DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);

    // Map from a file to a map representing the number of additional lines added or deleted at each line
    // of the original file. If the value is 0, the line has been modified in place.
    // Note: If renames are involved, the old name of the file is used.
    // More information about how differences are represented in JGit can be found here:
    // https://archive.eclipse.org/jgit/docs/jgit-2.0.0.201206130900-r/apidocs/org/eclipse/jgit/diff/Edit.html
    //          file        line     lines modified
    private Map<String, Map<Integer, Integer>> lineChanges = new HashMap<>();

    // Maps renamed files to the original names
    private Map<String, String> renames = new HashMap<>();

    // Contains new classes which have been made between commits
    private Set<String> newClasses = new HashSet<>();

    private Set<Violation> oldViolations;
    private Set<Violation> newViolations;

    public void execute() throws MojoExecutionException {
        getLog().info("[eMOP] Invoking the VMS Mojo...");
        lastSha = getLastSha();
        gitDir = basedir.toPath().resolve(".git");
        oldVC = Paths.get(getArtifactsDir(), "violation-counts-old");
        newVC = Paths.get(System.getProperty("user.dir"), "violation-counts");

        touchVmsFiles();

        findLineChangesAndRenames(getCommitDiffs());
        getLog().info("Number of files renamed: " + renames.size());
        getLog().info("Number of changed files found: " + lineChanges.size());
        oldViolations = Violation.parseViolations(oldVC);
        newViolations = Violation.parseViolations(newVC);
        getLog().info("Number of total violations found: " + newViolations.size());
        if (!firstRun) {
            removeDuplicateViolations();
        }
        getLog().info("Number of \"new\" violations found: " + newViolations.size());
        saveViolationCounts();
        rewriteViolationCounts();
    }

    /**
     * Fetches the two most recent commits of the repository and finds the differences between them.
     * TODO: Update description with how the previous version is determined based on user input and lastSha file
     *
     * @return List of differences between two most recent commits of the repository
     * @throws MojoExecutionException if error is encountered at runtime
     */
    private List<DiffEntry> getCommitDiffs() throws MojoExecutionException {
        Iterable<RevCommit> commits;
        ObjectReader objectReader;
        List<DiffEntry> diffs;
        List<AbstractTreeIterator> trees = new ArrayList<>();

        try (Git git = Git.open(gitDir.toFile())) {
            objectReader = git.getRepository().newObjectReader();

            // Set up diffFormatter
            diffFormatter.setRepository(git.getRepository());
            diffFormatter.setContext(0);
            diffFormatter.setDetectRenames(true);

            // Get more recent version of code (either working tree or most recent commit)
            if (useWorkingTree) {
                trees.add(new FileTreeIterator(git.getRepository()));
            } else {
                RevCommit mostRecentCommit = git.log().setMaxCount(1).call().iterator().next();
                trees.add(new CanonicalTreeParser(null, objectReader, mostRecentCommit.getTree().getId()));
            }

            // Get older version of code (either from user or file)
            RevCommit olderCommit;
            if (lastSha != null || !lastSha.isEmpty()) {
                ObjectId shaId = git.getRepository().resolve(lastSha);
                olderCommit = git.getRepository().parseCommit(shaId);
                trees.add(new CanonicalTreeParser(null, objectReader, olderCommit.getTree().getId()));
            } else {
                return null;
            }
            diffs = diffFormatter.scan(trees.get(1), trees.get(0));
        } catch (IOException | GitAPIException exception) {
            throw new MojoExecutionException("Failed to fetch code version");
        }

        return diffs;
    }

    /**
     * Updates the lineChanges and renames based on found differences.
     *
     * @param diffs List of differences between two versions of the same program
     * @throws MojoExecutionException if error is encountered at runtime
     */
    private void findLineChangesAndRenames(List<DiffEntry> diffs) throws MojoExecutionException {
        try {
            for (DiffEntry diff : diffs) {
                // Determines if the file is new, read more here:
                // https://archive.eclipse.org/jgit/docs/jgit-2.0.0.201206130900-r/apidocs/org/eclipse/jgit/diff/DiffEntry.html
                if (!diff.getNewPath().equals(DiffEntry.DEV_NULL) && !diff.getOldPath().equals(DiffEntry.DEV_NULL)) {
                    // Gets renamed classes
                    if (!diff.getNewPath().equals(diff.getOldPath())) {
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
     * Removes newViolations of violations believed to be duplicates from violation-counts-old.
     */
    private void removeDuplicateViolations() {
        Set<Violation> violationsToRemove = new HashSet<>();
        for (Violation newViolation : newViolations) {
            for (Violation oldViolation : oldViolations) {
                if (isSameViolationAfterDifferences(oldViolation, newViolation)) {
                    violationsToRemove.add(newViolation);
                    break;
                }
            }
        }
        newViolations.removeAll(violationsToRemove);
    }

    /**
     * Determines if an old violation in a class could be mapped to the new violation after accounting for differences
     * in code and renames.
     *
     * @param oldViolation Original violation to compare
     * @param newViolation New violation to compare
     * @return Whether the old violation can be mapped to the new violation, after code changes and renames
     */
    private boolean isSameViolationAfterDifferences(Violation oldViolation, Violation newViolation) {
        return oldViolation.getSpecification().equals(newViolation.getSpecification())
                && (oldViolation.getClassName().equals(newViolation.getClassName())
                    || isRenamed(oldViolation.getClassName(), newViolation.getClassName()))
                && hasSameLineNumber(oldViolation.getClassName(), oldViolation.getLineNum(), newViolation.getLineNum());
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
     * Determines whether an old line in a file can be mapped to the new line. An offset is calculated which determines
     * the maximum boundary of the distance a line has moved based off of differences between code versions. If two
     * lines are within this offset of each other (in the correct direction), they are considered mappable.
     * Take the following example:
     * Old code          New code
     * line 1            line 1
     * line 2            line 2
     * line 3            new line
     * line 4            line 3
     *                   line 4
     * Lines 1 and 2 do not see any differences, so their offset is 0 and they will only map to lines 1 and 2 in the new
     * code, respectively. Line 3 sees an addition of one line, so the offset here is 1 and both the new line and line 3
     * in the new code will map to the previous line 3. Similarly, line 4 also takes the previous addition of a line
     * into account and has an offset of 1, and both lines 3 and 4 in the new code will map to the previous line 4.
     * Deletions work similarly, and are represented by a negative offset.
     * TODO: The current implementation is generous with finding the "same" violation. The offset is very accurate in
     *  finding the precise location unmodified code has been moved to. This information can be leveraged for more
     *  accuracy when differentiating which violations are new or old when they occur close together and the line
     *  itself where the violation occurred is not a part of any direct differences in code. There are also errors that
     *  can arise because of differences between the last commit and the previous run of violation-counts (whose
     *  violations we keep track of) this can be fixed by incorporating sha information.
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
     * Rewrites <code>violation-counts</code> to only include violations in newViolations.
     */
    private void rewriteViolationCounts() throws MojoExecutionException {
        Path vc = Paths.get(System.getProperty("user.dir"), "violation-counts");

        List<String> lines = null;
        try {
            lines = Files.readAllLines(vc);
        } catch (IOException exception) {
            throw new MojoExecutionException("Failure encountered when reading violation-counts");
        }

        try (PrintWriter writer = new PrintWriter(vc.toFile())) {
            for (String line : lines) {
                if (isNewViolation(line)) {
                    writer.println(line);
                }
            }
        } catch (IOException exception) {
            throw new MojoExecutionException("Failure encountered when writing violation-counts");
        }
    }

    /**
     * Whether a violation line is a new violation.
     *
     * @param violation Violation line being considered
     * @return Whether the violation is a new violation
     */
    private boolean isNewViolation(String violation) {
        Violation parsedViolation = Violation.parseViolation(violation);
        for (Violation newViolation : newViolations) {
            if (newViolation.equals(parsedViolation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures that <code>violation-counts</code> and <code>violation-counts-old</code>
     * both exist, creating empty files if not.
     */
    private void touchVmsFiles() throws MojoExecutionException {
        try {
            oldVC.toFile().createNewFile();
            newVC.toFile().createNewFile();
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new MojoExecutionException("Failed to create violation-counts", ex);
        }
    }

    /**
     * If the working tree is clean, saves the most recent <code>violation-counts</code>
     * created by RV-Monitor in <code>violation-counts-old</code>.
     */
    private void saveViolationCounts() throws MojoExecutionException {
        try (Git git = Git.open(gitDir.toFile())) {
            if (git.status().call().isClean()) {
                Files.copy(newVC, oldVC, StandardCopyOption.REPLACE_EXISTING);

                try (PrintWriter out = new PrintWriter(Paths.get(getArtifactsDir(), "last-SHA").toFile())) {
                    out.println(git.getRepository().resolve(Constants.HEAD).name());
                }
            }
        } catch (IOException | GitAPIException ex) {
            ex.printStackTrace();
            throw new MojoExecutionException("Failed to save violation-counts", ex);
        }
    }

    /**
     * Reads and returns the last SHA VMS has been run on from file.
     *
     * @return String of the previous SHA, null if the file is empty
     * @throws MojoExecutionException if error encountered at runtime
     */
    private String getLastSha() throws MojoExecutionException {
        if (lastSha != null && !lastSha.isEmpty()) {
            return lastSha;
        }
        Path lastShaPath = Paths.get(getArtifactsDir(), "last-SHA");
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(lastShaPath.toFile()))) {
            return bufferedReader.readLine();
        } catch (IOException exception) {
            throw new MojoExecutionException("Error encountered when reading lastSha");
        }
    }
}
