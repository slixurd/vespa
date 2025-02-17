// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance;

import com.google.common.collect.ImmutableSet;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextImpl;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder;
import com.yahoo.vespa.hosted.node.admin.task.util.process.TestTerminal;
import com.yahoo.vespa.test.file.TestFileSystem;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author dybis
 */
@RunWith(Enclosed.class)
public class StorageMaintainerTest {

    public static class DiskUsageTests {

        private final TestTerminal terminal = new TestTerminal();

        @Test
        public void testDiskUsed() throws IOException {
            StorageMaintainer storageMaintainer = new StorageMaintainer(terminal, null, null);
            FileSystem fileSystem = TestFileSystem.create();
            NodeAgentContext context = new NodeAgentContextImpl.Builder("host-1.domain.tld").fileSystem(fileSystem).build();
            Files.createDirectories(context.pathOnHostFromPathInNode("/"));

            terminal.expectCommand("du -xsk /home/docker/host-1 2>&1", 0, "321\t/home/docker/host-1/");
            assertEquals(Optional.of(328_704L), storageMaintainer.getDiskUsageFor(context));

            // Value should still be cached, no new execution against the terminal
            assertEquals(Optional.of(328_704L), storageMaintainer.getDiskUsageFor(context));
        }

        @Test
        public void testNonExistingDiskUsed() {
            StorageMaintainer storageMaintainer = new StorageMaintainer(terminal, null, null);
            long usedBytes = storageMaintainer.getDiskUsedInBytes(null, Paths.get("/fake/path"));
            assertEquals(0L, usedBytes);
        }

        @After
        public void after() {
            terminal.verifyAllCommandsExecuted();
        }
    }

    public static class ArchiveContainerDataTests {
        @Test
        public void archive_container_data_test() throws IOException {
            // Create some files in containers
            FileSystem fileSystem = TestFileSystem.create();
            NodeAgentContext context1 = createNodeAgentContextAndContainerStorage(fileSystem, "container-1");
            createNodeAgentContextAndContainerStorage(fileSystem, "container-2");

            Path pathToArchiveDir = fileSystem.getPath("/home/docker/container-archive");
            Files.createDirectories(pathToArchiveDir);

            Path containerStorageRoot = context1.pathOnHostFromPathInNode("/").getParent();
            Set<String> containerStorageRootContentsBeforeArchive = FileFinder.from(containerStorageRoot)
                    .maxDepth(1)
                    .stream()
                    .map(FileFinder.FileAttributes::filename)
                    .collect(Collectors.toSet());
            assertEquals(ImmutableSet.of("container-archive", "container-1", "container-2"), containerStorageRootContentsBeforeArchive);


            // Archive container-1
            StorageMaintainer storageMaintainer = new StorageMaintainer(null, null, pathToArchiveDir);
            storageMaintainer.archiveNodeStorage(context1);

            // container-1 should be gone from container-storage
            Set<String> containerStorageRootContentsAfterArchive = FileFinder.from(containerStorageRoot)
                    .maxDepth(1)
                    .stream()
                    .map(FileFinder.FileAttributes::filename)
                    .collect(Collectors.toSet());
            assertEquals(ImmutableSet.of("container-archive", "container-2"), containerStorageRootContentsAfterArchive);

            // container archive directory should contain exactly 1 directory - the one we just archived
            List<FileFinder.FileAttributes> containerArchiveContentsAfterArchive = FileFinder.from(pathToArchiveDir).maxDepth(1).list();
            assertEquals(1, containerArchiveContentsAfterArchive.size());
            Path archivedContainerStoragePath = containerArchiveContentsAfterArchive.get(0).path();
            assertTrue(archivedContainerStoragePath.getFileName().toString().matches("container-1_[0-9]{14}"));
            Set<String> archivedContainerStorageContents = FileFinder.files(archivedContainerStoragePath)
                    .stream()
                    .map(fileAttributes -> archivedContainerStoragePath.relativize(fileAttributes.path()).toString())
                    .collect(Collectors.toSet());
            assertEquals(ImmutableSet.of("opt/vespa/logs/vespa/vespa.log", "opt/vespa/logs/vespa/zookeeper.log"), archivedContainerStorageContents);
        }

        private NodeAgentContext createNodeAgentContextAndContainerStorage(FileSystem fileSystem, String containerName) throws IOException {
            NodeAgentContext context = new NodeAgentContextImpl.Builder(containerName + ".domain.tld")
                    .fileSystem(fileSystem).build();

            Path containerVespaHomeOnHost = context.pathOnHostFromPathInNode(context.pathInNodeUnderVespaHome(""));
            Files.createDirectories(context.pathOnHostFromPathInNode("/etc/something"));
            Files.createFile(context.pathOnHostFromPathInNode("/etc/something/conf"));

            Files.createDirectories(containerVespaHomeOnHost.resolve("logs/vespa"));
            Files.createFile(containerVespaHomeOnHost.resolve("logs/vespa/vespa.log"));
            Files.createFile(containerVespaHomeOnHost.resolve("logs/vespa/zookeeper.log"));

            Files.createDirectories(containerVespaHomeOnHost.resolve("var/db"));
            Files.createFile(containerVespaHomeOnHost.resolve("var/db/some-file"));

            Path containerRootOnHost = context.pathOnHostFromPathInNode("/");
            Set<String> actualContents = FileFinder.files(containerRootOnHost)
                    .stream()
                    .map(fileAttributes -> containerRootOnHost.relativize(fileAttributes.path()).toString())
                    .collect(Collectors.toSet());
            Set<String> expectedContents = new HashSet<>(Arrays.asList(
                    "etc/something/conf",
                    "opt/vespa/logs/vespa/vespa.log",
                    "opt/vespa/logs/vespa/zookeeper.log",
                    "opt/vespa/var/db/some-file"));
            assertEquals(expectedContents, actualContents);
            return context;
        }
    }
}
