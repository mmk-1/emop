package edu.cornell;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import edu.cornell.emop.maven.AgentLoader;
import edu.cornell.emop.util.Util;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

@Mojo(name = "rpp-handler", requiresDependencyResolution = ResolutionScope.TEST)
public class RppHandlerMojo extends MonitorMojo {

    static Set<String> criticalSpecsSet;
    static Set<String> backgroundSpecsSet;
    static SimpleDateFormat timeFormatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");

    File metaInfoDirectory;

    @Parameter(property = "criticalSpecsFile", defaultValue = "")
    private String criticalSpecsFile;

    @Parameter(property = "backgroundSpecsFile", defaultValue = "")
    private String backgroundSpecsFile;

    @Parameter(property = "javamopAgent")
    private String javamopAgent;

    /**
     * Reads a file containing specifications (one on each line), and outputs the set contained in the file.
     * @param specsFilePath The path to the specifications file.
     * @return Set of specifications.
     */
    protected Set<String> parseSpecsFile(String specsFilePath) {
        try {
            return new HashSet<>(Files.readAllLines(new File(specsFilePath).toPath()))
                    .stream()
                    .filter(spec -> !spec.isEmpty())
                    .map(spec -> spec.endsWith("MonitorAspect") ? spec :
                            spec + "MonitorAspect").collect(Collectors.toSet());
        } catch (IOException ex) {
            return new HashSet<>();
        }
    }

    /**
     * Deduces what files we will read for critical and background phase specs.
     * If the user provides either the criticalSpecsFile or backgroundSpecsFile argument, then we will compute our
     * critical and background specs based off of the user's choice. Otherwise, we will check whether there were
     * pre-recorded critical and background specs files from previous runs.
     */
    private void setupSpecFiles() {
        if (criticalSpecsFile == null && backgroundSpecsFile == null) {
            // if the user didn't provide any
            File autogenCriticalSpecs = new File(metaInfoDirectory, "rpp-critical-specs.txt");
            File autogenBackgroundSpecs = new File(metaInfoDirectory, "rpp-background-specs.txt");
            if (autogenCriticalSpecs.exists()) {
                criticalSpecsFile = autogenCriticalSpecs.getAbsolutePath();
                getLog().info("Detected autogenerated critical specs file: " + criticalSpecsFile);
            }
            if (autogenBackgroundSpecs.exists()) {
                backgroundSpecsFile = autogenBackgroundSpecs.getAbsolutePath();
                getLog().info("Detected autogenerated background specs file: " + backgroundSpecsFile);
            }
        }
    }

    /**
     * Computes the critical specs set and the background specs set.
     */
    private void computeSpecSets() {
        Set<String> allSpecs;
        if (!Boolean.getBoolean("rpsRpp")) {
            allSpecs = Util.retrieveSpecListFromJar(javamopAgent, getLog());
        } else {
            allSpecs = parseSpecsFile(new File(metaInfoDirectory, "affected-specs.txt").getAbsolutePath());
        }
        // if we still don't have any spec files, then we'd just need to obtain all specs and run them in critical
        if (criticalSpecsFile == null && backgroundSpecsFile == null) {
            criticalSpecsSet = allSpecs;
            backgroundSpecsSet = new HashSet<>();
        } else if (backgroundSpecsFile == null) {
            // if we only have the critical specs, then our background specs is the set difference
            criticalSpecsSet = parseSpecsFile(criticalSpecsFile);
            backgroundSpecsSet = Sets.difference(allSpecs, criticalSpecsSet);
        } else if (criticalSpecsFile == null) {
            // if we only have the background specs, then our critical specs is the set difference
            backgroundSpecsSet = parseSpecsFile(backgroundSpecsFile);
            criticalSpecsSet = Sets.difference(allSpecs, backgroundSpecsSet);
        } else {
            // we have both files, so all we need to do is read from them.
            criticalSpecsSet = parseSpecsFile(criticalSpecsFile);
            backgroundSpecsSet = parseSpecsFile(backgroundSpecsFile);
            backgroundSpecsSet.removeAll(criticalSpecsSet);
            if (criticalSpecsSet.isEmpty() && backgroundSpecsSet.isEmpty()) {
                getLog().error("Both critical sets and background sets were empty!");
                System.exit(1);
            }
        }
        // filtering out specs that are not in the set of all specs
        criticalSpecsSet.retainAll(allSpecs);
        backgroundSpecsSet.retainAll(allSpecs);
        // if there are any specs in the universe that we haven't considered, add them to the critical specs set
        Set<String> remainingSpecs = new HashSet<>(allSpecs);
        remainingSpecs.removeAll(criticalSpecsSet);
        remainingSpecs.removeAll(backgroundSpecsSet);
        criticalSpecsSet.addAll(remainingSpecs);
    }

    /**
     * Creates a JavaMOP agent JAR configured to only monitor the specified set of specifications.
     * @param mode an identifier for the jar (either "critical" or "background").
     * @param specsToMonitor  the list of specifications that the agent should monitor.
     * @return The path to the created JAR.
     */
    private String setUpSingleJar(String mode, Set<String> specsToMonitor) {
        File javamopAgentFile = new File(javamopAgent);
        File createdJar = new File(metaInfoDirectory, mode + "-javamop.jar");
        try {
            Files.copy(javamopAgentFile.toPath(), createdJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Util.generateNewMonitorFile(metaInfoDirectory + File.separator + mode + "-ajc.xml",
                    specsToMonitor, MonitorMojo.monitorIncludes, MonitorMojo.monitorExcludes);
            Util.replaceFileInJar(createdJar.getAbsolutePath(), "/META-INF/aop-ajc.xml",
                    metaInfoDirectory + File.separator + mode + "-ajc.xml");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return createdJar.getAbsolutePath();
    }

    /**
     * Creates new agent JARs for running critical and background phases, and sets up System properties to record
     * paths to critical and background phase JARs.
     */
    private void setupJars() {
        if (javamopAgent == null) {
            javamopAgent = getLocalRepository().getBasedir() + File.separator + "javamop-agent"
                    + File.separator + "javamop-agent"
                    + File.separator + "1.0"
                    + File.separator + "javamop-agent-1.0.jar";
        }
        System.setProperty("previous-javamop-agent", javamopAgent);
        setupSpecFiles();
        computeSpecSets();
        String criticalRunAgentPath = setUpSingleJar("critical", criticalSpecsSet);
        String backgroundRunAgentPath = setUpSingleJar("background", backgroundSpecsSet);
        if (!criticalRunAgentPath.isEmpty()) {
            System.setProperty("rpp-agent", criticalRunAgentPath);
            System.setProperty("background-agent", backgroundRunAgentPath);
        } else {
            getLog().info("Critical phase had no specs, skipping and running background phase...");
            System.setProperty("rpp-agent", backgroundRunAgentPath);
            System.setProperty("background-agent", ""); // prevent RppMojo from running a second time
        }
    }

    /**
     * This mojo performs setup for RPP, and configures surefire to monitor the set of critical specs.
     * @throws MojoExecutionException where instrumentation for surefire did not succeed.
     */
    public void execute() throws MojoExecutionException {
        getLog().info("RPP preprocessing start: " + timeFormatter.format(new Date()));
        metaInfoDirectory = new File(getArtifactsDir());
        // prepare the two jars
        setupJars();
        System.setProperty("running-rpp", "true");
        // load agent that will harness surefire and manipulate arguments to surefire before test execution
        if (!AgentLoader.loadDynamicAgent("JavaAgent.class")) {
            throw new MojoExecutionException("Could not attach agent");
        }
        getLog().info("RPP preprocessing end: " + timeFormatter.format(new Date()));
    }
}
