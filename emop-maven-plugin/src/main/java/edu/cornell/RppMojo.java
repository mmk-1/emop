package edu.cornell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import edu.cornell.emop.maven.SurefireMojoInterceptor;
import edu.cornell.emop.util.Util;
import edu.cornell.emop.util.Violation;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

@Mojo(name = "rpp", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST, lifecycle = "rpp")
public class RppMojo extends RppHandlerMojo {

    @Parameter(property = "demoteCritical", defaultValue = "false", required = false)
    private boolean demoteCritical;

    /**
     * Runs maven surefire.
     * @throws MojoExecutionException when the surefire invocation fails.
     */
    private void invokeSurefire() throws MojoExecutionException {
        getLog().info("RPP background phase surefire execution start: " + timeFormatter.format(new Date()));
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        try {
            PrintStream ps = new PrintStream(new FileOutputStream(
                    getArtifactsDir() + File.separator + "background-surefire-run.txt"));
            // FIXME: this somehow doesn't redirect the entire surefire output. Debug while PR is ongoing
            System.setOut(ps);
            System.setErr(ps);
            SurefireMojoInterceptor.sfMojo.getClass().getMethod("execute").invoke(SurefireMojoInterceptor.sfMojo);
            System.setOut(stdout);
            System.setErr(stderr);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        } catch (InvocationTargetException ex) {
            throw new RuntimeException(ex);
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex);
        }
        getLog().info("RPP background phase surefire execution end: " + timeFormatter.format(new Date()));
    }

    public static void writeSpecsToFile(Set<String> specs, File file) throws FileNotFoundException {
        try (PrintWriter writer = new PrintWriter(file)) {
            for (String spec : specs) {
                writer.println(spec);
            }
        }
    }

    public void updateCriticalAndBackgroundSpecs(String criticalViolationsPath, String bgViolationsPath, String javamopAgent)
            throws MojoExecutionException, FileNotFoundException {
        Set<String> criticalSpecsSet = new HashSet<>();
        Set<String> allSpecs = Util.retrieveSpecListFromJar(javamopAgent, getLog());
        if (!demoteCritical && !RppHandlerMojo.criticalSpecsSet.equals(allSpecs)) {
            // demote specs if the critical specs set contained all specs (first run)
            criticalSpecsSet.addAll(RppHandlerMojo.criticalSpecsSet);
        }
        // read the violation-counts files and output the list of critical and background specs for next time
        // (in the case that the user doesn't provide files for critical and background specs)
        Set<String> violatedSpecs = Violation.parseViolationSpecs(Paths.get(criticalViolationsPath));
        Set<String> bgViolatedSpecs = Violation.parseViolationSpecs(Paths.get(bgViolationsPath));
        violatedSpecs.addAll(bgViolatedSpecs);
        violatedSpecs = violatedSpecs.stream().map(spec -> spec.endsWith("MonitorAspect") ? spec :
                spec + "MonitorAspect").collect(Collectors.toSet());
        // implicitly demote all specs that were not violated and not already in the critical specs set
        criticalSpecsSet.addAll(violatedSpecs);
        File artifactsDir = new File(getArtifactsDir());
        File metaCriticalSpecsFile = new File(artifactsDir, "rpp-critical-specs.txt");
        File metaBackgroundSpecsFile = new File(artifactsDir, "rpp-background-specs.txt");
        Set<String> backgroundSpecsSet = new HashSet<>(allSpecs);
        backgroundSpecsSet.remove(criticalSpecsSet);
        writeSpecsToFile(criticalSpecsSet, metaCriticalSpecsFile);
        writeSpecsToFile(backgroundSpecsSet, metaBackgroundSpecsFile);
    }

    /**
     * This mojo runs RPP.
     * @throws MojoExecutionException if RPP fails.
     */
    public void execute() throws MojoExecutionException {
        getLog().info("RPP background phase start: " + timeFormatter.format(new Date()));
        // by the time this method is invoked, we have finished invoking the critical specs surefire run
        String criticalViolationsPath = Util.moveViolationCounts(getBasedir(), getArtifactsDir(), "critical");
        String bgViolationsPath = "";
        if (criticalViolationsPath.isEmpty()) {
            getLog().info("violation-counts file for critical run was not produced, skipping moving...");
        }
        String previousJavamopAgent = System.getProperty("rpp-agent");
        String backgroundAgent = System.getProperty("background-agent");
        if (!backgroundAgent.isEmpty()) {
            System.setProperty("previous-javamop-agent", previousJavamopAgent);
            System.setProperty("rpp-agent", backgroundAgent);
            invokeSurefire();
            bgViolationsPath = Util.moveViolationCounts(getBasedir(), getArtifactsDir(), "background");
            if (bgViolationsPath.isEmpty()) {
                getLog().info("violation-counts file for background run was not produced, skipping moving...");
            }
        } else { // edge case where critical phase runs all specs
            getLog().info("No specs to monitor for background phase, terminating...");
        }
        try {
            updateCriticalAndBackgroundSpecs(criticalViolationsPath, bgViolationsPath, previousJavamopAgent);
        } catch (FileNotFoundException ex) {
            getLog().error("Failed to automatically update critical and background specs.");
            System.exit(1);
        }
        getLog().info("RPP background phase end: " + timeFormatter.format(new Date()));
    }

}
