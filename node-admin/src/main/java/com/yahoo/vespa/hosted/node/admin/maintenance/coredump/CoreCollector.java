// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.coredump;

import com.yahoo.vespa.hosted.dockerapi.ProcessResult;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes in an uncompressed core dump and collects relevant metadata.
 *
 * @author freva
 */
public class CoreCollector {
    private static final Logger logger = Logger.getLogger(CoreCollector.class.getName());

    private static final Pattern CORE_GENERATOR_PATH_PATTERN = Pattern.compile("^Core was generated by `(?<path>.*?)'.$");
    private static final Pattern EXECFN_PATH_PATTERN = Pattern.compile("^.* execfn: '(?<path>.*?)'");
    private static final Pattern FROM_PATH_PATTERN = Pattern.compile("^.* from '(?<path>.*?)'");

    private final DockerOperations docker;
    private final Path gdb;

    public CoreCollector(DockerOperations docker, Path pathToGdbInContainer) {
        this.docker = docker;
        this.gdb = pathToGdbInContainer;
    }

    Path readBinPathFallback(NodeAgentContext context, Path coredumpPath) {
        String command = gdb + " -n -batch -core " + coredumpPath + " | grep \'^Core was generated by\'";
        String[] wrappedCommand = {"/bin/sh", "-c", command};
        ProcessResult result = docker.executeCommandInContainerAsRoot(context, wrappedCommand);

        Matcher matcher = CORE_GENERATOR_PATH_PATTERN.matcher(result.getOutput());
        if (! matcher.find()) {
            throw new RuntimeException(String.format("Failed to extract binary path from GDB, result: %s, command: %s",
                    result, Arrays.toString(wrappedCommand)));
        }
        return Paths.get(matcher.group("path").split(" ")[0]);
    }

    Path readBinPath(NodeAgentContext context, Path coredumpPath) {
        String[] command = {"file", coredumpPath.toString()};
        try {
            ProcessResult result = docker.executeCommandInContainerAsRoot(context, command);
            if (result.getExitStatus() != 0) {
                throw new RuntimeException("file command failed with " + result);
            }

            Matcher execfnMatcher = EXECFN_PATH_PATTERN.matcher(result.getOutput());
            if (execfnMatcher.find()) {
                return Paths.get(execfnMatcher.group("path").split(" ")[0]);
            }

            Matcher fromMatcher = FROM_PATH_PATTERN.matcher(result.getOutput());
            if (fromMatcher.find()) {
                return Paths.get(fromMatcher.group("path").split(" ")[0]);
            }
        } catch (RuntimeException e) {
            context.log(logger, Level.WARNING, String.format("Failed getting bin path, command: %s. " +
                    "Trying fallback instead", Arrays.toString(command)), e);
        }

        return readBinPathFallback(context, coredumpPath);
    }

    List<String> readBacktrace(NodeAgentContext context, Path coredumpPath, Path binPath, boolean allThreads) {
        String threads = allThreads ? "thread apply all bt" : "bt";
        String[] command = {gdb.toString(), "-n", "-ex", threads, "-batch", binPath.toString(), coredumpPath.toString()};

        ProcessResult result = docker.executeCommandInContainerAsRoot(context, command);
        if (result.getExitStatus() != 0)
            throw new RuntimeException("Failed to read backtrace " + result + ", Command: " + Arrays.toString(command));

        return Arrays.asList(result.getOutput().split("\n"));
    }

    List<String> readJstack(NodeAgentContext context, Path coredumpPath, Path binPath) {
        String[] command = {"jhsdb", "jstack", "--exe", binPath.toString(), "--core", coredumpPath.toString()};

        ProcessResult result = docker.executeCommandInContainerAsRoot(context, command);
        if (result.getExitStatus() != 0)
            throw new RuntimeException("Failed to read jstack " + result + ", Command: " + Arrays.toString(command));

        return Arrays.asList(result.getOutput().split("\n"));
    }

    /**
     * Collects metadata about a given core dump
     * @param context context of the NodeAgent that owns the core dump
     * @param coredumpPath path to core dump file inside the container
     * @return map of relevant metadata about the core dump
     */
    Map<String, Object> collect(NodeAgentContext context, Path coredumpPath) {
        Map<String, Object> data = new HashMap<>();
        try {
            Path binPath = readBinPath(context, coredumpPath);

            data.put("bin_path", binPath.toString());
            if (binPath.getFileName().toString().equals("java")) {
                data.put("backtrace_all_threads", readJstack(context, coredumpPath, binPath));
            } else {
                data.put("backtrace", readBacktrace(context, coredumpPath, binPath, false));
                data.put("backtrace_all_threads", readBacktrace(context, coredumpPath, binPath, true));
            }
        } catch (RuntimeException e) {
            context.log(logger, Level.WARNING, "Failed to extract backtrace", e);
        }
        return data;
    }
}
